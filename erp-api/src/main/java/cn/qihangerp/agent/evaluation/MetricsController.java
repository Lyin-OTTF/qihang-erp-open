package cn.qihangerp.agent.evaluation;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指标暴露接口
 *
 * 提供Prometheus格式的指标数据，供Grafana可视化
 *
 * 接口列表：
 * - GET /api/agent/metrics/global - 获取全局指标
 * - GET /api/agent/metrics/tools - 获取所有Tool的指标
 * - GET /api/agent/metrics/prometheus - Prometheus格式的指标（用于Grafana）
 *
 * @author AI Code Generator
 * @since 2026-10-11
 */
@RestController
@RequestMapping("/api/agent/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final MetricsCollector metricsCollector;

    /**
     * 获取全局指标
     *
     * @return 全局指标JSON
     */
    @GetMapping("/global")
    public Map<String, Object> getGlobalMetrics() {
        MetricsCollector.GlobalMetrics global = metricsCollector.getGlobalMetrics();

        Map<String, Object> result = new HashMap<>();
        result.put("totalCalls", global.getTotalCalls());
        result.put("successCalls", global.getSuccessCalls());
        result.put("failureCalls", global.getFailureCalls());
        result.put("successRate", String.format("%.2f%%", global.getSuccessRate()));
        result.put("avgDurationMs", String.format("%.2f", global.getAvgDurationMs()));
        result.put("errorTypes", global.getErrorTypeCounts());
        result.put("startTime", global.getStartTime());

        return result;
    }

    /**
     * 获取所有Tool的指标
     *
     * @return Tool指标列表
     */
    @GetMapping("/tools")
    public Map<String, Map<String, Object>> getToolMetrics() {
        ConcurrentHashMap<String, MetricsCollector.ToolMetrics> allMetrics =
                metricsCollector.getAllToolMetrics();

        Map<String, Map<String, Object>> result = new HashMap<>();

        allMetrics.forEach((toolName, metrics) -> {
            Map<String, Object> toolData = new HashMap<>();
            toolData.put("toolName", metrics.getToolName());
            toolData.put("category", metrics.getCategory());
            toolData.put("totalCalls", metrics.getTotalCalls());
            toolData.put("successCalls", metrics.getSuccessCalls());
            toolData.put("failureCalls", metrics.getFailureCalls());
            toolData.put("successRate", String.format("%.2f%%", metrics.getSuccessRate()));
            toolData.put("avgDurationMs", String.format("%.2f", metrics.getAvgDurationMs()));
            toolData.put("minDurationMs", metrics.getMinDurationMs());
            toolData.put("maxDurationMs", metrics.getMaxDurationMs());
            toolData.put("errorTypes", metrics.getErrorTypeCounts());
            toolData.put("firstCallTime", metrics.getFirstCallTime());
            toolData.put("lastCallTime", metrics.getLastCallTime());

            result.put(toolName, toolData);
        });

        return result;
    }

    /**
     * Prometheus格式的指标
     *
     * 用于Grafana抓取
     *
     * @return Prometheus文本格式
     */
    @GetMapping(value = "/prometheus", produces = "text/plain")
    public String getPrometheusMetrics() {
        StringBuilder sb = new StringBuilder();

        // 全局指标
        MetricsCollector.GlobalMetrics global = metricsCollector.getGlobalMetrics();
        sb.append("# HELP agent_tool_calls_total Total number of tool calls\n");
        sb.append("# TYPE agent_tool_calls_total counter\n");
        sb.append("agent_tool_calls_total ").append(global.getTotalCalls()).append("\n");

        sb.append("# HELP agent_tool_calls_success Number of successful tool calls\n");
        sb.append("# TYPE agent_tool_calls_success counter\n");
        sb.append("agent_tool_calls_success ").append(global.getSuccessCalls()).append("\n");

        sb.append("# HELP agent_tool_calls_failure Number of failed tool calls\n");
        sb.append("# TYPE agent_tool_calls_failure counter\n");
        sb.append("agent_tool_calls_failure ").append(global.getFailureCalls()).append("\n");

        sb.append("# HELP agent_tool_success_rate Tool call success rate (0-100)\n");
        sb.append("# TYPE agent_tool_success_rate gauge\n");
        sb.append("agent_tool_success_rate ").append(String.format("%.2f", global.getSuccessRate())).append("\n");

        sb.append("# HELP agent_tool_avg_duration_ms Average tool execution duration in milliseconds\n");
        sb.append("# TYPE agent_tool_avg_duration_ms gauge\n");
        sb.append("agent_tool_avg_duration_ms ").append(String.format("%.2f", global.getAvgDurationMs())).append("\n");

        // Tool级别指标
        ConcurrentHashMap<String, MetricsCollector.ToolMetrics> allMetrics =
                metricsCollector.getAllToolMetrics();

        sb.append("# HELP agent_tool_calls_by_name Number of calls per tool\n");
        sb.append("# TYPE agent_tool_calls_by_name counter\n");
        allMetrics.forEach((toolName, metrics) -> {
            sb.append("agent_tool_calls_by_name{tool=\"").append(toolName)
                    .append("\",category=\"").append(metrics.getCategory())
                    .append("\"} ").append(metrics.getTotalCalls()).append("\n");
        });

        sb.append("# HELP agent_tool_success_rate_by_name Success rate per tool (0-100)\n");
        sb.append("# TYPE agent_tool_success_rate_by_name gauge\n");
        allMetrics.forEach((toolName, metrics) -> {
            sb.append("agent_tool_success_rate_by_name{tool=\"").append(toolName)
                    .append("\",category=\"").append(metrics.getCategory())
                    .append("\"} ").append(String.format("%.2f", metrics.getSuccessRate())).append("\n");
        });

        sb.append("# HELP agent_tool_avg_duration_by_name_ms Average duration per tool in milliseconds\n");
        sb.append("# TYPE agent_tool_avg_duration_by_name_ms gauge\n");
        allMetrics.forEach((toolName, metrics) -> {
            sb.append("agent_tool_avg_duration_by_name_ms{tool=\"").append(toolName)
                    .append("\",category=\"").append(metrics.getCategory())
                    .append("\"} ").append(String.format("%.2f", metrics.getAvgDurationMs())).append("\n");
        });

        // 错误类型统计
        sb.append("# HELP agent_tool_errors_by_type Number of errors by type\n");
        sb.append("# TYPE agent_tool_errors_by_type counter\n");
        global.getErrorTypeCounts().forEach((errorType, count) -> {
            sb.append("agent_tool_errors_by_type{error_type=\"").append(errorType)
                    .append("\"} ").append(count).append("\n");
        });

        return sb.toString();
    }

    /**
     * 重置所有指标（用于测试）
     *
     * 生产环境建议关闭此接口或加权限验证
     */
    @GetMapping("/reset")
    public Map<String, String> resetMetrics() {
        metricsCollector.resetAll();
        Map<String, String> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "所有指标已重置");
        return result;
    }
}
