package cn.qihangerp.agent.evaluation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MetricsCollector 单元测试。
 *
 * <p>验证指标收集的计数、成功率、平均耗时、错误类型统计、重置等逻辑正确性。</p>
 *
 * @author 回溯开发-阶段2
 * @since 2026-10-12
 */
class MetricsCollectorTest {

    private MetricsCollector metricsCollector;

    @BeforeEach
    void setUp() {
        metricsCollector = new MetricsCollector();
    }

    @Test
    void recordSuccess_incrementsCounters() {
        metricsCollector.recordSuccess("OrderTools.getOrderList", "订单查询", 50, 1001L);
        metricsCollector.recordSuccess("OrderTools.getOrderList", "订单查询", 100, 1001L);

        MetricsCollector.ToolMetrics metrics = metricsCollector.getToolMetrics("OrderTools.getOrderList");
        assertNotNull(metrics);
        assertEquals(2, metrics.getTotalCalls());
        assertEquals(2, metrics.getSuccessCalls());
        assertEquals(0, metrics.getFailureCalls());
        assertEquals(75.0, metrics.getAvgDurationMs(), 0.01, "平均耗时应为(50+100)/2=75");
        assertEquals(100.0, metrics.getSuccessRate(), 0.01, "成功率应为100%");
    }

    @Test
    void recordFailure_incrementsFailureCounters() {
        metricsCollector.recordFailure("GoodsTools.searchSku", "商品查询", 200,
                "NullPointerException", "sku is null", 1001L);

        MetricsCollector.ToolMetrics metrics = metricsCollector.getToolMetrics("GoodsTools.searchSku");
        assertNotNull(metrics);
        assertEquals(1, metrics.getTotalCalls());
        assertEquals(0, metrics.getSuccessCalls());
        assertEquals(1, metrics.getFailureCalls());
        assertEquals(0.0, metrics.getSuccessRate(), 0.01, "成功率应为0%");

        ConcurrentHashMap<String, Long> errorTypes = metrics.getErrorTypeCounts();
        assertEquals(1, errorTypes.get("NullPointerException"));
    }

    @Test
    void mixedSuccessAndFailure_calculatesCorrectRate() {
        // 7次成功，3次失败 → 成功率70%
        for (int i = 0; i < 7; i++) {
            metricsCollector.recordSuccess("TestTool.query", "测试", 10, 1L);
        }
        for (int i = 0; i < 3; i++) {
            metricsCollector.recordFailure("TestTool.query", "测试", 20, "TimeoutException", "timeout", 1L);
        }

        MetricsCollector.ToolMetrics metrics = metricsCollector.getToolMetrics("TestTool.query");
        assertEquals(10, metrics.getTotalCalls());
        assertEquals(7, metrics.getSuccessCalls());
        assertEquals(3, metrics.getFailureCalls());
        assertEquals(70.0, metrics.getSuccessRate(), 0.01, "成功率应为70%");
        // 平均耗时 = (7*10 + 3*20) / 10 = 13
        assertEquals(13.0, metrics.getAvgDurationMs(), 0.01, "平均耗时应为13ms");
    }

    @Test
    void globalMetrics_aggregatesAllTools() {
        metricsCollector.recordSuccess("ToolA.doA", "A类", 10, 1L);
        metricsCollector.recordSuccess("ToolB.doB", "B类", 20, 1L);
        metricsCollector.recordFailure("ToolA.doA", "A类", 30, "Error", "msg", 1L);

        MetricsCollector.GlobalMetrics global = metricsCollector.getGlobalMetrics();
        assertEquals(3, global.getTotalCalls());
        assertEquals(2, global.getSuccessCalls());
        assertEquals(1, global.getFailureCalls());
        assertEquals(66.67, global.getSuccessRate(), 0.01, "全局成功率应为2/3=66.67%");
        // 平均耗时 = (10+20+30)/3 = 20
        assertEquals(20.0, global.getAvgDurationMs(), 0.01, "全局平均耗时应为20ms");
    }

    @Test
    void globalErrorTypes_aggregatesAcrossTools() {
        metricsCollector.recordFailure("ToolA", "A", 10, "TimeoutException", "t", 1L);
        metricsCollector.recordFailure("ToolB", "B", 10, "TimeoutException", "t", 1L);
        metricsCollector.recordFailure("ToolC", "C", 10, "NullPointerException", "n", 1L);

        MetricsCollector.GlobalMetrics global = metricsCollector.getGlobalMetrics();
        ConcurrentHashMap<String, Long> errorTypes = global.getErrorTypeCounts();
        assertEquals(2, errorTypes.get("TimeoutException"));
        assertEquals(1, errorTypes.get("NullPointerException"));
    }

    @Test
    void minMaxDuration_trackedCorrectly() {
        metricsCollector.recordSuccess("TestTool", "测试", 5, 1L);
        metricsCollector.recordSuccess("TestTool", "测试", 500, 1L);
        metricsCollector.recordSuccess("TestTool", "测试", 200, 1L);

        MetricsCollector.ToolMetrics metrics = metricsCollector.getToolMetrics("TestTool");
        assertEquals(5, metrics.getMinDurationMs());
        assertEquals(500, metrics.getMaxDurationMs());
    }

    @Test
    void getToolMetrics_returnsNullForUnknownTool() {
        assertNull(metricsCollector.getToolMetrics("NonExistentTool"));
    }

    @Test
    void getAllToolMetrics_returnsAllRegisteredTools() {
        metricsCollector.recordSuccess("ToolA", "A", 10, 1L);
        metricsCollector.recordSuccess("ToolB", "B", 10, 1L);

        ConcurrentHashMap<String, MetricsCollector.ToolMetrics> all = metricsCollector.getAllToolMetrics();
        assertEquals(2, all.size());
        assertTrue(all.containsKey("ToolA"));
        assertTrue(all.containsKey("ToolB"));
    }

    @Test
    void resetAll_clearsAllMetrics() {
        metricsCollector.recordSuccess("ToolA", "A", 10, 1L);
        metricsCollector.recordFailure("ToolB", "B", 20, "Error", "msg", 1L);

        metricsCollector.resetAll();

        assertNull(metricsCollector.getToolMetrics("ToolA"));
        assertNull(metricsCollector.getToolMetrics("ToolB"));
        MetricsCollector.GlobalMetrics global = metricsCollector.getGlobalMetrics();
        assertEquals(0, global.getTotalCalls());
        assertEquals(0.0, global.getSuccessRate(), 0.01);
    }

    @Test
    void emptyMetrics_successRateIsZero() {
        MetricsCollector.ToolMetrics metrics = new MetricsCollector.ToolMetrics("Empty", "测试");
        assertEquals(0, metrics.getTotalCalls());
        assertEquals(0.0, metrics.getSuccessRate(), 0.01);
        assertEquals(0.0, metrics.getAvgDurationMs(), 0.01);
        assertEquals(0, metrics.getMinDurationMs(), "空指标min应为0");
    }

    @Test
    void concurrentRecords_areThreadSafe() throws InterruptedException {
        int threadCount = 10;
        int perThread = 100;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < perThread; j++) {
                    metricsCollector.recordSuccess("ConcurrentTool", "并发", 1, 1L);
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        MetricsCollector.ToolMetrics metrics = metricsCollector.getToolMetrics("ConcurrentTool");
        assertEquals(threadCount * perThread, metrics.getTotalCalls(),
                "并发10线程×100次应总计1000次");
        assertEquals(1000, metrics.getSuccessCalls());
    }
}
