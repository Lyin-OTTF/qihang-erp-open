package cn.qihangerp.agent.gateway;

import cn.qihangerp.agent.policy.AutonomyLevel;
import cn.qihangerp.agent.policy.ToolPolicy;
import cn.qihangerp.agent.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GatewayToolCallback 单元测试。
 *
 * 验证：
 * 1. 正常执行：JSON 入参解析 → 走 ToolGateway → 返回工具结果
 * 2. 多参数不同类型解析（String + Integer）
 * 3. 租户上下文透传：回调内设置 TenantContext，工具方法可读取，调用结束后清理
 * 4. ESCALATE 未审批：网关拒绝，回调返回错误字符串而非抛出异常
 * 5. merchantId 为 null 时不崩溃（向后兼容）
 * 6. getToolDefinition 委托给 MethodToolCallback，名称正确
 *
 * @author 回溯开发-阶段1
 * @since 2026-10-12
 */
class GatewayToolCallbackTest {

    private ToolGateway toolGateway;
    private ObjectMapper objectMapper;
    private TestTool testTool;

    @BeforeEach
    void setUp() {
        toolGateway = new ToolGateway();
        objectMapper = new ObjectMapper();
        testTool = new TestTool();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private GatewayToolCallback callbackFor(String methodName, Class<?>... paramTypes) throws Exception {
        Method method = TestTool.class.getMethod(methodName, paramTypes);
        // 用 Spring AI 官方 Provider 生成 ToolDefinition（含入参 schema）
        ToolCallback[] rawCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(testTool)
                .build()
                .getToolCallbacks();
        ToolDefinition def = null;
        for (ToolCallback raw : rawCallbacks) {
            if (methodName.equals(raw.getToolDefinition().name())) {
                def = raw.getToolDefinition();
                break;
            }
        }
        assertNotNull(def, "找不到方法 [" + methodName + "] 对应的 ToolDefinition");
        return new GatewayToolCallback(testTool, method, def, toolGateway, objectMapper);
    }

    private ToolContext ctx(Object... kv) {
        Map<String, Object> map = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return new ToolContext(map);
    }

    @Test
    void normalExecution_parsesJsonArgsAndReturnsResult() throws Exception {
        GatewayToolCallback cb = callbackFor("echo", String.class, Integer.class);
        ToolContext context = ctx("merchantId", 100L, "userId", 200L, "traceId", "t-001", "sessionId", "s-001");

        String result = cb.call("{\"message\":\"hello\",\"count\":3}", context);

        assertEquals("echo:hello-3", result);
    }

    @Test
    void tenantContext_isPropagatedAndCleared() throws Exception {
        GatewayToolCallback cb = callbackFor("readTenant");
        ToolContext context = ctx("merchantId", 777L, "userId", 1L, "traceId", "t-002");

        String result = cb.call("{}", context);

        assertEquals("tenant:777", result, "工具执行期间应能读取到透传的租户ID");
        assertNull(TenantContext.getTenantId(), "回调结束后必须清理 TenantContext，防止线程复用污染");
    }

    @Test
    void escalateWithoutApproval_returnsErrorStringNotThrow() throws Exception {
        GatewayToolCallback cb = callbackFor("dangerousOp");
        ToolContext context = ctx("merchantId", 1L, "userId", 1L, "traceId", "t-003", "approved", false);

        String result = cb.call("{}", context);

        assertTrue(result.contains("失败") || result.contains("审批") || result.contains("确认"),
                "ESCALATE 未审批应返回错误信息字符串，实际: " + result);
        assertFalse(result.contains("should not execute"),
                "未审批的高风险工具不应被实际执行");
    }

    @Test
    void nullMerchantId_doesNotBreakExecution() throws Exception {
        GatewayToolCallback cb = callbackFor("echo", String.class, Integer.class);
        ToolContext context = ctx("userId", 1L, "traceId", "t-004"); // 无 merchantId

        String result = cb.call("{\"message\":\"hi\",\"count\":1}", context);

        assertEquals("echo:hi-1", result);
    }

    @Test
    void getToolDefinition_delegatesToMethodToolCallback() throws Exception {
        GatewayToolCallback cb = callbackFor("echo", String.class, Integer.class);
        ToolDefinition def = cb.getToolDefinition();

        assertNotNull(def, "ToolDefinition 不应为 null");
        assertEquals("echo", def.name(), "工具名应为方法名");
        assertNotNull(def.description(), "工具描述不应为 null");
    }

    @Test
    void blankInput_withParameters_returnsResultWithNulls() throws Exception {
        GatewayToolCallback cb = callbackFor("echo", String.class, Integer.class);
        ToolContext context = ctx("merchantId", 1L, "userId", 1L, "traceId", "t-005");

        String result = cb.call("", context);

        assertEquals("echo:null-null", result, "空入参时参数应为 null，不应崩溃");
    }

    /** 测试用工具类 */
    static class TestTool {

        @Tool(description = "回显消息和次数")
        @ToolPolicy(level = AutonomyLevel.OBSERVE, category = "测试")
        public String echo(String message, Integer count) {
            return "echo:" + message + "-" + count;
        }

        @Tool(description = "读取当前租户ID")
        @ToolPolicy(level = AutonomyLevel.OBSERVE, category = "测试")
        public String readTenant() {
            return "tenant:" + TenantContext.getTenantId();
        }

        @Tool(description = "高风险操作")
        @ToolPolicy(level = AutonomyLevel.ESCALATE, category = "测试")
        public String dangerousOp() {
            return "should not execute";
        }
    }
}
