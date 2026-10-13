package cn.qihangerp.agent.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关型 ToolCallback 工厂。
 *
 * <p>扫描传入的工具 Bean 中所有标注 {@link Tool} 的方法，为每个方法生成一个
 * {@link GatewayToolCallback}，使 AI 调用统一经过 {@link ToolGateway}。</p>
 *
 * <p>ToolDefinition（含入参 JSON Schema）通过 {@link MethodToolCallbackProvider}
 * 复用 Spring AI 官方生成逻辑，保证模型看到的工具描述与入参 schema 一致。</p>
 *
 * <p>使用 {@link ClassUtils#getUserClass(Object)} 处理 CGLIB 代理场景，
 * 确保能从代理对象上找到原始类的 @Tool 方法。</p>
 *
 * @author 回溯开发-阶段1
 * @since 2026-10-12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayToolCallbackFactory {

    private final ToolGateway toolGateway;
    private final ObjectMapper objectMapper;

    /**
     * 为给定的工具 Bean 列表生成网关型 ToolCallback。
     *
     * @param toolBeans 标注了 @Tool 方法的 Spring Bean
     * @return 每个 @Tool 方法对应一个 GatewayToolCallback
     */
    public List<ToolCallback> create(Object... toolBeans) {
        List<ToolCallback> callbacks = new ArrayList<>();
        if (toolBeans == null) {
            return callbacks;
        }
        for (Object bean : toolBeans) {
            if (bean == null) {
                continue;
            }
            // 用 Spring AI 官方 Provider 生成原始回调，从中提取 ToolDefinition
            ToolCallback[] rawCallbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(bean)
                    .build()
                    .getToolCallbacks();
            Map<String, ToolDefinition> definitionMap = new HashMap<>();
            for (ToolCallback raw : rawCallbacks) {
                ToolDefinition def = raw.getToolDefinition();
                if (def != null) {
                    definitionMap.put(def.name(), def);
                }
            }

            Class<?> userClass = ClassUtils.getUserClass(bean);
            for (Method method : userClass.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Tool.class)) {
                    continue;
                }
                Tool toolAnn = method.getAnnotation(Tool.class);
                String toolName = (toolAnn.name() != null && !toolAnn.name().isEmpty())
                        ? toolAnn.name() : method.getName();
                ToolDefinition def = definitionMap.get(toolName);
                if (def == null) {
                    log.warn("未找到工具 [{}] 的 ToolDefinition，跳过注册", toolName);
                    continue;
                }
                callbacks.add(new GatewayToolCallback(bean, method, def, toolGateway, objectMapper));
                log.debug("注册网关工具回调: {}.{}", userClass.getSimpleName(), method.getName());
            }
        }
        log.info("共注册 {} 个网关型 AI 工具回调", callbacks.size());
        return callbacks;
    }
}
