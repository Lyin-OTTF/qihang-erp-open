package cn.qihangerp.service.impl;

import cn.qihangerp.common.*;
import cn.qihangerp.enums.EnumShopType;
import cn.qihangerp.mapper.*;
import cn.qihangerp.model.bo.ShopOrderCreateBo;
import cn.qihangerp.model.bo.PosOrderCreateBo;
import cn.qihangerp.model.bo.VirtualOrderConfirmAndNewBo;
import cn.qihangerp.model.entity.*;
import cn.qihangerp.model.query.ShopOrderQueryBo;
import cn.qihangerp.service.ShopGoodsSkuShipItemService;
import cn.qihangerp.service.ShopOrderService;
import cn.qihangerp.statemachine.TransitionValidator;
import com.alibaba.fastjson2.util.DateUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
* @author qilip
* @description 针对表【oms_shop_order】的数据库操作Service实现
* @createDate 2025-07-15 11:36:42
*/
@Slf4j
@AllArgsConstructor
@Service
public class ShopOrderServiceImpl extends ServiceImpl<ShopOrderMapper, ShopOrder>
    implements ShopOrderService {
    private final OOrderMapper orderMapper;
    private final OOrderStockingMapper orderStockMapper;
    private final ShopOrderItemMapper itemMapper;
    private final ShopGoodsSkuShipItemService shipItemService;
    private final ShopGoodsSkuMapper shopGoodsSkuMapper;
    private final ShopGoodsMapper shopGoodsMapper;
    private final ShopMemberMapper shopMemberMapper;
    private final OShopMapper shopMapper;
    private final ErpMerchantMapper merchantMapper;
    private final OMarketingDiscountRuleMapper discountRuleMapper;
    private final ShopOrderPromotionMapper shopOrderPromotionMapper;
    private final ErpRecoveryRecordMapper recoveryRecordMapper;
    private final ErpRecoveryDeductionMapper recoveryDeductionMapper;
    private final TransitionValidator transitionValidator;

    /**
     * 普通发货订单
     * @param bo
     * @param pageQuery
     * @return
     */
    @Override
    public PageResult<ShopOrder> queryOrderPageList(ShopOrderQueryBo bo, PageQuery pageQuery) {
        Long startTimestamp = null;
        Long endTimestamp = null;

        if(org.springframework.util.StringUtils.hasText(bo.getStartTime())){
            boolean b = DateHelper.isValidDate(bo.getStartTime());
            if(!b){
//                bo.setStartTime(bo.getStartTime()+" 00:00:00");
                bo.setStartTime("");
            }
        }
        if(org.springframework.util.StringUtils.hasText(bo.getEndTime())){
            boolean b = DateHelper.isValidDate(bo.getEndTime());
            if(b){
//                bo.setEndTime(bo.getEndTime()+" 23:59:59");
                bo.setEndTime("");
            }
        }else{
            bo.setEndTime(bo.getStartTime());
        }


        if(StringUtils.hasText(bo.getStartTime())) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime startTime = LocalDateTime.parse(bo.getStartTime()+" 00:00:00", formatter);
            LocalDateTime endTime = LocalDateTime.parse(bo.getEndTime()+" 23:59:59", formatter);

            startTimestamp = startTime.toEpochSecond(ZoneOffset.ofHours(8));
            endTimestamp = endTime.toEpochSecond(ZoneOffset.ofHours(8));
        }
        // 按订单在系统中的创建时间倒序（新拉取/新建的订单排在最前），id 作为次级排序保证稳定
        pageQuery.setOrderByColumn("create_on,id");
        pageQuery.setIsAsc("desc,desc");


        LambdaQueryWrapper<ShopOrder> queryWrapper = new LambdaQueryWrapper<ShopOrder>()
                .eq(bo.getOrderStatus()!=null, ShopOrder::getOrderStatus,bo.getOrderStatus())
                .eq(bo.getRefundStatus()!=null, ShopOrder::getRefundStatus,bo.getRefundStatus())
                .eq(bo.getShopId()!=null, ShopOrder::getShopId,bo.getShopId())
                .eq(bo.getShopType()!=null, ShopOrder::getShopType,bo.getShopType())
                .eq(bo.getMerchantId()!=null, ShopOrder::getMerchantId,bo.getMerchantId())
                .ge(startTimestamp!=null, ShopOrder::getOrderTime,startTimestamp)
                .le(endTimestamp!=null, ShopOrder::getOrderTime,endTimestamp)
                .like(StringUtils.hasText(bo.getOrderId()), ShopOrder::getOrderId,bo.getOrderId())
                .eq(ShopOrder::getDeliverMethod,0)
                ;

        Page<ShopOrder> orderPage = this.baseMapper.selectPage(pageQuery.build(), queryWrapper);
        if(orderPage.getRecords()!=null){
            for (var order:orderPage.getRecords()) {
                order.setItems(itemMapper.selectList(
                        new LambdaQueryWrapper<ShopOrderItem>()
                                .eq(ShopOrderItem::getShopOrderId,order.getId()))
                );
            }
        }
        return PageResult.build(orderPage);
    }

    @Override
    public ShopOrder queryDetailById(Long orderId) {
        ShopOrder shopOrder = this.baseMapper.selectById(orderId);
        if(shopOrder!=null){
            shopOrder.setItems(itemMapper.selectList(
                    new LambdaQueryWrapper<ShopOrderItem>()
                            .eq(ShopOrderItem::getShopOrderId,shopOrder.getId())));

            if(shopOrder.getDeliverMethod()==1){
                String[] productIds = shopOrder.getItems().stream()
                    .map(ShopOrderItem::getProductId)
                    .toArray(String[]::new);
                List<ShopGoodsSkuShipItem> list = shipItemService.list(new LambdaQueryWrapper<ShopGoodsSkuShipItem>()
                        .in(ShopGoodsSkuShipItem::getProductId, productIds)
                );
                shopOrder.setItemList(list);
            }

            if(StringUtils.hasText(shopOrder.getPlatformUserId())) {
                List<ShopMember> shopMembers = shopMemberMapper.selectList(new LambdaQueryWrapper<ShopMember>().eq(ShopMember::getPlatformUserId, shopOrder.getPlatformUserId()));
                if(shopMembers!=null&&shopMembers.size()>0) {
                    shopOrder.setMember(shopMembers.get(0));
                }
            }
        }
        return shopOrder;
    }

    @Override
    public ShopOrder queryDetailByOrderNo(String orderNo) {
        var shopOrders = this.baseMapper.selectList(new LambdaQueryWrapper<ShopOrder>().eq(ShopOrder::getOrderId, orderNo));
        if(shopOrders!=null&&shopOrders.size()>0){
            return shopOrders.get(0);
        }else return null;
    }

    @Override
    public List<ShopOrderItem> queryOrderItemList(Long shopOrderId) {
        return itemMapper.selectList(
                new LambdaQueryWrapper<ShopOrderItem>()
                        .eq(ShopOrderItem::getShopOrderId,shopOrderId));
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResultVo<Long> updateShopOrderLogistic(String shopOrderNo, String tradeNo, String logisticCompany, String logisticCode, LocalDateTime consignTime) {
        List<ShopOrder> shopOrderList = this.baseMapper.selectList(new LambdaQueryWrapper<ShopOrder>().eq(ShopOrder::getOrderId, shopOrderNo));
        if(shopOrderList!=null&&shopOrderList.size()>0){
            log.info("======更新店铺订单物流，找到订单了：{}",shopOrderList.size());
            Integer currentStatus = shopOrderList.get(0).getOrderStatus();
            // 状态机校验：发货（→已发货）
            transitionValidator.validateOrderTransition(currentStatus, 2, shopOrderNo);
            ShopOrder shopOrder = new  ShopOrder();
            shopOrder.setId(shopOrderList.get(0).getId());
            shopOrder.setOrderStatus(2);
            shopOrder.setLogisticsPartnerCode(logisticCompany);
            shopOrder.setLogisticsOrderNo(logisticCode);
            if(consignTime!=null){
                shopOrder.setShipDoneTime(consignTime.atZone(java.time.ZoneId.systemDefault()).toInstant().getEpochSecond());
            }
            shopOrder.setErpShipStatus(2);
            shopOrder.setErpShipCompany(logisticCompany);
            shopOrder.setErpShipCode(logisticCode);
            shopOrder.setErpShipTime(consignTime);
            shopOrder.setUpdateOn(LocalDateTime.now());
            this.baseMapper.updateById(shopOrder);

            // 更新订单库
            List<OOrder> oOrders = orderMapper.selectList(new LambdaQueryWrapper<OOrder>().eq(OOrder::getOrderNum, shopOrderNo).eq(OOrder::getShopId, shopOrder.getShopId()));
            if(oOrders!=null&&oOrders.size()>0){
                log.info("======更新店铺订单物流，找到了订单库数据：{}",oOrders.get(0).getId());
                OOrder oOrder = new OOrder();
                oOrder.setId(oOrders.get(0).getId());
                oOrder.setOrderStatus(oOrders.get(0).getOrderStatus()==1?2:null);
                oOrder.setWaybillCompany(logisticCompany);
                oOrder.setWaybillCode(logisticCode);
                oOrder.setUpdateBy("自动更新物流");
                oOrder.setUpdateTime(LocalDateTime.now());
                orderMapper.updateById(oOrder);
            }else{
                log.error("======更新店铺订单物流，未找到订单库订单");
            }

            //  更新 发货订单表
            // 先处理发货订单（其实就是推送过去发货的订单）
            List<OOrderStocking> oOrderStockings = orderStockMapper.selectList(new LambdaQueryWrapper<OOrderStocking>().eq(OOrderStocking::getShippingErpOrderCode, tradeNo));
            if(oOrderStockings!=null&&oOrderStockings.size()>0){
                log.info("======更新店铺订单物流，找到了tradeNO订单：{}",tradeNo);
                OOrderStocking oOrderStocking = new OOrderStocking();
                oOrderStocking.setId(oOrderStockings.get(0).getId());
                oOrderStocking.setShippingCompany(logisticCompany);
                oOrderStocking.setShippingNumber(logisticCode);
                oOrderStocking.setShippingTime(consignTime);
                oOrderStocking.setSendStatus(2);
                orderStockMapper.updateById(oOrderStocking);
            }else{
                // 继续查找发货单
                List<OOrderStocking> oOrderStockings1 = orderStockMapper.selectList(new LambdaQueryWrapper<OOrderStocking>().eq(OOrderStocking::getOrderNum, shopOrderNo));
                if(oOrderStockings1!=null&&oOrderStockings1.size()>0){
                    log.info("======更新店铺订单物流，找到了shopOrderNO订单：{}",shopOrderNo);
                    OOrderStocking oOrderStocking = new OOrderStocking();
                    oOrderStocking.setId(oOrderStockings1.get(0).getId());
                    oOrderStocking.setShippingCompany(logisticCompany);
                    oOrderStocking.setShippingNumber(logisticCode);
                    oOrderStocking.setShippingTime(consignTime);
                    oOrderStocking.setSendStatus(2);
                    orderStockMapper.updateById(oOrderStocking);
                }else{
                    log.info("======更新店铺订单物流，未找到发货单");
                }
            }
            return ResultVo.success();
        }else{
            log.error("======更新店铺订单物流，没有找到订单:{}",shopOrderNo);
            return ResultVo.error("不存在");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResultVo<Long> saveOrder(Long shopId,Long merchantId,Integer shopType, ShopOrder order) {
        if (order == null) return ResultVo.error(ResultVoEnum.SystemException);

//        OShop shop = shopService.getById(shopId);
//        if (shop == null) return ResultVo.error("店铺不存在");
        order.setShopId(shopId);
        order.setMerchantId(merchantId);
        order.setShopType(shopType);

        List<ShopOrder> shopOrderList = this.baseMapper.selectList(new LambdaQueryWrapper<ShopOrder>()
                .eq(ShopOrder::getOrderId, order.getOrderId())
                .eq(ShopOrder::getShopId, shopId)
        );
        Long shopOrderId=null;
        if (shopOrderList != null && shopOrderList.size() > 0) {
            // 存在，修改
//            ShopOrder update = new ShopOrder();
//            update.setId(shopOrderList.get(0).getId());
//            update.setOrderStatus(order.getOrderStatus());
//            update.setPlatformOrderStatusText(order.getPlatformOrderStatusText());
//            update.setPlatformOrderStatus(order.getPlatformOrderStatus());
//            update.setSellerMemo(order.getSellerMemo());
//            update.setBuyerMemo(order.getBuyerMemo());
//            update.setRemark(order.getRemark());
//            update.setRefundStatus(order.getRefundStatus());
//            update.setUpdateTime(order.getUpdateTime());
//            update.setUpdateTimeText(order.getUpdateTimeText());
//            update.setUpdateOn(LocalDateTime.now());
            if(StringUtils.isEmpty(order.getProvince())) order.setProvince(null);
            if(StringUtils.isEmpty(order.getCity())) order.setCity(null);
            if(StringUtils.isEmpty(order.getTown())) order.setTown(null);
            if(StringUtils.isEmpty(order.getCounty())) order.setCounty(null);
            if(StringUtils.isEmpty(order.getReceiverName())) order.setReceiverName(null);
            if(StringUtils.isEmpty(order.getReceiverPhone())) order.setReceiverPhone(null);
            if(StringUtils.isEmpty(order.getAddress())) order.setAddress(null);
            if(StringUtils.isEmpty(order.getEncryptPostAddress())) order.setEncryptPostAddress(null);
            if(StringUtils.isEmpty(order.getEncryptPostTel())) order.setEncryptPostTel(null);
            if(StringUtils.isEmpty(order.getEncryptPostReceiver())) order.setEncryptPostReceiver(null);
            order.setId(shopOrderList.get(0).getId());
            order.setUpdateOn(LocalDateTime.now());
            this.baseMapper.updateById(order);
            shopOrderId = order.getId();
        } else {
            // 不存在，新增
            order.setOrderType(0);
            order.setOrderMode(0);
            order.setErpShipStatus(0);
            order.setConfirmStatus(0);
            order.setCreateOn(LocalDateTime.now());
            this.baseMapper.insert(order);
            shopOrderId = order.getId();
            // 如果平台会员ID存在，则添加会员到会员表
            if(StringUtils.hasText(order.getPlatformUserId())) {
                ShopMember shopMember = new ShopMember();
                shopMember.setShopType(order.getShopType());
                shopMember.setShopId(order.getShopId());
                shopMember.setMerchantId(order.getMerchantId());
                shopMember.setPlatformUserId(order.getPlatformUserId());
                shopMember.setPlatformAccount(order.getPlatformAccount());
                shopMember.setName(order.getReceiverName());
                shopMember.setPhone(order.getReceiverPhone());
                shopMember.setProvince(order.getProvince());
                shopMember.setCity(order.getCity());
                shopMember.setCounty(order.getCounty());
                shopMember.setTown(order.getTown());
                shopMember.setAddress(order.getAddress());
                shopMember.setStatus(0);
                shopMember.setCreateOn(LocalDateTime.now());
                shopMemberMapper.insert(shopMember);
            }
        }
        // 添加订单item
        for (ShopOrderItem item : order.getItems()) {
            item.setShopId(shopId);
            item.setShopType(order.getShopType());
            item.setMerchantId(order.getMerchantId());
            if (StringUtils.hasText(item.getSkuId())) {
                List<ShopGoodsSku> shopGoodsSkuList = shopGoodsSkuMapper.selectList(new LambdaQueryWrapper<ShopGoodsSku>()
                        .eq(ShopGoodsSku::getSkuId, item.getSkuId())
                        .eq(ShopGoodsSku::getShopId, shopId)
                );
                if (shopGoodsSkuList != null&&!shopGoodsSkuList.isEmpty()) {
                    item.setErpGoodsId(shopGoodsSkuList.get(0).getErpGoodsId());
                    item.setErpGoodsSkuId(shopGoodsSkuList.get(0).getErpGoodsSkuId());
                }
            } else {
                List<ShopGoods> shopGoodsList = shopGoodsMapper.selectList(new LambdaQueryWrapper<ShopGoods>().eq(ShopGoods::getProductId, item.getProductId()));
                if (!shopGoodsList.isEmpty()) {
                    item.setErpGoodsId(shopGoodsList.get(0).getErpGoodsId());
                    item.setErpGoodsSkuId(0L);
                }
            }
            List<ShopOrderItem> orderItems = null;

            if(StringUtils.hasText(item.getSubOrderId())){
                // 用子订单号查询订单item
                orderItems = itemMapper.selectList(
                        new LambdaQueryWrapper<ShopOrderItem>()
                                .eq(ShopOrderItem::getOrderId, item.getOrderId())
                                .eq(ShopOrderItem::getSubOrderId, item.getSubOrderId())
                );
            }else {
                // 有skuid查询订单item
                orderItems = itemMapper.selectList(
                        new LambdaQueryWrapper<ShopOrderItem>()
                                .eq(ShopOrderItem::getOrderId, item.getOrderId())
                                .eq(ShopOrderItem::getProductId, item.getProductId())
                                .eq(StringUtils.hasText(item.getSkuId()), ShopOrderItem::getSkuId, item.getSkuId())
                );
            }

            if (orderItems != null && orderItems.size() > 0) {
                // 更新
                item.setId(orderItems.get(0).getId());
                item.setShopOrderId(shopOrderId);
                item.setUpdateOn(LocalDateTime.now());
                itemMapper.updateById(item);
            } else {
                // 新增
                item.setShopOrderId(shopOrderId);
                item.setCreateOn(LocalDateTime.now());
                itemMapper.insert(item);
            }
        }

        if (shopOrderList != null && shopOrderList.size() > 0) {
            return ResultVo.error(ResultVoEnum.DataExist, "订单已经存在，更新成功",shopOrderId);
        } else {
            return ResultVo.success(shopOrderId);
        }

    }

    /**
     * 虚拟发货订单确认并生成实物发货订单
     * @param confirmAndNewBo
     * @param man
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResultVo<Long> virtualOrderConfirmAndNewShipOrder(VirtualOrderConfirmAndNewBo confirmAndNewBo, String man) {
        if(confirmAndNewBo.getItemList()==null||confirmAndNewBo.getItemList().size()==0) return ResultVo.error("请添加商品");
        ShopOrder order = this.baseMapper.selectById(confirmAndNewBo.getShopOrderId());
        if(order==null){
            return ResultVo.error("订单不存在");
        }else if(order.getDeliverMethod().intValue()!=1){
            return ResultVo.error("订单类型不对，无法操作！");
        } else if (order.getConfirmStatus()!=0) {
            return ResultVo.error("无需确认");
        }
        Long shopMemberId = 0L;
        // 查询shopMember
        List<ShopMember> shopMembers = shopMemberMapper.selectList(new LambdaQueryWrapper<ShopMember>().eq(ShopMember::getPlatformUserId, order.getPlatformUserId()));
        if(shopMembers!=null&&shopMembers.size()>0){
            shopMemberId = shopMembers.get(0).getId();
        }
        Double goodsAmount = confirmAndNewBo.getItemList().stream().mapToDouble(x -> x.getPurPrice().doubleValue()).sum();
        goodsAmount = goodsAmount*100;
        List<ShopOrderItem> shopOrderItemList = new ArrayList<>();

        // 插入新发货订单
        ShopOrder shopOrderNew = new ShopOrder();
        shopOrderNew.setMerchantId(order.getMerchantId());
        shopOrderNew.setShopType(order.getShopType());
        shopOrderNew.setShopId(order.getShopId());
        shopOrderNew.setOrderMode(1);//订单模式0店铺订单1手工订单
        shopOrderNew.setOrderId(order.getOrderId());
        shopOrderNew.setOrderTime(System.currentTimeMillis()/1000);
        shopOrderNew.setUpdateTime(System.currentTimeMillis()/1000);
        shopOrderNew.setOrderStatus(1);
        shopOrderNew.setRefundStatus(1);
        shopOrderNew.setGoodsAmount(goodsAmount.intValue());
        shopOrderNew.setOrderAmount(goodsAmount.intValue());
        shopOrderNew.setFreight(0);
        shopOrderNew.setPaymentAmount(0);
        shopOrderNew.setSellerDiscount(0);
        shopOrderNew.setDiscountAmount(0);
        shopOrderNew.setPlatformDiscount(0);
        shopOrderNew.setRemark(order.getRemark());
        shopOrderNew.setPlatformUserId(order.getPlatformUserId());
        shopOrderNew.setPlatformAccount(order.getPlatformAccount());
        shopOrderNew.setReceiverName(confirmAndNewBo.getName());
        shopOrderNew.setReceiverPhone(confirmAndNewBo.getPhone());
        shopOrderNew.setProvince(confirmAndNewBo.getProvince());
        shopOrderNew.setCity(confirmAndNewBo.getCity());
        shopOrderNew.setCounty(confirmAndNewBo.getCounty());
        shopOrderNew.setAddress(confirmAndNewBo.getAddress());
        shopOrderNew.setConfirmStatus(1);
        shopOrderNew.setConfirmTime(LocalDateTime.now());
        shopOrderNew.setErpShipStatus(0);
        shopOrderNew.setCreateOn(LocalDateTime.now());
        shopOrderNew.setDeliverMethod(0);
        shopOrderNew.setShopMemberId(shopMemberId);
        shopOrderNew.setOrderTimeText(order.getOrderTimeText());
        this.baseMapper.insert(shopOrderNew);
        for(var it:confirmAndNewBo.getItemList()){
            ShopOrderItem shopOrderItem = new ShopOrderItem();
            shopOrderItem.setShopOrderId(shopOrderNew.getId());
            shopOrderItem.setMerchantId(order.getMerchantId());
            shopOrderItem.setShopType(order.getShopType());
            shopOrderItem.setShopId(order.getShopId());
            shopOrderItem.setProductId("");
            shopOrderItem.setSkuId("");
            shopOrderItem.setImg(it.getColorImage());
            shopOrderItem.setQuantity(it.getQuantity());
            shopOrderItem.setSalePrice(it.getPurPrice().multiply(BigDecimal.valueOf(100)).intValue());
            shopOrderItem.setTitle(it.getGoodsName());
            shopOrderItem.setOnAftersaleSkuCnt(0);
            shopOrderItem.setFinishAftersaleSkuCnt(0);
            shopOrderItem.setSkuCode(it.getSkuCode());
            shopOrderItem.setMarketPrice(it.getRetailPrice().multiply(BigDecimal.valueOf(100)).intValue());
            shopOrderItem.setRealPrice(it.getRetailPrice().multiply(BigDecimal.valueOf(100)).intValue());
            shopOrderItem.setItemAmount(it.getRetailPrice().multiply(BigDecimal.valueOf(it.getQuantity())).multiply(BigDecimal.valueOf(100)).intValue());
            shopOrderItem.setDiscountAmount(0);
            shopOrderItem.setSkuName(it.getSkuName());
            shopOrderItem.setIsDiscounted("false");
            shopOrderItem.setIsChangePrice("false");
            shopOrderItem.setErpGoodsId(it.getGoodsId());
            shopOrderItem.setErpGoodsSkuId(it.getId());
            shopOrderItem.setOrderId(shopOrderNew.getOrderId());
            shopOrderItem.setSubOrderId(shopOrderItem.getOrderId()+"-"+shopOrderItem.getErpGoodsSkuId());
            shopOrderItem.setOrderTime(shopOrderNew.getOrderTime());
            shopOrderItem.setCreateOn(LocalDateTime.now());
            shopOrderItem.setRefundStatus(1);
            shopOrderItem.setRefundAmount(0);

            itemMapper.insert(shopOrderItem);
        }
        // 更新自己
        ShopOrder update =new ShopOrder();
        update.setId(order.getId());
        update.setUpdateOn(LocalDateTime.now());
        update.setConfirmStatus(1);
        update.setConfirmTime(LocalDateTime.now());
        this.baseMapper.updateById(update);
        log.info("=============螳螂虚拟订单确认创建发货订单成功：{}================",shopOrderNew.getId());
        return ResultVo.success(shopOrderNew.getId());
    }

    /**
     * 取消订单
     * @param id 店铺订单id
     * @param cancelReason 取消原因
     * @param man 操作人
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResultVo cancelOrder(Long id, String cancelReason, String man) {
        ShopOrder shopOrder = this.baseMapper.selectById(id);
        if(shopOrder==null) return ResultVo.error("找不到订单数据");
        else if(shopOrder.getOrderStatus().intValue()==3) return ResultVo.error("已完成的单不可以取消");
        // 状态机校验：取消（→已取消）
        try {
            transitionValidator.validateOrderTransition(shopOrder.getOrderStatus(), 11, shopOrder.getOrderId());
        } catch (cn.qihangerp.statemachine.IllegalTransitionException e) {
            return ResultVo.error("当前订单状态不允许取消：" + cn.qihangerp.statemachine.OrderStateMachine.getStateName(shopOrder.getOrderStatus()));
        }
        // 取消订单
        ShopOrder update = new ShopOrder();
        update.setId(id);
        update.setCancelReason(man+" 操作取消订单，取消原因："+cancelReason);
        update.setUpdateOn(LocalDateTime.now());
        update.setOrderStatus(11);
        this.baseMapper.updateById(update);

        // 回滚确认订单状态
        if(shopOrder.getShopType().intValue()== EnumShopType.TANG_LANG.getIndex()) {
            List<ShopOrder> shopOrders = this.baseMapper.selectList(new LambdaQueryWrapper<ShopOrder>()
                    .eq(ShopOrder::getOrderId, shopOrder.getOrderId())
                    .eq(ShopOrder::getDeliverMethod, 1));
            if(shopOrders!=null&&shopOrders.size()>0) {
                for(ShopOrder order:shopOrders) {
                    ShopOrder shopOrderUpdate = new ShopOrder();
                    shopOrderUpdate.setId(order.getId());
                    shopOrderUpdate.setConfirmStatus(0);
                    shopOrderUpdate.setUpdateOn(LocalDateTime.now());
                    this.baseMapper.updateById(shopOrderUpdate);
                }
            }
        }
        return ResultVo.success();
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResultVo<Long> createOrder(ShopOrderCreateBo order, String man) {
        if (order == null) return ResultVo.error("数据错误");
        if(order.getItemList() == null || order.getItemList().isEmpty()) return ResultVo.error("数据错误：没有商品");
        OShop shop = shopMapper.selectById(order.getShopId());
        if (shop == null) return ResultVo.error("店铺不存在");
        // 查订单号
        List<ShopOrder> shopOrders = this.baseMapper.selectList(new LambdaQueryWrapper<ShopOrder>()
                .eq(ShopOrder::getShopId, shop.getId())
                .eq(ShopOrder::getOrderId, order.getOrderNum()));
        if(shopOrders!=null&&shopOrders.size()>0) {
            return ResultVo.error("订单号已存在");
        }
        // 查商品，并组合shopOrderItem
        List<ShopOrderItem> shopOrderItemList = new ArrayList<>();
        for(var item : order.getItemList()){
            // 查商品
            ShopGoodsSku shopGoodsSku = shopGoodsSkuMapper.selectById(item.getId());
            if(shopGoodsSku==null) return ResultVo.error("找不到商品数据，id:"+item.getId());
            if(shopGoodsSku.getShopId()!=shop.getId()) return ResultVo.error("店铺商品不属于你");
            // 未关联商品库商品sku时不拦截，erpGoodsSkuId记为0，支持快速下单（后续可在订单库中补关联）
            // 组合
            ShopOrderItem orderItem = new ShopOrderItem();
            orderItem.setMerchantId(shop.getMerchantId());
            orderItem.setShopId(shop.getId());
            orderItem.setShopType(shop.getType());
            orderItem.setProductId(shopGoodsSku.getProductId());
            orderItem.setSkuId(shopGoodsSku.getSkuId());
            orderItem.setOuterProductId(shopGoodsSku.getOuterProductId());
            orderItem.setOuterSkuId(shopGoodsSku.getOuterSkuId());
            orderItem.setTitle(shopGoodsSku.getProductTitle());
            orderItem.setSkuName(shopGoodsSku.getSkuName());
            orderItem.setSkuCode(shopGoodsSku.getSkuCode());
            orderItem.setBarcode(item.getBarcode());
            orderItem.setImg(shopGoodsSku.getImg());
            orderItem.setQuantity(item.getQuantity());
            orderItem.setSalePrice(shopGoodsSku.getPrice());
            orderItem.setMarketPrice(shopGoodsSku.getPrice());
            orderItem.setOnAftersaleSkuCnt(0);
            orderItem.setFinishAftersaleSkuCnt(0);
            orderItem.setRealPrice(item.getItemAmount().multiply(BigDecimal.valueOf(100)).intValue());
            orderItem.setItemAmount(item.getItemAmount().multiply(BigDecimal.valueOf(100)).intValue());
            orderItem.setIsChangePrice("false");
            orderItem.setIsDiscounted("false");
            orderItem.setDiscountAmount(0);
            orderItem.setErpGoodsId(shopGoodsSku.getErpGoodsId()==null?0L:shopGoodsSku.getErpGoodsId());
            orderItem.setErpGoodsSkuId(shopGoodsSku.getErpGoodsSkuId()==null?0L:shopGoodsSku.getErpGoodsSkuId());
            orderItem.setOrderId(order.getOrderNum());
            orderItem.setOrderTime(System.currentTimeMillis()/1000);
            orderItem.setCreateOn(LocalDateTime.now());
            orderItem.setRefundStatus(1);
            orderItem.setRefundAmount(0);
            orderItem.setSubOrderId(order.getOrderNum()+"-"+item.getId());
            orderItem.setOrderStatus(1);
            orderItem.setShipStatus(0);
            orderItem.setIsGift(item.getIsGift());
            shopOrderItemList.add(orderItem);
        }
        // 添加订单
        ShopOrder shopOrder = new ShopOrder();
        shopOrder.setMerchantId(shop.getMerchantId());
        shopOrder.setShopId(shop.getId());
        shopOrder.setShopType(shop.getType());
        shopOrder.setOrderId(order.getOrderNum());
        shopOrder.setOrderTime(System.currentTimeMillis()/1000);
        shopOrder.setUpdateTime(System.currentTimeMillis()/1000);
        shopOrder.setOrderStatus(1);
        shopOrder.setRefundStatus(1);
        shopOrder.setGoodsAmount(order.getGoodsAmount().multiply(BigDecimal.valueOf(100)).intValue());
        shopOrder.setFreight(order.getPostage()==null? 0 : order.getPostage().multiply(BigDecimal.valueOf(100)).intValue());
        shopOrder.setChangePrice(order.getChangePrice()==null?0:order.getChangePrice().multiply(BigDecimal.valueOf(100)).intValue());
        shopOrder.setPlatformDiscount(0);
        // 折扣优惠
        shopOrder.setDiscountAmount(order.getDiscountAmount()==null?0:order.getDiscountAmount().multiply(BigDecimal.valueOf(100)).intValue());
        // 商家优惠=手动改价+折扣优惠
        shopOrder.setSellerDiscount(shopOrder.getChangePrice()+shopOrder.getDiscountAmount());

        // 订单金额=商品金额+运费-手动改价-优惠折扣
        Integer orderAmount = shopOrder.getGoodsAmount() +shopOrder.getFreight()-shopOrder.getChangePrice()-shopOrder.getDiscountAmount();
        shopOrder.setOrderAmount(orderAmount);
        // 抵扣金额
        Integer deductionPrice =order.getDeductionPrice()==null?0: order.getDeductionPrice().multiply(BigDecimal.valueOf(100)).intValue();
        /** 抵扣金额和订单金额对比*/
        if(orderAmount >= deductionPrice) {
            // 订单金额 >= 抵扣金额
            shopOrder.setDeductionPrice(deductionPrice);
        }else{
            // 剩余抵扣不能用 (只抵扣订单金额部分，剩下的需要继续结算)
            shopOrder.setDeductionPrice(orderAmount);
        }
        // 支付金额=订单金额-抵扣
        shopOrder.setPaymentAmount(shopOrder.getOrderAmount()-shopOrder.getDeductionPrice());
        shopOrder.setPaymentMethod(order.getPayMethod());
        shopOrder.setBuyerMemo(order.getBuyerMemo());
        shopOrder.setProvince(order.getProvince());
        shopOrder.setCity(order.getCity());
        shopOrder.setCounty(order.getTown());
        shopOrder.setAddress(order.getAddress());
        shopOrder.setReceiverName(order.getReceiverName());
        shopOrder.setReceiverPhone(order.getReceiverPhone());
        shopOrder.setShipDoneTime(0L);
        shopOrder.setConfirmStatus(0);
        shopOrder.setErpShipStatus(0);
        shopOrder.setCreateOn(LocalDateTime.now());
        shopOrder.setShopMemberId(0L);
        shopOrder.setPlatformUserId("0");
        shopOrder.setDeliverMethod(0);
        shopOrder.setOrderMode(1);
        shopOrder.setOrderType(0);
        shopOrder.setFinishTime(0L);
        // 处理订单会员信息，如果会员存在直接加入
        if(order.getShopMemberId()==null) order.setShopMemberId(0L);
        if(order.getShopMemberId()>0){
            ShopMember shopMember = shopMemberMapper.selectById(order.getShopMemberId());
            if(shopMember==null){
                // 不存在，重新设置为0
                order.setShopMemberId(0L);
            }
        }
        if(order.getShopMemberId()==0){
            // 不存在，插入并更新
            ShopMember newMember = new ShopMember();
            newMember.setMerchantId(shop.getMerchantId());
            newMember.setShopId(shop.getId());
            newMember.setShopType(shop.getType());
            newMember.setName(order.getReceiverName());
            newMember.setPhone(order.getReceiverPhone());
            newMember.setProvince(order.getProvince());
            newMember.setCity(order.getCity());
            newMember.setCounty(order.getTown());
            newMember.setAddress(order.getAddress());
            newMember.setStatus(1);
            newMember.setCreateOn(LocalDateTime.now());
            shopMemberMapper.insert(newMember);
            order.setShopMemberId(newMember.getId());
        }
        shopOrder.setShopMemberId(order.getShopMemberId());
        //处理销售员
        shopOrder.setFinderId(order.getFinderId()==null?"":order.getFinderId().toString());

        this.baseMapper.insert(shopOrder);
        for(var item : shopOrderItemList){
            item.setShopOrderId(shopOrder.getId());
            itemMapper.insert(item);
        }

        // 添加优惠明细
        /***1 折扣 */
        if(order.getDiscountAmount()!=null&&order.getDiscountAmount().intValue()>0){
            // 折扣有使用
            if(order.getDiscountId()!=null){
                OMarketingDiscountRule discount = discountRuleMapper.selectById(order.getDiscountId());
                if(discount!=null){
                    ShopOrderPromotion promotion1 = new ShopOrderPromotion();
                    promotion1.setShopOrderId(shopOrder.getId());
                    promotion1.setPromotionId(discount.getId().toString());
                    promotion1.setPromotionName(discount.getRuleName());
                    promotion1.setDiscountFee(order.getDiscountAmount().toString());
                    promotion1.setPromotionDesc(discount.getRemark());
                    shopOrderPromotionMapper.insert(promotion1);
                }else{
                    ShopOrderPromotion promotion0 = new ShopOrderPromotion();
                    promotion0.setShopOrderId(shopOrder.getId());
                    promotion0.setPromotionId("O");
                    promotion0.setPromotionName("手动折扣");
                    promotion0.setDiscountFee(order.getDiscountAmount().toString());
                    promotion0.setPromotionDesc("");
                    shopOrderPromotionMapper.insert(promotion0);
                }
            }else{
                ShopOrderPromotion promotion0 = new ShopOrderPromotion();
                promotion0.setShopOrderId(shopOrder.getId());
                promotion0.setPromotionId("O");
                promotion0.setPromotionName("手动折扣");
                promotion0.setDiscountFee(order.getDiscountAmount().toString());
                promotion0.setPromotionDesc("");
                shopOrderPromotionMapper.insert(promotion0);
            }
        }
        /**2 手动改价*/
        if(order.getChangePrice()!=null&&order.getChangePrice().intValue()>0){
            ShopOrderPromotion promotion0 = new ShopOrderPromotion();
            promotion0.setShopOrderId(shopOrder.getId());
            promotion0.setPromotionId("O");
            promotion0.setPromotionName("手动改价");
            promotion0.setDiscountFee(order.getChangePrice().toString());
            promotion0.setPromotionDesc("");
            shopOrderPromotionMapper.insert(promotion0);
        }

        // 处理 回收抵扣
        if(shopOrder.getDeductionPrice()>0 && order.getIsRecoveryDeduction()==1){
            ErpRecoveryRecord recoveryRecord = new ErpRecoveryRecord();
            recoveryRecord.setRecoveryNo("RE"+ DateUtils.format(LocalDateTime.now(), "yyyyMMddHHmmss"));
            recoveryRecord.setRecoveryDate(LocalDateTime.now());
            recoveryRecord.setCustomerId(shopOrder.getShopMemberId());
            recoveryRecord.setCustomerName(order.getReceiverName());
            recoveryRecord.setCustomerPhone(order.getReceiverPhone());
            recoveryRecord.setCustomerAddress(order.getProvince()+order.getCity()+order.getTown()+order.getAddress());
            recoveryRecord.setMerchantId(shopOrder.getMerchantId());
            ErpMerchant erpMerchant = merchantMapper.selectById(shopOrder.getMerchantId());
            recoveryRecord.setMerchantName(erpMerchant!=null?erpMerchant.getName():"");
            recoveryRecord.setShopId(shopOrder.getShopId());
            recoveryRecord.setShopName(shop.getName());
            recoveryRecord.setGoldWeight(order.getGoldWeight());
            recoveryRecord.setSilverWeight(order.getSilverWeight());
            recoveryRecord.setGoldPrice(order.getGoldPrice());
            recoveryRecord.setSilverPrice(order.getSilverPrice());
            // 计算总价
            BigDecimal goldPrice = order.getGoldWeight().multiply(order.getGoldPrice());
            BigDecimal silverPrice = order.getSilverWeight().multiply(order.getSilverPrice());
            recoveryRecord.setTotalAmount(goldPrice.add(silverPrice));
//            Integer recoveryTotalAmount=recoveryRecord.getTotalAmount().multiply(BigDecimal.valueOf(100)).intValue();
            recoveryRecord.setRemark(order.getDeductionRemark());
            recoveryRecord.setCreatedTime(LocalDateTime.now());
            recoveryRecord.setCreatedBy(man);

            /** 抵扣金额和订单金额对比*/
            if(orderAmount >= deductionPrice) {
                // 订单金额 >= 抵扣金额  就是全部抵扣掉了
                recoveryRecord.setSettlementType(1);//1-仅抵扣，2-仅现金，3-混合
                recoveryRecord.setDeductedAmount(recoveryRecord.getTotalAmount());
                recoveryRecord.setRemainingAmount(BigDecimal.valueOf(0.0));
                recoveryRecord.setStatus(1);
                recoveryRecord.setSettlementStatus(2);
            }else{
                // 剩余抵扣不能用 (只抵扣订单金额部分，剩下的需要继续结算)
                recoveryRecord.setSettlementType(3);//1-仅抵扣，2-仅现金，3-混合
                recoveryRecord.setDeductedAmount(BigDecimal.valueOf(shopOrder.getDeductionPrice()).multiply(BigDecimal.valueOf(100)));
                recoveryRecord.setRemainingAmount(recoveryRecord.getTotalAmount().subtract(recoveryRecord.getDeductedAmount()));
                recoveryRecord.setStatus(1);
                recoveryRecord.setSettlementStatus(1);
            }

            // 插入回收记录
            recoveryRecordMapper.insert(recoveryRecord);
            // 增加回收抵扣
            ErpRecoveryDeduction erpRecoveryDeduction = new ErpRecoveryDeduction();
            erpRecoveryDeduction.setOrderId(shopOrder.getId());
            erpRecoveryDeduction.setRecoveryId(recoveryRecord.getId());
            erpRecoveryDeduction.setDeductionAmount(BigDecimal.valueOf(shopOrder.getDeductionPrice()).multiply(BigDecimal.valueOf(100)));
            erpRecoveryDeduction.setDeductionTime(LocalDateTime.now());
            erpRecoveryDeduction.setCreatedBy(man);
            recoveryDeductionMapper.insert(erpRecoveryDeduction);
        }

        // TODO:处理锁库
        if(order.getIsLock()!=null&&order.getIsLock()==1){

        }
        return ResultVo.success(shopOrder.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResultVo<Long> createPosOrder(PosOrderCreateBo order, Long shopId, Long merchantId, String man) {
        if (order == null) return ResultVo.error("数据错误");
        if(order.getItems() == null || order.getItems().isEmpty()) return ResultVo.error("数据错误：没有商品");

        // 生成订单号
        String orderNum = "POS" + System.currentTimeMillis();

        // 查商品，并组合shopOrderItem
        List<ShopOrderItem> shopOrderItemList = new ArrayList<>();
        for(PosOrderCreateBo.PosOrderItemBo item : order.getItems()){
            ShopGoodsSku shopGoodsSku = shopGoodsSkuMapper.selectById(item.getSkuId());
            if(shopGoodsSku==null) return ResultVo.error("找不到商品数据，skuId:"+item.getSkuId());

            // 组合订单商品
            ShopOrderItem orderItem = new ShopOrderItem();
            orderItem.setMerchantId(merchantId);
            orderItem.setShopId(shopId);
            orderItem.setShopType(EnumShopType.OFFLINE.getIndex());
            orderItem.setProductId(shopGoodsSku.getProductId());
            orderItem.setSkuId(shopGoodsSku.getSkuId());
            orderItem.setTitle(item.getGoodsName());
            orderItem.setSkuName(item.getSkuName());
            orderItem.setSkuCode(item.getSkuCode());
            orderItem.setBarcode(item.getBarcode());
            orderItem.setImg(shopGoodsSku.getImg());
            orderItem.setQuantity(item.getQuantity());
            orderItem.setSalePrice(item.getUnitPrice().multiply(BigDecimal.valueOf(100)).intValue());
            orderItem.setMarketPrice(item.getUnitPrice().multiply(BigDecimal.valueOf(100)).intValue());
            orderItem.setRealPrice(item.getUnitPrice().multiply(BigDecimal.valueOf(100)).intValue());
            orderItem.setItemAmount(item.getSubtotal().multiply(BigDecimal.valueOf(100)).intValue());
            orderItem.setOnAftersaleSkuCnt(0);
            orderItem.setFinishAftersaleSkuCnt(0);
            orderItem.setIsChangePrice("false");
            orderItem.setIsDiscounted("false");
            orderItem.setDiscountAmount(0);
            orderItem.setErpGoodsId(shopGoodsSku.getErpGoodsId());
            orderItem.setErpGoodsSkuId(shopGoodsSku.getErpGoodsSkuId());
            orderItem.setOrderId(orderNum);
            orderItem.setOrderTime(System.currentTimeMillis()/1000);
            orderItem.setCreateOn(LocalDateTime.now());
            orderItem.setRefundStatus(1);
            orderItem.setRefundAmount(0);
            orderItem.setSubOrderId(orderNum+"-"+item.getSkuId());
            orderItem.setOrderStatus(1);
            orderItem.setShipStatus(0);
            orderItem.setIsGift(0);
            shopOrderItemList.add(orderItem);
        }

        // 计算金额（以元为单位，乘以100转为分）
        Integer goodsAmount = order.getTotalAmount().multiply(BigDecimal.valueOf(100)).intValue();
        Integer discountAmount = order.getDiscountAmount().multiply(BigDecimal.valueOf(100)).intValue();
        Integer recoveryDeductionAmount = order.getRecoveryDeductionAmount().multiply(BigDecimal.valueOf(100)).intValue();
        Integer finalAmount = order.getFinalAmount().multiply(BigDecimal.valueOf(100)).intValue();

        // 添加订单
        ShopOrder shopOrder = new ShopOrder();
        shopOrder.setMerchantId(merchantId);
        shopOrder.setShopId(shopId);
        shopOrder.setShopType(EnumShopType.OFFLINE.getIndex());
        shopOrder.setOrderId(orderNum);
        shopOrder.setOrderTime(System.currentTimeMillis()/1000);
        shopOrder.setUpdateTime(System.currentTimeMillis()/1000);
        shopOrder.setOrderStatus(1);
        shopOrder.setRefundStatus(1);
        shopOrder.setGoodsAmount(goodsAmount);
        shopOrder.setFreight(0);
        shopOrder.setChangePrice(0);
        shopOrder.setPlatformDiscount(0);
        shopOrder.setDiscountAmount(discountAmount);
        shopOrder.setSellerDiscount(discountAmount);
        shopOrder.setOrderAmount(finalAmount);
        shopOrder.setDeductionPrice(recoveryDeductionAmount);
        shopOrder.setPaymentAmount(finalAmount - recoveryDeductionAmount);
        shopOrder.setPaymentMethod(order.getPaymentMethod());
        shopOrder.setShipDoneTime(0L);
        shopOrder.setConfirmStatus(0);
        shopOrder.setErpShipStatus(0);
        shopOrder.setCreateOn(LocalDateTime.now());
        shopOrder.setPlatformUserId("0");
        shopOrder.setDeliverMethod(0);
        shopOrder.setOrderMode(1);
        shopOrder.setOrderType(0);
        shopOrder.setFinishTime(0L);

        // 处理会员信息
        if(order.getMemberId()!=null && order.getMemberId()>0){
            shopOrder.setShopMemberId(order.getMemberId());
        }else{
            shopOrder.setShopMemberId(0L);
        }

        // 处理销售员
        if(order.getSalespersonId()!=null){
            shopOrder.setFinderId(order.getSalespersonId().toString());
        }else{
            shopOrder.setFinderId("");
        }

        this.baseMapper.insert(shopOrder);

        // 保存订单商品
        for(var item : shopOrderItemList){
            item.setShopOrderId(shopOrder.getId());
            itemMapper.insert(item);
        }

        // 处理折扣信息
        if(order.getDiscountInfo()!=null && order.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0){
            ShopOrderPromotion promotion = new ShopOrderPromotion();
            promotion.setShopOrderId(shopOrder.getId());
            promotion.setPromotionId(order.getDiscountInfo().getId()!=null?order.getDiscountInfo().getId().toString():"O");
            promotion.setPromotionName(order.getDiscountInfo().getType()==1?"百分比折扣":"固定金额折扣");
            promotion.setDiscountFee(order.getDiscountAmount().toString());
            promotion.setPromotionDesc("");
            shopOrderPromotionMapper.insert(promotion);
        }

        // 处理回收抵扣
        if(order.getRecoveryId()!=null && order.getRecoveryDeductionAmount().compareTo(BigDecimal.ZERO) > 0){
            ErpRecoveryDeduction erpRecoveryDeduction = new ErpRecoveryDeduction();
            erpRecoveryDeduction.setOrderId(shopOrder.getId());
            erpRecoveryDeduction.setRecoveryId(order.getRecoveryId());
            erpRecoveryDeduction.setDeductionAmount(order.getRecoveryDeductionAmount().multiply(BigDecimal.valueOf(100)));
            erpRecoveryDeduction.setDeductionTime(LocalDateTime.now());
            erpRecoveryDeduction.setCreatedBy(man);
            recoveryDeductionMapper.insert(erpRecoveryDeduction);
        }

        log.info("==============POS手工订单创建成功，订单号：{}================", orderNum);
        return ResultVo.success(shopOrder.getId());
    }


    @Override
    public ShopOrder queryByLogisticsNo(String logisticsNo) {
        List<ShopOrder> oShipments =  this.baseMapper.selectList(new LambdaQueryWrapper<ShopOrder>().eq(ShopOrder::getLogisticsOrderNo, logisticsNo));
        if(oShipments.size()>0){
            return oShipments.get(0);
        }else
            return null;
    }
}