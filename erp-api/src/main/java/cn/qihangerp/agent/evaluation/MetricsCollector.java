package cn.qihangerp.agent.evaluation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 指标收集器
 *
 * 收集AI Tool调用的各项指标，包括：
 * - 成功率
 * - 响应时间（平均、P50、P95、P99）
 * - 调用次数统计
 * - 错误类型统计
 *
 * 支持Prometheus/Grafana监控集成
 *
 * @author AI Code Generator
 * @since 2026-10-11
 */
@Slf4j
@Component
public class MetricsCollector {

    // Tool级别的指标统计
    private final ConcurrentHashMap<String, ToolMetrics> toolMetricsMap = new ConcurrentHashMap<>();

    // 全局统计
    private final GlobalMetrics globalMetrics = new GlobalMetrics();

    /** Micrometer 注册表（可选）：存在 actuator/prometheus 时把指标导出到 /actuator/prometheus */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /** 无参构造：单元测试/无 Spring 环境下使用（不做 Micrometer 导出） */
    public MetricsCollector() {
        this.meterRegistryProvider = null;
    }

    @Autowired
    public MetricsCollector(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
    }

    /**
     * 记录Tool调用成功
     *
     * @param toolName     Tool名称（如"OrderTools.getOrderList"）
     * @param category     Tool分类（如"订单查询"）
     * @param durationMs   执行耗时（毫秒）
     * @param merchantId   租户ID
     */
    public void recordSuccess(String toolName, String category, long durationMs, Long merchantId) {
        // 更新Tool级别指标
        ToolMetrics metrics = toolMetricsMap.computeIfAbsent(toolName, k -> new ToolMetrics(toolName, category));
        metrics.recordSuccess(durationMs);

        // 更新全局指标
        globalMetrics.recordSuccess(durationMs);

        recordMicrometer(toolName, category, durationMs, true, null);
        log.debug("记录成功调用: Tool={}, Duration={}ms, MerchantId={}", toolName, durationMs, merchantId);
    }

    /**
     * 记录Tool调用失败
     *
     * @param toolName      Tool名称
     * @param category      Tool分类
     * @param durationMs    执行耗时（毫秒）
     * @param errorType     错误类型（如"租户隔离检查失败"、"并发冲突"）
     * @param errorMessage  错误消息
     * @param merchantId    租户ID
     */
    public void recordFailure(String toolName, String category, long durationMs,
                              String errorType, String errorMessage, Long merchantId) {
        // 更新Tool级别指标
        ToolMetrics metrics = toolMetricsMap.computeIfAbsent(toolName, k -> new ToolMetrics(toolName, category));
        metrics.recordFailure(durationMs, errorType);

        // 更新全局指标
        globalMetrics.recordFailure(durationMs, errorType);

        recordMicrometer(toolName, category, durationMs, false, errorType);
        log.warn("记录失败调用: Tool={}, ErrorType={}, Duration={}ms, MerchantId={}",
                toolName, errorType, durationMs, merchantId);
    }

