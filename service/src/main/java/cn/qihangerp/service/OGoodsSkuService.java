package cn.qihangerp.service;

import cn.qihangerp.common.ResultVo;
import cn.qihangerp.model.entity.OGoodsSku;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
* @author TW
* @description 针对表【o_goods_sku(商品规格库存管理)】的数据库操作Service
* @createDate 2024-03-11 14:24:49
*/
public interface OGoodsSkuService extends IService<OGoodsSku> {
    List<OGoodsSku> searchGoodsSpec(String keyword);
    /** 带租户过滤的SKU搜索（回溯开发-阶段1新增） */
    List<OGoodsSku> searchGoodsSpec(String keyword, Long merchantId);
    List<OGoodsSku> selectSkuAll(Long merchantId);
    OGoodsSku getGoodsSkuByCode(String skuCode);
    OGoodsSku getGoodsSkuByOuterSkuCode(String outerSkuCode);
    ResultVo updateSku(OGoodsSku sku);
    ResultVo deleteSkuById(Long skuId);
}
