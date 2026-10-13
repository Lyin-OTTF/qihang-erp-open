package cn.qihangerp.erp.serviceImpl.ai;

import cn.qihangerp.agent.policy.AutonomyLevel;
import cn.qihangerp.agent.policy.ToolPolicy;
import cn.qihangerp.agent.tenant.TenantContext;
import cn.qihangerp.model.entity.OShop;
import cn.qihangerp.service.OShopService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShopTools {

    private final OShopService shopService;

    @Tool(description = "获取所有店铺列表，返回每条记录的店铺名称、平台类型")
    @ToolPolicy(level = AutonomyLevel.OBSERVE, category = "店铺查询")
    public String getShopList() {
        LambdaQueryWrapper<OShop> wrapper = new LambdaQueryWrapper<>();
        Long merchantId = TenantContext.getTenantId();
        if (merchantId != null) {
            wrapper.eq(OShop::getMerchantId, merchantId);
        }
        var shops = shopService.list(wrapper);
        if (shops == null || shops.isEmpty()) {
            return "暂无店铺数据";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(shops.size()).append(" 个店铺：\n");
        for (var shop : shops) {
            sb.append("  - ").append(shop.getName() != null ? shop.getName() : "")
                    .append(" [平台类型:").append(shop.getType() != null ? shop.getType() : "").append("]\n");
        }
        return sb.toString();
    }
}
