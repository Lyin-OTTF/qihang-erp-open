package cn.qihangerp.agent.controller;

import cn.qihangerp.agent.trace.TraceEntry;
import cn.qihangerp.agent.trace.TraceService;
import cn.qihangerp.agent.tenant.TenantAuthorizer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent Trace查询控制器。
 * 列表按登录租户过滤；按traceId查询时，商户用户仅能看到本租户的span。
 */
@RestController
@RequestMapping("/api/agent/trace")
@RequiredArgsConstructor
public class TraceController {

    private final TraceService traceService;
    private final TenantAuthorizer tenantAuthorizer;

    /**
     * 按traceId查询完整调用链。
     */
    @GetMapping("/chain/{traceId}")
    public Map<String, Object> getTraceChain(@PathVariable String traceId) {
        List<TraceEntry> chain = traceService.getTraceChain(traceId);
        Long bound = tenantAuthorizer.currentTenant();
        if (bound != null) {
            // 商户用户：仅保留本租户span（防止凭traceId窥探其他租户）
            chain = chain.stream().filter(e -> bound.equals(e.getMerchantId())).toList();
        }
        Map<String, Object> result = new HashMap<>();
        result.put("traceId", traceId);
        result.put("spanCount", chain.size());
        result.put("spans", chain);
        return result;
    }

    /**
     * 按商户查询最近的trace。
     */
    @GetMapping("/recent")
    public Map<String, Object> getRecentTraces(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(defaultValue = "20") int limit) {
        Long effectiveTenant = tenantAuthorizer.requireTenant(merchantId);
        List<TraceEntry> traces = traceService.getRecentTraces(effectiveTenant, limit);
        Map<String, Object> result = new HashMap<>();
        result.put("merchantId", effectiveTenant);
        result.put("count", traces.size());
        result.put("traces", traces);
        return result;
    }

    /**
     * 查询失败的调用。
     */
    @GetMapping("/failed")
    public Map<String, Object> getFailedTraces(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(defaultValue = "20") int limit) {
        Long effectiveTenant = tenantAuthorizer.requireTenant(merchantId);
        List<TraceEntry> traces = traceService.getFailedTraces(effectiveTenant, limit);
        Map<String, Object> result = new HashMap<>();
        result.put("count", traces.size());
        result.put("traces", traces);
        return result;
    }

    /**
     * 统计指定traceId的调用次数。
     */
    @GetMapping("/count/{traceId}")
    public Map<String, Object> countTraceSpans(@PathVariable String traceId) {
        int count = traceService.countTraceSpans(traceId);
        Map<String, Object> result = new HashMap<>();
        result.put("traceId", traceId);
        result.put("spanCount", count);
        return result;
    }
}
