package cn.qihangerp.agent.policy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具策略注册表。
 *
 * <p>启动时扫描所有 Spring Bean 中带 @Tool 注解的方法，提取 @ToolPolicy 策略信息，
 * 注册到内存表中。提供按工具名查询策略的能力。</p>
 *
 * <p>注意：Spring AI 的 @Tool 注解可能在不同版本中包名不同，此处用反射方式
 * 检测方法上是否存在名为 "Tool" 的注解，避免硬编码包名导致编译失败。</p>
 *
 * @author 回溯开发-搁置项攻关
 * @since 2026-10-12
 */
@Slf4j
@Component
public class PolicyRegistry {

    private final ApplicationContext applicationContext;

    /** toolName -> ToolPolicy 元数据 */
    private final Map<String, ToolPolicyMetadata> registry = new ConcurrentHashMap<>();

    public PolicyRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        scanAndRegister();
    }

    /**
     * 扫描所有 Bean，注册 @Tool 方法的策略
     */
    private void scanAndRegister() {
        int count = 0;
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            try {
                Object bean = applicationContext.getBean(beanName);
                if (bean == null) continue;
                Class<?> clazz = bean.getClass();
                // 跳过 Spring 自身的代理类和基础组件
                if (clazz.getName().startsWith("org.springframework")) continue;

                for (Method method : clazz.getDeclaredMethods()) {
                    // 检测 @Tool 注解（用注解名匹配，避免硬编码包名）
                    boolean hasTool = Arrays.stream(method.getAnnotations())
                            .anyMatch(a -> a.annotationType().getSimpleName().equals("Tool"));
                    if (!hasTool) continue;

                    ToolPolicy policy = method.getAnnotation(ToolPolicy.class);
                    String toolName = clazz.getSimpleName() + "." + method.getName();

                    ToolPolicyMetadata metadata = new ToolPolicyMetadata(
                            toolName,
                            policy != null ? policy.level() : AutonomyLevel.OBSERVE,
                            policy != null ? policy.category() : "未分类",
                            policy != null ? policy.description() : "",
                            policy != null && policy.level().allowsWrite(),
                            policy != null && policy.level() == AutonomyLevel.ESCALATE,
                            policy != null ? policy.requiresTenantIsolation() : true,
                            policy != null ? policy.allowConcurrent() : true,
                            policy != null ? policy.requiresAudit() : true,
                            policy != null ? policy.maxBatchSize() : 0,
                            policy != null ? policy.timeoutSeconds() : 0
                    );
                    registry.put(toolName, metadata);
                    count++;
                }
            } catch (Exception e) {
                // 某些 Bean 可能无法实例化，跳过
                log.debug("扫描Bean策略时跳过: {} - {}", beanName, e.getMessage());
            }
        }
        log.info("PolicyRegistry 扫描完成: 共注册 {} 个工具策略", count);
    }

    /**
     * 根据工具名查询策略
     */
    public ToolPolicyMetadata getPolicy(String toolName) {
        return registry.get(toolName);
    }

    /**
     * 获取所有已注册的工具策略
     */
    public Collection<ToolPolicyMetadata> getAllPolicies() {
        return Collections.unmodifiableCollection(registry.values());
    }

    /**
     * 获取所有写操作工具
     */
    public List<ToolPolicyMetadata> getWriteTools() {
        return registry.values().stream()
                .filter(ToolPolicyMetadata::isWrite)
                .toList();
    }

    /**
     * 获取所有需要审批的工具
     */
    public List<ToolPolicyMetadata> getApprovalRequiredTools() {
        return registry.values().stream()
                .filter(ToolPolicyMetadata::isRequiresApproval)
                .toList();
    }

    /**
     * 工具策略元数据
     */
    public static class ToolPolicyMetadata {
        private final String toolName;
        private final AutonomyLevel autonomyLevel;
        private final String category;
        private final String description;
        private final boolean write;
        private final boolean requiresApproval;
        private final boolean requiresTenantIsolation;
        private final boolean allowConcurrent;
        private final boolean requiresAudit;
        private final int maxBatchSize;
        private final int timeoutSeconds;

        public ToolPolicyMetadata(String toolName, AutonomyLevel autonomyLevel, String category,
                                  String description, boolean write, boolean requiresApproval,
                                  boolean requiresTenantIsolation, boolean allowConcurrent,
                                  boolean requiresAudit, int maxBatchSize, int timeoutSeconds) {
            this.toolName = toolName;
            this.autonomyLevel = autonomyLevel;
            this.category = category;
            this.description = description;
            this.write = write;
            this.requiresApproval = requiresApproval;
            this.requiresTenantIsolation = requiresTenantIsolation;
            this.allowConcurrent = allowConcurrent;
            this.requiresAudit = requiresAudit;
            this.maxBatchSize = maxBatchSize;
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getToolName() { return toolName; }
        public AutonomyLevel getAutonomyLevel() { return autonomyLevel; }
        public String getCategory() { return category; }
        public String getDescription() { return description; }
        public boolean isWrite() { return write; }
        public boolean isRequiresApproval() { return requiresApproval; }
        public boolean isRequiresTenantIsolation() { return requiresTenantIsolation; }
        public boolean isAllowConcurrent() { return allowConcurrent; }
        public boolean isRequiresAudit() { return requiresAudit; }
        public int getMaxBatchSize() { return maxBatchSize; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
    }
}
