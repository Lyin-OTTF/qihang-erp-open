package cn.qihangerp.agent.evaluation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 MetricsCollector 会把工具调用同步写入 Micrometer（counter + timer）。
 */
class MetricsCollectorMicrometerTest {

    /** 极简 ObjectProvider：只支持 ifAvailable/getObject，够本测试使用 */
    private static ObjectProvider<MeterRegistry> providerOf(MeterRegistry registry) {
        return new ObjectProvider<>() {
            @Override
            public MeterRegistry getObject() {
                return registry;
            }

            @Override
            public MeterRegistry getObject(Object... args) {
                return registry;
            }

            @Override
            public MeterRegistry getIfAvailable() {
                return registry;
            }

            @Override
            public MeterRegistry getIfUnique() {
                return registry;
            }

            @Override
            public void ifAvailable(Consumer<MeterRegistry> dependencyConsumer) {
                dependencyConsumer.accept(registry);
            }
        };
    }

    @Test
    void writesToolCallAndDurationToMeterRegistry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsCollector collector = new MetricsCollector(providerOf(registry));

        collector.recordSuccess("OrderTools.getOrderList", "订单查询", 12, 1L);
        collector.recordFailure("OrderActionTools.cancelOrder", "订单操作", 30,
                "非法状态迁移", "订单已完成", 1L);

        double successCalls = registry.get("agent.tool.calls")
                .tags("tool", "OrderTools.getOrderList", "category", "订单查询", "result", "success")
                .counter().count();
        double failureCalls = registry.get("agent.tool.calls")
                .tags("tool", "OrderActionTools.cancelOrder", "category", "订单操作", "result", "failure")
                .counter().count();
        double errorCalls = registry.get("agent.tool.errors")
                .tags("tool", "OrderActionTools.cancelOrder", "error", "非法状态迁移")
                .counter().count();
        long successDurationCount = registry.get("agent.tool.duration")
                .tag("tool", "OrderTools.getOrderList")
                .timer().count();

        assertEquals(1.0, successCalls);
        assertEquals(1.0, failureCalls);
        assertEquals(1.0, errorCalls);
        assertEquals(1L, successDurationCount);
    }
}
