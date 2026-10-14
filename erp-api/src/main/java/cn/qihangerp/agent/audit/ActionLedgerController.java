package cn.qihangerp.agent.audit;

import cn.qihangerp.common.R;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ActionLedger审计日志查询接口
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@Slf4j
@Tag(name = "审计日志")
@RestController
@RequestMapping("/api/agent/audit")
@RequiredArgsConstructor
public class ActionLedgerController {

    private final ActionLedgerService actionLedgerService;
    private final cn.qihangerp.agent.tenant.TenantAuthorizer tenantAuthorizer;

    /**
     * 根据traceId查询审计日志
     */
    @Operation(summary = "根据traceId查询审计日志")
    @GetMapping("/trace/{traceId}")
    public R<List<ActionLedger>> getByTraceId(@PathVariable String traceId) {
        List<ActionLedger> list = actionLedgerService.getByTraceId(traceId);
        Long bound = tenantAuthorizer.currentTenant();
        if (bound != null) {
            list = list.stream().filter(e -> bound.equals(e.getMerchantId())).toList();
        }
        return R.ok(list);
    }

    /**
     * 根据租户ID分页查询审计日志
     */
    @Operation(summary = "根据租户ID分页查询审计日志")
    @GetMapping("/merchant/{merchantId}")
    public R<Page<ActionLedger>> getByMerchantId(
            @PathVariable Long merchantId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        Long effectiveTenant = tenantAuthorizer.requireTenant(merchantId);
        Page<ActionLedger> page = actionLedgerService.getByMerchantId(effectiveTenant, pageNum, pageSize);
        return R.ok(page);
    }

    /**
     * 根据用户ID分页查询审计日志
     */
    @Operation(summary = "根据用户ID分页查询审计日志")
    @GetMapping("/user/{userId}")
    public R<Page<ActionLedger>> getByUserId(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        Long bound = tenantAuthorizer.currentTenant();
        if (bound != null) {
            Page<ActionLedger> page = actionLedgerService.getByUserId(userId, pageNum, pageSize);
            // 仅返回本租户数据
            page.setRecords(page.getRecords().stream().filter(e -> bound.equals(e.getMerchantId())).toList());
            return R.ok(page);
        }
        Page<ActionLedger> page = actionLedgerService.getByUserId(userId, pageNum, pageSize);
        return R.ok(page);
    }

    /**
     * 查询失败的审计日志
     */
    @Operation(summary = "查询失败的审计日志")
    @GetMapping("/failed")
    public R<Page<ActionLedger>> getFailedActions(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        Long effectiveTenant = tenantAuthorizer.requireTenant(merchantId);
        Page<ActionLedger> page = actionLedgerService.getFailedActions(effectiveTenant, pageNum, pageSize);
        return R.ok(page);
    }

    /**
     * 查询需要审批的操作
     */
    @Operation(summary = "查询需要审批的操作")
    @GetMapping("/approval")
    public R<Page<ActionLedger>> getApprovalActions(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) Boolean approved,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        Long effectiveTenant = tenantAuthorizer.requireTenant(merchantId);
        Page<ActionLedger> page = actionLedgerService.getApprovalActions(
                effectiveTenant, approved, pageNum, pageSize);
        return R.ok(page);
    }

    /**
     * 统计Tool调用成功率
     */
    @Operation(summary = "统计Tool调用成功率")
    @GetMapping("/success-rate")
    public R<Double> getSuccessRate(
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) Long merchantId) {
        Long effectiveTenant = tenantAuthorizer.requireTenant(merchantId);
        double rate = actionLedgerService.calculateSuccessRate(toolName, effectiveTenant);
        return R.ok(rate);
    }
}
