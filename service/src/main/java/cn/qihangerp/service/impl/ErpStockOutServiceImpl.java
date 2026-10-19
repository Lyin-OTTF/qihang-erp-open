package cn.qihangerp.service.impl;

import cn.qihangerp.common.*;
import cn.qihangerp.model.bo.InventoryChangeCommand;
import cn.qihangerp.model.entity.*;
import cn.qihangerp.model.request.StockOutItemRequest;
import cn.qihangerp.mapper.ErpStockOutMapper;
import cn.qihangerp.mapper.OOrderStockingMapper;
import cn.qihangerp.service.*;
import cn.qihangerp.utils.DateUtils;
import cn.qihangerp.model.vo.GoodsSkuInventoryVo;
import cn.qihangerp.model.request.StockOutCreateRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import lombok.AllArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
* @author qilip
* @description 针对表【wms_stock_out(出库单)】的数据库操作Service实现
* @createDate 2024-09-22 11:13:23
*/
@AllArgsConstructor
@Service
public class ErpStockOutServiceImpl extends ServiceImpl<ErpStockOutMapper, ErpStockOut>
    implements ErpStockOutService {
    private final ErpStockOutMapper outMapper;
    private final ErpStockOutItemService outItemService;
    private final ErpWarehouseService erpWarehouseService;
    private final OOrderStockingItemBatchService orderStockingItemBatchService;
    private final OOrderStockingItemService orderStockingItemService;
    private final OOrderStockingMapper orderStockingMapper;
    private final OGoodsInventoryService oGoodsInventoryService;
    private final OGoodsInventoryBatchService oGoodsInventoryBatchService;

    @Override
    public PageResult<ErpStockOut> queryPageList(ErpStockOut bo, PageQuery pageQuery) {
        LambdaQueryWrapper<ErpStockOut> queryWrapper = new LambdaQueryWrapper<ErpStockOut>()
                .eq(bo.getMerchantId()!=null, ErpStockOut::getMerchantId, bo.getMerchantId())
                .eq( bo.getShopId()!=null, ErpStockOut::getShopId, bo.getShopId())
                .eq( bo.getShopGroupId()!=null, ErpStockOut::getShopGroupId, bo.getShopGroupId())
                .eq( bo.getStatus()!=null, ErpStockOut::getStatus, bo.getStatus())
                .eq( bo.getType()!=null, ErpStockOut::getType, bo.getType())
                .eq( bo.getWarehouseId()!=null, ErpStockOut::getWarehouseId, bo.getWarehouseId())
                .eq(StringUtils.isNotBlank(bo.getOutNum()), ErpStockOut::getOutNum, bo.getOutNum())
                .eq(StringUtils.isNotBlank(bo.getSourceNum()), ErpStockOut::getSourceNum, bo.getSourceNum())
                .eq(bo.getSourceId()!=null, ErpStockOut::getSourceId, bo.getSourceId())
                ;

        Page<ErpStockOut> pages = outMapper.selectPage(pageQuery.build(), queryWrapper);
        return PageResult.build(pages);
    }

    /**
     * 手动创建出库单
     * @param userId
     * @param userName
     * @param request
     * @return
     */
    @Transactional
    @Override
    public ResultVo<Long> createEntry(Long userId, String userName, StockOutCreateRequest request) {
        if(request.getType() == null ) return ResultVo.error(ResultVoEnum.ParamsError,"缺少参数type");
        if(request.getItemList().isEmpty()) return ResultVo.error(ResultVoEnum.ParamsError,"缺少参数itemList");
        ErpWarehouse erpWarehouse = erpWarehouseService.getById(request.getWarehouseId());
        if(erpWarehouse==null) return ResultVo.error("仓库信息不存在");
        if(StringUtils.isBlank(request.getOutNum())){
            request.setOutNum(DateUtils.parseDateToStr("yyyyMMddHHmmss",LocalDateTime.now()));
        }
        if(StringUtils.isBlank(request.getOperator())){
            request.setOperator(userName);
        }

        Map<Long, List<GoodsSkuInventoryVo>> goodsGroup = request.getItemList().stream()
                .filter(x -> x.getGoodsId() != null)
                .collect(Collectors.groupingBy(GoodsSkuInventoryVo::getGoodsId));
        Long total = request.getItemList().stream().mapToLong(x -> x.getQuantity()!=null ? x.getQuantity() : 0).sum();

        //添加主表信息
        ErpStockOut stockOut = new ErpStockOut();
        stockOut.setMerchantId(0L);
        stockOut.setShopId(0L);
        stockOut.setShopGroupId(0L);
        stockOut.setOutNum(request.getOutNum());
        stockOut.setType(request.getType());
        stockOut.setSourceNum(request.getSourceNo());
        stockOut.setSourceId(0L);
        stockOut.setWarehouseId(request.getWarehouseId());
        stockOut.setRemark(request.getRemark());
        stockOut.setCreateBy(userName);
        stockOut.setCreateTime(LocalDateTime.now());
        stockOut.setGoodsUnit(goodsGroup.size());
        stockOut.setSpecUnit(request.getItemList().size());
        stockOut.setSpecUnitTotal(total.intValue());
        stockOut.setOutTotal(0);
        stockOut.setOperatorId(userId.toString());
        stockOut.setOperatorName(StringUtils.isEmpty(request.getOperator())?userName:request.getOperator());
        stockOut.setPrintStatus(0);
        stockOut.setRemark(request.getRemark());
        stockOut.setStatus(0);//状态（0待入库1部分入库2全部入库）
        stockOut.setWarehouseId(request.getWarehouseId());
        stockOut.setWarehouseName(erpWarehouse.getWarehouseName());
        outMapper.insert(stockOut);

        //添加子表信息
        List<ErpStockOutItem> itemList = new ArrayList<>();
        for(GoodsSkuInventoryVo item: request.getItemList()){
            if(org.springframework.util.StringUtils.hasText(item.getBatchId())) {
                // 指定了库存批次
                OGoodsInventoryBatch batch = oGoodsInventoryBatchService.getById(item.getBatchId());
                if(batch!=null) {
                    ErpStockOutItem outItem = new ErpStockOutItem();
                    outItem.setMerchantId(stockOut.getMerchantId());
                    outItem.setShopId(stockOut.getShopId());
                    outItem.setShopGroupId(stockOut.getShopGroupId());
                    outItem.setEntryId(stockOut.getId());
                    outItem.setType(request.getType());
                    outItem.setBatchId(batch.getId());
                    outItem.setInventoryMode(batch.getInventoryMode());
                    outItem.setGoodsId(batch.getGoodsId());
                    outItem.setPurPrice(batch.getPurPrice() != null ? batch.getPurPrice().doubleValue() : null);
                    outItem.setSkuId(batch.getSkuId());
                    outItem.setSkuCode(batch.getSkuCode());
                    outItem.setGoodsName(item.getGoodsName());
                    outItem.setGoodsNum(item.getGoodsNum());
                    outItem.setSkuName(item.getSkuName());
                    outItem.setGoodsImage(item.getGoodsImg());
                    outItem.setOriginalQuantity(item.getQuantity());
                    outItem.setOutQuantity(0);
                    outItem.setStatus(0);
                    outItem.setWarehouseId(stockOut.getWarehouseId());
                    outItem.setPositionId(0L);
                    outItem.setPurPrice(0.0);
                    outItem.setCreateBy(userName);
                    outItem.setCreateTime(LocalDateTime.now());
                    outItem.setWarehouseId(batch.getWarehouseId());
                    outItem.setPositionId(batch.getPositionId());
                    outItem.setPositionNum(batch.getPositionNum());
                    itemList.add(outItem);
                }
            } else {
                // 未指定批次，仅记录SKU信息，出库时再分配批次
                ErpStockOutItem outItem = new ErpStockOutItem();
                outItem.setMerchantId(stockOut.getMerchantId());
                outItem.setShopId(stockOut.getShopId());
                outItem.setShopGroupId(stockOut.getShopGroupId());
                outItem.setEntryId(stockOut.getId());
                outItem.setType(request.getType());
                outItem.setBatchId(null);
                outItem.setInventoryMode(0);
                outItem.setGoodsId(item.getGoodsId());
                outItem.setSkuId(item.getSkuId());
                outItem.setSkuCode(item.getSkuCode());
                outItem.setGoodsName(item.getGoodsName());
                outItem.setSkuName(item.getSkuName());
                outItem.setGoodsImage(item.getGoodsImg());
                outItem.setGoodsNum(item.getGoodsNum());
                outItem.setPurPrice(item.getPurPrice() != null ? item.getPurPrice().doubleValue() : null);
                outItem.setOriginalQuantity(item.getQuantity());
                outItem.setOutQuantity(0);
                outItem.setStatus(0);
                outItem.setWarehouseId(stockOut.getWarehouseId());
                outItem.setPositionId(0L);
                outItem.setPurPrice(0.0);
                outItem.setCreateBy(userName);
                outItem.setCreateTime(LocalDateTime.now());
                outItem.setWarehouseId(request.getWarehouseId());
                itemList.add(outItem);
            }
        }
        outItemService.saveBatch(itemList);
        return ResultVo.success(stockOut.getId());
    }

    @Override
    public ErpStockOut getDetailAndItemById(Long id) {
        ErpStockOut erpStockOut = outMapper.selectById(id);
        if(erpStockOut !=null){
            List<ErpStockOutItem> itemList = outItemService.list(
                    new LambdaQueryWrapper<ErpStockOutItem>().eq(ErpStockOutItem::getEntryId,id));
            // 填充批次信息
            for(ErpStockOutItem item : itemList){
                if(item.getBatchId() != null){
                    OGoodsInventoryBatch batch = oGoodsInventoryBatchService.getById(item.getBatchId());
                    if(batch != null){
                        item.setBatchNum(batch.getBatchNum());
                        item.setBatchCurrentQty(batch.getCurrentQty());
                    }
                }
            }
            erpStockOut.setItemList(itemList);
        }
        return erpStockOut;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResultVo<Long> stockOut(Long userId, String userName, StockOutItemRequest request) {
        if(request.getEntryItemId() == null) return ResultVo.error(1500,"缺少参数：outItemId");
        if(request.getOutQty()==null || request.getOutQty().longValue()<=0) return ResultVo.error(1500,"缺少参数：出库数量");

        ErpStockOutItem outItem = outItemService.getById(request.getEntryItemId());
        if(outItem == null) return ResultVo.error(1500,"出库数据错误");

        // 如果请求中指定了批次且出库明细未绑定批次，更新批次绑定
        if(request.getBatchId() != null && outItem.getBatchId() == null) {
            ErpStockOutItem batchUpdate = new ErpStockOutItem();
            batchUpdate.setId(outItem.getId());
            batchUpdate.setBatchId(request.getBatchId());
            outItemService.updateById(batchUpdate);
            outItem.setBatchId(request.getBatchId());
        }

        // 判断库存够不够扣减的
        if(outItem.getBatchId()!=null) {
            /***指定了库存批次***减库存****/
            OGoodsInventoryBatch batch = oGoodsInventoryBatchService.getById(outItem.getBatchId());
            if (batch == null) return ResultVo.error(1500, "库存数据不存在");
            if (batch.getCurrentQty().longValue() < request.getOutQty().longValue())
                return ResultVo.error(1500, "库存不足");

            // 1扣减批次库存（原子条件更新：false 说明已被并发请求抢占，不能再出）
            if (!oGoodsInventoryBatchService.deductBatchStock(batch.getId(), request.getOutQty().intValue())) {
                throw new ServiceException("批次库存不足，请刷新后重试");
            }

            // 2扣减总库存 + 同事务写流水（带出库单/订单号与幂等键）
            if(batch.getInventoryId()!=null){
                applyStockOut(batch.getInventoryId(), batch, request.getOutQty().intValue(), outItem, userName);
            }

            // 记录发货备货批次信息(出库类型：1 且 存在sourceOrderItemId)
            if (outItem.getType()==1&&outItem.getSourceOrderItemId() != null) {
                OOrderStockingItem orderStockingItem = orderStockingItemService.getById(outItem.getSourceOrderItemId());
                if (orderStockingItem != null) {
                    OOrderStockingItemBatch batchRecord = new OOrderStockingItemBatch();
                    batchRecord.setOrderStockingId(orderStockingItem.getShipOrderId());
                    batchRecord.setOrderStockingItemId(orderStockingItem.getId());
                    batchRecord.setOrderId(orderStockingItem.getOOrderId().toString());
                    batchRecord.setOrderItemId(orderStockingItem.getOOrderItemId().toString());

                    batchRecord.setMerchantId(outItem.getMerchantId());
                    batchRecord.setShopId(outItem.getShopId());

                    batchRecord.setBatchId(batch.getId());
                    batchRecord.setBatchNum(batch.getBatchNum());
                    batchRecord.setInventoryId(batch.getInventoryId());
                    batchRecord.setGoodsId(batch.getGoodsId());
                    batchRecord.setGoodsNo(batch.getSkuCode());
                    batchRecord.setQuantity(request.getOutQty());
                    batchRecord.setUnitCost(batch.getPurPrice());
                    batchRecord.setTotalCost(batch.getPurPrice() != null ? batch.getPurPrice().multiply(java.math.BigDecimal.valueOf(request.getOutQty())) : null);
                    batchRecord.setWarehouseId(batch.getWarehouseId());
                    batchRecord.setInventoryMode(batch.getInventoryMode());
                    batchRecord.setBarcode(batch.getBarcode());
                    batchRecord.setActualGoldWeight(batch.getActualGoldWeight());
                    batchRecord.setActualSilverWeight(batch.getActualSilverWeight());
                    batchRecord.setLaborCost(batch.getLaborCost());
                    batchRecord.setCertificateNo(batch.getCertificateNo());
                    batchRecord.setPurPrice(batch.getPurPrice());
                    batchRecord.setCreateTime(LocalDateTime.now());
                    batchRecord.setCreateBy(userName);
                    orderStockingItemBatchService.save(batchRecord);
                }
            }
        }else{
            /***没有指定库存批次***减库存****/
            // 判断总库存
            List<OGoodsInventory> inventoryList = oGoodsInventoryService.list(
                    new LambdaQueryWrapper<OGoodsInventory>()
                            .eq(OGoodsInventory::getSkuId, outItem.getSkuId())
                            .eq(OGoodsInventory::getWarehouseId, outItem.getWarehouseId()));

            if(inventoryList.isEmpty()) return ResultVo.error("没有找到库存数据");
            else if (inventoryList.get(0).getAvailableQuantity().intValue()<request.getOutQty().intValue()) {
                return ResultVo.error("库存不足");
            }
            // 查询出所有的批次
            List<OGoodsInventoryBatch> batchList = oGoodsInventoryBatchService.list(
                    new LambdaQueryWrapper<OGoodsInventoryBatch>()
                            .eq(OGoodsInventoryBatch::getInventoryId, inventoryList.get(0).getId()));
            if(batchList.isEmpty()) return ResultVo.error("库存批次数据错误");

            // 按次序减少批次库存（先进先出）
            int shengyu = request.getOutQty().intValue();
            for(var batch:batchList){
                if(shengyu==0) break;

                int batchQty = batch.getCurrentQty();
                int deductQuantity = Math.min(batchQty, shengyu);
                if(batchQty>shengyu){
                    // 原子扣减：并发下若已被抢占则整体回滚，避免批次被重复扣减
                    if (!oGoodsInventoryBatchService.deductBatchStock(batch.getId(), shengyu)) {
                        throw new ServiceException("批次库存并发变化，请重试");
                    }
                    shengyu=0;
                }else{
                    if (!oGoodsInventoryBatchService.deductBatchStock(batch.getId(), batchQty)) {
                        throw new ServiceException("批次库存并发变化，请重试");
                    }
                    deductQuantity = batchQty;
                    shengyu=shengyu-batchQty;
                }
                
                // 记录发货备货批次信息(出库类型：1 且 存在sourceOrderItemId)
                if (outItem.getType()==1&&outItem.getSourceOrderItemId() != null) {
                    OOrderStockingItem orderStockingItem = orderStockingItemService.getById(outItem.getSourceOrderItemId());
                    if(orderStockingItem!=null){
                        OOrderStockingItemBatch batchRecord = new OOrderStockingItemBatch();

                        batchRecord.setOrderStockingId(orderStockingItem.getShipOrderId());
                        batchRecord.setOrderStockingItemId(orderStockingItem.getId());
                        batchRecord.setOrderId(orderStockingItem.getOOrderId().toString());
                        batchRecord.setOrderItemId(orderStockingItem.getOOrderItemId().toString());

                        batchRecord.setMerchantId(outItem.getMerchantId());
                        batchRecord.setShopId(outItem.getShopId());
                        batchRecord.setBatchId(batch.getId());
                        batchRecord.setBatchNum(batch.getBatchNum());
                        batchRecord.setInventoryId(batch.getInventoryId());
                        batchRecord.setGoodsId(batch.getGoodsId());
                        batchRecord.setGoodsNo(batch.getSkuCode());
                        batchRecord.setQuantity(deductQuantity);
                        batchRecord.setUnitCost(batch.getPurPrice());
                        batchRecord.setTotalCost(batch.getPurPrice() != null ? batch.getPurPrice().multiply(java.math.BigDecimal.valueOf(deductQuantity)) : null);
                        batchRecord.setWarehouseId(batch.getWarehouseId());
                        batchRecord.setInventoryMode(batch.getInventoryMode());
                        batchRecord.setBarcode(batch.getBarcode());
                        batchRecord.setActualGoldWeight(batch.getActualGoldWeight());
                        batchRecord.setActualSilverWeight(batch.getActualSilverWeight());
                        batchRecord.setLaborCost(batch.getLaborCost());
                        batchRecord.setCertificateNo(batch.getCertificateNo());
                        batchRecord.setPurPrice(batch.getPurPrice());
                        batchRecord.setCreateTime(LocalDateTime.now());
                        batchRecord.setCreateBy(userName);
                        orderStockingItemBatchService.save(batchRecord);
                    }

                }
            }
            // 减少总库存（原子条件扣减 + 同事务写流水，失败即回滚整笔出库）
            applyStockOut(inventoryList.get(0).getId(), null, request.getOutQty().intValue(), outItem, userName);
        }



        // 更新自己的状态
        ErpStockOutItem outItemUpdate = new ErpStockOutItem();
        outItemUpdate.setId(outItem.getId());
        outItemUpdate.setStatus(2);
        outItemUpdate.setCompleteTime(LocalDateTime.now());
        outItemUpdate.setOutQuantity(outItem.getOutQuantity()+ request.getOutQty());
        outItemUpdate.setUpdateBy(userName);
        outItemUpdate.setUpdateTime(LocalDateTime.now());
        outItemService.updateById(outItemUpdate);

        // 更新主表单数据
        ErpStockOut erpStockOut = outMapper.selectById(outItem.getEntryId());
        if(erpStockOut.getOutTotal()==null) erpStockOut.setOutTotal(0);
        // 查询入库表单是否入库完成
        List<ErpStockOutItem> itemList = outItemService.list(new LambdaQueryWrapper<ErpStockOutItem>().eq(ErpStockOutItem::getEntryId,outItem.getEntryId()).ne(ErpStockOutItem::getStatus, 2));
        ErpStockOut sUpdate = new ErpStockOut();
        if (itemList.isEmpty()) {
            // 全部入库完成了
            sUpdate.setStatus(2);
            sUpdate.setCompleteTime(LocalDateTime.now());
        } else {
            // 部分入库
            sUpdate.setStatus(1);
        }

        sUpdate.setId(outItem.getEntryId());
        sUpdate.setOperatorId(userId.toString());
        sUpdate.setOperatorName(userName);
        sUpdate.setOutTime(LocalDateTime.now());
        sUpdate.setOutTotal(erpStockOut.getOutTotal()+request.getOutQty().intValue());
        sUpdate.setUpdateBy(userName);
        sUpdate.setUpdateTime(LocalDateTime.now());
        outMapper.updateById(sUpdate);

        return ResultVo.success(outItem.getEntryId());
    }

    /**
     * 出库记库存：原子扣减 + 同事务写流水。
     *
     * <p>旧实现丢弃了 deductStock 的返回值，库存不足时业务照样往下走，
     * 造成"出库成功但账面没扣"的账实不符。这里改为失败即抛异常回滚整笔事务。</p>
     *
     * @param inventoryId 库存记录ID
     * @param batch       批次（可为 null，表示按 SKU+仓库整体出库）
     * @param qty         出库数量
     * @param outItem     出库明细（提供业务单号、商户、累计出库量）
     * @param userName    操作人
     */
    private void applyStockOut(Long inventoryId, OGoodsInventoryBatch batch, Integer qty,
                               ErpStockOutItem outItem, String userName) {
        if (inventoryId == null || qty == null || qty <= 0) {
            return;
        }
        InventoryChangeCommand cmd = InventoryChangeCommand.of(inventoryId, qty,
                InventoryChangeCommand.TYPE_OUT);
        if (batch != null) {
            cmd.setBatchId(batch.getId());
            cmd.setBatchNum(batch.getBatchNum());
        }
        cmd.setBizType(outBizType(outItem.getType()));
        cmd.setBizId(outItem.getEntryId());
        cmd.setBizNum(outItem.getSourceOrderNum());
        cmd.setBizItemId(outItem.getId());
        cmd.setMerchantId(outItem.getMerchantId());
        cmd.setWarehouseId(outItem.getWarehouseId());
        cmd.setOperator(userName);
        int accumulated = (outItem.getOutQuantity() == null ? 0 : outItem.getOutQuantity()) + qty;
        cmd.setIdempotencyKey("OUT_ITEM:" + outItem.getId() + ":" + accumulated);
        cmd.setRemark("出库明细 " + outItem.getId());
        if (!oGoodsInventoryService.changeStock(cmd)) {
            throw new ServiceException("总库存不足，出库失败");
        }
    }

    /**
     * 出库类型 → 流水业务类型映射。
     */
    private int outBizType(Integer outType) {
        if (outType == null) {
            return InventoryChangeCommand.BIZ_OTHER;
        }
        return switch (outType) {
            case 1 -> InventoryChangeCommand.BIZ_ORDER_OUT;
            case 2 -> InventoryChangeCommand.BIZ_PURCHASE_RETURN;
            case 3 -> InventoryChangeCommand.BIZ_STOCKTAKING;
            case 4 -> InventoryChangeCommand.BIZ_DAMAGE_OUT;
            default -> InventoryChangeCommand.BIZ_OTHER;
        };
    }

}




