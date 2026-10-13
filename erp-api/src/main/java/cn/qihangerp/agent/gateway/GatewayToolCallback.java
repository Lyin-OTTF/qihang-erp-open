package cn.qihangerp.agent.gateway;

import cn.qihangerp.agent.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;

/**
 * 网关型 ToolCallback 装饰器。
 *
 * <p>将 Spring AI 的 @Tool 方法调用统一接入 {@link ToolGateway}，使每次 AI 工具调用
 * 都经过策略校验、租户检查、并发控制、审批判断、审计与指标采集，而不是直接反射执行。</p>
 *
 * <p>ToolDefinition（含入参 JSON Schema）由 {@link GatewayToolCallbackFactory} 通过
 * {@link org.springframework.ai.tool.method.MethodToolCallbackProvider} 生成后传入，
 * 保证入参描述与模型看到的一致；真正的执行走
 * {@link ToolGateway#execute(Object, String, ToolExecutionContext, Object...)}。</p>
 *
 * <p>上下文透传：从 {@link ToolContext} 中读取 merchantId/userId/traceId/sessionId/approved，
 * 在同步调用工具方法前设置 {@link TenantContext}，使工具内可读取当前租户；调用结束后清理。</p>
 *
 * @author 回溯开发-阶段1
 * @since 2026-10-12
 */
@Slf4j
public class GatewayToolCallback implements ToolCallback {

    private final Object toolBean;
    private final Method toolMethod;
    private final ToolDefinition toolDefinition;
    private final ToolGateway toolGateway;
    private final ObjectMapper objectMapper;

    public GatewayToolCallback(Object toolBean, Method toolMethod, ToolDefinition toolDefinition,
                               ToolGateway toolGateway, ObjectMapper objectMapper) {
        this.toolBean = toolBean;
        this.toolMethod = toolMethod;
        this.toolDefinition = toolDefinition;
        this.toolGateway = toolGateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, new ToolContext(Map.of()));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Map<String, Object> ctx = toolContext.getContext();
        Long merchantId = toLong(ctx.get("merchantId"));
        Long userId = toLong(ctx.get("userId"));
        String traceId = (String) ctx.get("traceId");
        String sessionId = (String) ctx.get("sessionId");
        boolean approved = Boolean.TRUE.equals(ctx.get("approved"));
        String clientIp = (String) ctx.get("clientIp");
        String source = (String) ctx.get("source");

        // 在同步执行期内设置租户上下文，供工具方法读取；finally 中清理，避免线程复用污染
        boolean tenantSet = false;
        if (merchantId != null) {
            TenantContext.setTenantId(merchantId);
            tenantSet = true;
        }
        try {
            ToolExecutionContext execCtx = new ToolExecutionContext(merchantId, userId, traceId);
            execCtx.setSessionId(sessionId);
            execCtx.setApproved(approved);
            if (clientIp != null) {
                execCtx.setClientIp(clientIp);
            }
            if (source != null) {
                execCtx.setSource(source);
            }

            Object[] args = parseArgs(toolInput, toolMethod);
            Object result = toolGateway.execute(toolBean, toolMethod.getName(), execCtx, args);
            return serializeResult(result);
        } catch (ToolGateway.ToolExecutionException e) {
            // ToolGateway 内部已记录失败审计，此处把错误信息返回给模型，便于其自我修正
            log.warn("AI工具[{}]被网关拒绝或执行失败: {}", toolMethod.getName(), e.getMessage());
            return "工具执行失败: " + e.getMessage();
        } catch (Exception e) {
            log.error("AI工具[{}]执行发生未预期异常", toolMethod.getName(), e);
            return "工具执行异常: " + e.getMessage();
        } finally {
            if (tenantSet) {
                TenantContext.clear();
            }
        }
    }

    /**
     * 将模型传入的 JSON 入参解析为方法参数数组。
     * 参数名依赖编译时 -parameters 标志（项目根 pom 已开启 maven.compiler.parameters=true），
     * 与 MethodToolCallback 生成 schema 时使用的参数名一致。
     */
    private Object[] parseArgs(String toolInput, Method method) throws Exception {
        Parameter[] params = method.getParameters();
        if (params.length == 0) {
            return new Object[0];
        }
        Object[] args = new Object[params.length];
        if (toolInput == null || toolInput.isBlank()) {
            return args;
        }
        Map<String, Object> inputMap = objectMapper.readValue(toolInput,
                new TypeReference<Map<String, Object>>() {});
        for (int i = 0; i < params.length; i++) {
            String name = params[i].getName();
            Object raw = inputMap.get(name);
            if (raw != null) {
                JavaType javaType = objectMapper.getTypeFactory()
                        .constructType(params[i].getParameterizedType());
                args[i] = objectMapper.convertValue(raw, javaType);
            }
        }
        return args;
    }

    /**
     * 工具返回值序列化为字符串。现有工具均返回 String（Markdown 表格/文本），直接返回；
     * 非 String 返回值用 Jackson 序列化为 JSON。
     */
    private String serializeResult(Object result) throws Exception {
        if (result == null) {
            return "";
        }
        if (result instanceof String s) {
            return s;
        }
        return objectMapper.writeValueAsString(result);
    }

    private static Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
