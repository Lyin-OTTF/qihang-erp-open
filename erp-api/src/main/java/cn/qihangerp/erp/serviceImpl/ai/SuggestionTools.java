package cn.qihangerp.erp.serviceImpl.ai;

import cn.qihangerp.agent.policy.AutonomyLevel;
import cn.qihangerp.agent.policy.ToolPolicy;
import cn.qihangerp.agent.tenant.TenantContext;
import cn.qihangerp.model.entity.OGoodsInventory;
import cn.qihangerp.service.OGoodsInventoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 建议生成工具（SUGGEST 自治等级）。
 *
 * <p>与 OBSERVE（纯数据查询）不同，SUGGEST 工具对查询结果进行分析，
 * 生成可操作的建议方案，但不执行任何写操作。
 * 网关层面 allowsWrite()=false 已确保不会触发写操作。</p>
 *
 * @author 回溯开发-第二类补全
 * @since 2026-10-14
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuggestionTools {

    private final OGoodsInventoryService inventoryService;

    /**
     * 库存补货建议：查询可用库存低于阈值的SKU，计算建议补货量。
     *
     * <p>建议补货量 = max(安全库存 - 当前可用, 0)，安全库存默认取阈值的2倍。
     * 仅生成建议文本，不创建采购单、不修改库存。</p>
     */
    @Tool(description = "生成库存补货建议。查询可用库存低于指定阈值的SKU，分析并给出建议补货数量和优先级。仅生成建议，不执行采购或库存修改。")
    @ToolPolicy(level = AutonomyLevel.SUGGEST, category = "库存建议",
            description = "库存补货建议分析", requiresTenantIsolation = true)
    public String suggestReplenishment(
            @ToolParam(description = "可用库存阈值，低于此值的SKU纳入建议，默认10") Integer threshold,
            @ToolParam(description = "返回的最大建议条数，默认20") Integer limit) {

        if (threshold == null || threshold <= 0) threshold = 10;
        if (limit == null || limit <= 0) limit = 20;
        int safetyStock = threshold * 2;

        Long merchantId = TenantContext.getTenantId();
        LambdaQueryWrapper<OGoodsInventory> wrapper = new LambdaQueryWrapper<OGoodsInventory>()
                .lt(OGoodsInventory::getAvailableQuantity, threshold)
                .orderByAsc(OGoodsInventory::getAvailableQuantity)
                .last("LIMIT " + limit);
        if (merchantId != null) {
            wrapper.eq(OGoodsInventory::getMerchantId, merchantId);
        }

        List<OGoodsInventory> lowStock = inventoryService.list(wrapper);
        if (lowStock == null || lowStock.isEmpty()) {
            return String.format("【补货建议】当前没有可用库存低于%d的SKU，库存状况良好。", threshold);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【补货建议】共发现 %d 个SKU可用库存低于阈值(%d)，建议补货：%n", lowStock.size(), threshold));
        int urgentCount = 0;
        for (OGoodsInventory inv : lowStock) {
            int available = inv.getAvailableQuantity() != null ? inv.getAvailableQuantity() : 0;
            int suggestQty = Math.max(safetyStock - available, 0);
            String priority = available <= threshold / 3 ? "紧急" : (available <= threshold * 2 / 3 ? "较高" : "一般");
            if ("紧急".equals(priority)) urgentCount++;
            sb.append(String.format("- SKU[%s] 可用:%d → 建议补货:%d (优先级:%s)%n",
                    inv.getSkuCode() != null ? inv.getSkuCode() : "ID:" + inv.getId(),
                    available, suggestQty, priority));
        }
        sb.append(String.format("%n其中紧急补货 %d 项，建议优先处理。本建议仅供参考，实际采购量请结合销售预测和在途库存调整。", urgentCount));
        return sb.toString();
    }
}