    /**
     * 同步写入 Micrometer（counter + timer，timer 带 P50/P95/P99 分位）。
     * Micrometer 未装配或记录失败时静默降级，不影响内部统计。
     */
    private void recordMicrometer(String toolName, String category, long durationMs,
                                  boolean success, String errorType) {
        if (meterRegistryProvider == null) {
            return;
        }
        meterRegistryProvider.ifAvailable(registry -> {
            try {
                Counter.builder("agent.tool.calls")
                        .description("AI Tool 调用次数")
                        .tag("tool", toolName)
                        .tag("category", category != null ? category : "unknown")
                        .tag("result", success ? "success" : "failure")
                        .register(registry)
                        .increment();

                Timer.builder("agent.tool.duration")
                        .description("AI Tool 执行耗时")
                        .tag("tool", toolName)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry)
                        .record(Duration.ofMillis(Math.max(durationMs, 0)));

                if (!success && errorType != null) {
                    Counter.builder("agent.tool.errors")
                            .description("AI Tool 失败次数（按错误类型）")
                            .tag("tool", toolName)
                            .tag("error", errorType)
                            .register(registry)
                            .increment();
                }
            } catch (Exception e) {
                log.debug("Micrometer指标记录失败: {}", e.getMessage());
            }
        });
    }

    /**
     * 获取Tool的指标统计
     *
     * @param toolName Tool名称
     * @return 指标统计，如果Tool未被调用过返回null
     */
    public ToolMetrics getToolMetrics(String toolName) {
        return toolMetricsMap.get(toolName);
    }

    /**
     * 获取全局指标统计
     *
     * @return 全局指标
     */
    public GlobalMetrics getGlobalMetrics() {
        return globalMetrics;
    }

    /**
     * 获取所有Tool的指标统计
     *
     * @return Tool名称 -> 指标统计的Map
     */
    public ConcurrentHashMap<String, ToolMetrics> getAllToolMetrics() {
        return new ConcurrentHashMap<>(toolMetricsMap);
    }

    /**
     * 重置所有指标（用于测试或定期重置）
     */
    public void resetAll() {
        toolMetricsMap.clear();
        globalMetrics.reset();
        log.info("所有指标已重置");
    }

    /**
     * Tool级别的指标
     */
    @Data
    public static class ToolMetrics {
        private final String toolName;
        private final String category;

        // 调用次数统计
        private final LongAdder totalCalls = new LongAdder();
        private final LongAdder successCalls = new LongAdder();
        private final LongAdder failureCalls = new LongAdder();

        // 响应时间统计
        private final LongAdder totalDurationMs = new LongAdder();
        private final AtomicLong minDurationMs = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxDurationMs = new AtomicLong(0);

        // 分位计算：有界滑动窗口保存最近 N 次耗时（内存友好，超过则丢弃最旧的）
        private static final int PERCENTILE_WINDOW_SIZE = 1000;
        private final ConcurrentLinkedDeque<Long> durationWindow = new ConcurrentLinkedDeque<>();

        // 错误类型统计
        private final ConcurrentHashMap<String, LongAdder> errorTypeCount = new ConcurrentHashMap<>();

        // 时间戳
        private final LocalDateTime firstCallTime = LocalDateTime.now();
        private volatile LocalDateTime lastCallTime = LocalDateTime.now();

        public ToolMetrics(String toolName, String category) {
            this.toolName = toolName;
            this.category = category;
        }

        /**
         * 记录成功调用
         */
        public void recordSuccess(long durationMs) {
            totalCalls.increment();
            successCalls.increment();
            totalDurationMs.add(durationMs);
            updateMinMax(durationMs);
            addToWindow(durationMs);
            lastCallTime = LocalDateTime.now();
        }

        /**
         * 记录失败调用
         */
        public void recordFailure(long durationMs, String errorType) {
            totalCalls.increment();
            failureCalls.increment();
            totalDurationMs.add(durationMs);
            updateMinMax(durationMs);
            addToWindow(durationMs);

            // 统计错误类型
            errorTypeCount.computeIfAbsent(errorType, k -> new LongAdder()).increment();

            lastCallTime = LocalDateTime.now();
        }

        /**
         * 把耗时加入滑动窗口（超过窗口大小时丢弃最旧的）
         */
        private void addToWindow(long durationMs) {
            durationWindow.addLast(durationMs);
            while (durationWindow.size() > PERCENTILE_WINDOW_SIZE) {
                durationWindow.pollFirst();
            }
        }

        /**
         * 更新最小/最大耗时
         */
        private void updateMinMax(long durationMs) {
            minDurationMs.updateAndGet(current -> Math.min(current, durationMs));
            maxDurationMs.updateAndGet(current -> Math.max(current, durationMs));
        }

        /**
         * 计算成功率（0-100）
         */
        public double getSuccessRate() {
            long total = totalCalls.sum();
            if (total == 0) return 0.0;
            return (successCalls.sum() * 100.0) / total;
        }

        /**
         * 计算平均响应时间（毫秒）
         */
        public double getAvgDurationMs() {
            long total = totalCalls.sum();
            if (total == 0) return 0.0;
            return (double) totalDurationMs.sum() / total;
        }

        /**
         * 获取最小响应时间
         */
        public long getMinDurationMs() {
            long min = minDurationMs.get();
            return min == Long.MAX_VALUE ? 0 : min;
        }

        /**
         * 获取最大响应时间
         */
        public long getMaxDurationMs() {
            return maxDurationMs.get();
        }

        /**
         * 计算 P50 分位（中位数）
         */
        public double getP50() {
            return calculatePercentile(50);
        }

        /**
         * 计算 P95 分位
         */
        public double getP95() {
            return calculatePercentile(95);
        }

        /**
         * 计算 P99 分位
         */
        public double getP99() {
            return calculatePercentile(99);
        }

        /**
         * 通用分位计算（基于滑动窗口内的耗时数据）
         *
         * @param percentile 分位数（0-100）
         * @return 分位值，无数据时返回0
         */
        private double calculatePercentile(int percentile) {
            if (durationWindow.isEmpty()) {
                return 0.0;
            }
            List<Long> sorted = new ArrayList<>(durationWindow);
            Collections.sort(sorted);
            int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
            index = Math.max(0, Math.min(index, sorted.size() - 1));
            return sorted.get(index);
        }

        /**
         * 获取调用总次数
         */
        public long getTotalCalls() {
            return totalCalls.sum();
        }

        /**
         * 获取成功次数
         */
        public long getSuccessCalls() {
            return successCalls.sum();
        }

        /**
         * 获取失败次数
         */
        public long getFailureCalls() {
            return failureCalls.sum();
        }

        /**
         * 获取错误类型统计
         */
        public ConcurrentHashMap<String, Long> getErrorTypeCounts() {
            ConcurrentHashMap<String, Long> result = new ConcurrentHashMap<>();
            errorTypeCount.forEach((type, adder) -> result.put(type, adder.sum()));
            return result;
        }

        @Override
        public String toString() {
            return String.format(
                    "ToolMetrics{tool='%s', category='%s', totalCalls=%d, successRate=%.2f%%, avgDuration=%.2fms}",
                    toolName, category, getTotalCalls(), getSuccessRate(), getAvgDurationMs()
            );
        }
    }

    /**
     * 全局指标
     */
    @Data
    public static class GlobalMetrics {
        // 调用次数统计
        private final LongAdder totalCalls = new LongAdder();
        private final LongAdder successCalls = new LongAdder();
        private final LongAdder failureCalls = new LongAdder();

        // 响应时间统计
        private final LongAdder totalDurationMs = new LongAdder();

        // 分位计算：有界滑动窗口
        private static final int PERCENTILE_WINDOW_SIZE = 1000;
        private final ConcurrentLinkedDeque<Long> durationWindow = new ConcurrentLinkedDeque<>();

        // 错误类型统计
        private final ConcurrentHashMap<String, LongAdder> errorTypeCount = new ConcurrentHashMap<>();

        // 启动时间
        private final LocalDateTime startTime = LocalDateTime.now();

        public void recordSuccess(long durationMs) {
            totalCalls.increment();
            successCalls.increment();
            totalDurationMs.add(durationMs);
            addToWindow(durationMs);
        }

        public void recordFailure(long durationMs, String errorType) {
            totalCalls.increment();
            failureCalls.increment();
            totalDurationMs.add(durationMs);
            addToWindow(durationMs);

            errorTypeCount.computeIfAbsent(errorType, k -> new LongAdder()).increment();
        }

        private void addToWindow(long durationMs) {
            durationWindow.addLast(durationMs);
            while (durationWindow.size() > PERCENTILE_WINDOW_SIZE) {
                durationWindow.pollFirst();
            }
        }

        public double getP50() { return calculatePercentile(50); }
        public double getP95() { return calculatePercentile(95); }
        public double getP99() { return calculatePercentile(99); }

        private double calculatePercentile(int percentile) {
            if (durationWindow.isEmpty()) return 0.0;
            List<Long> sorted = new ArrayList<>(durationWindow);
            Collections.sort(sorted);
            int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
            index = Math.max(0, Math.min(index, sorted.size() - 1));
            return sorted.get(index);
        }

        public void reset() {
            totalCalls.reset();
            successCalls.reset();
            failureCalls.reset();
            totalDurationMs.reset();
            errorTypeCount.clear();
            durationWindow.clear();
        }

        public double getSuccessRate() {
            long total = totalCalls.sum();
            if (total == 0) return 0.0;
            return (successCalls.sum() * 100.0) / total;
        }

        public double getAvgDurationMs() {
            long total = totalCalls.sum();
            if (total == 0) return 0.0;
            return (double) totalDurationMs.sum() / total;
        }

        public long getTotalCalls() {
            return totalCalls.sum();
        }

        public long getSuccessCalls() {
            return successCalls.sum();
        }

        public long getFailureCalls() {
            return failureCalls.sum();
        }

        public ConcurrentHashMap<String, Long> getErrorTypeCounts() {
            ConcurrentHashMap<String, Long> result = new ConcurrentHashMap<>();
            errorTypeCount.forEach((type, adder) -> result.put(type, adder.sum()));
            return result;
        }

        @Override
        public String toString() {
            return String.format(
                    "GlobalMetrics{totalCalls=%d, successRate=%.2f%%, avgDuration=%.2fms, uptime=%s}",
                    getTotalCalls(), getSuccessRate(), getAvgDurationMs(),
                    java.time.Duration.between(startTime, LocalDateTime.now())
            );
        }
    }
}
