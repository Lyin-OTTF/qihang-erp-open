package cn.qihangerp.agent.policy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具策略注册表。
 *
 * <p>启动时扫描所有 Spring Bean 中带 @Tool 注解的方法，提取 @ToolPolicy 策略信息，
 * 注册到内存表中。随后从 agent_tool_policy 表加载DB配置，DB策略优先于注解策略。
 * 提供按工具名查询策略、运行时刷新和动态修改的能力。</p>
 *
 * @author 回溯开发-搁置项攻关
 * @since 2026-10-12
 */
@Slf4j
@Component
public class PolicyRegistry {

    private final ApplicationContext applicationContext;
    private final AgentToolPolicyMapper policyMapper;

    /** toolName -> ToolPolicy 元数据 */
    private final Map<String, ToolPolicyMetadata> registry = new ConcurrentHashMap<>();

    public PolicyRegistry(ApplicationContext applicationContext, AgentToolPolicyMapper policyMapper) {
        this.applicationContext = applicationContext;
        this.policyMapper = policyMapper;
    }

    @PostConstruct
    public void init() {
        scanAndRegister();
        loadFromDb();
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
                // 用 AopUtils.getTargetClass 解析 CGLIB/JDK 代理后的真实类，
                // 否则 getDeclaredMethods 看不到代理类上的 @Tool/@ToolPolicy 注解
                Class<?> clazz = AopUtils.getTargetClass(bean);
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
     * 从DB加载策略配置，覆盖注解策略（DB优先）。
     * merchant_id 为 NULL 的全局策略会覆盖对应工具的注解默认值。
     */
    private void loadFromDb() {
        if (policyMapper == null) {
            log.warn("AgentToolPolicyMapper 未注入，跳过DB策略加载");
            return;
        }
        try {
            List<AgentToolPolicy> dbPolicies = policyMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentToolPolicy>()
                            .eq(AgentToolPolicy::getEnabled, true)
                            .isNull(AgentToolPolicy::getMerchantId)); // 先加载全局策略
            int overridden = 0;
            for (AgentToolPolicy db : dbPolicies) {
                AutonomyLevel level;
                try {
                    level = AutonomyLevel.valueOf(db.getAutonomyLevel());
                } catch (IllegalArgumentException e) {
                    log.warn("DB策略自治等级无效，跳过: tool={}, level={}", db.getToolName(), db.getAutonomyLevel());
                    continue;
                }
                ToolPolicyMetadata metadata = new ToolPolicyMetadata(
                        db.getToolName(), level,
                        db.getCategory() != null ? db.getCategory() : "DB配置",
                        db.getDescription() != null ? db.getDescription() : "",
                        level.allowsWrite(),
                        level == AutonomyLevel.ESCALATE,
                        db.getRequiresTenantIsolation() != null ? db.getRequiresTenantIsolation() : true,
                        db.getAllowConcurrent() != null ? db.getAllowConcurrent() : true,
                        db.getRequiresAudit() != null ? db.getRequiresAudit() : true,
                        db.getMaxBatchSize() != null ? db.getMaxBatchSize() : 0,
                        db.getTimeoutSeconds() != null ? db.getTimeoutSeconds() : 0
                );
                registry.put(db.getToolName(), metadata);
                overridden++;
            }
            log.info("PolicyRegistry DB策略加载完成: {} 条全局策略已覆盖注解默认值", overridden);
        } catch (Exception e) {
            // 表不存在或查询失败时降级为纯注解策略，不阻断启动
            log.warn("PolicyRegistry 从DB加载策略失败（降级为注解策略）: {}", e.getMessage());
        }
    }

    /**
     * 运行时刷新：重新从DB加载策略（不重新扫描注解）。
     */
    public void refreshFromDb() {
        loadFromDb();
    }

    /**
     * 更新或插入一条DB策略，并立即刷新内存。
     *
     * <p>synchronized 保证"查不存在则插入"的原子性。
     * MySQL 唯一索引 uk_tool_merchant(tool_name, merchant_id) 对 merchant_id IS NULL
     * 不约束（唯一索引允许多个NULL），并发调用可能插出多行导致后续 selectOne 抛异常。
     * 此方法仅管理员改策略时调用，频率极低，synchronized 性能影响可忽略。</p>
     *
     * @param toolName       工具名
     * @param autonomyLevel  自治等级
     * @param category       分类（可为null）
     * @param description    描述（可为null）
     * @return 更新后的策略元数据
     */
    public synchronized ToolPolicyMetadata updatePolicy(String toolName, AutonomyLevel autonomyLevel,
                                           String category, String description) {
        AgentToolPolicy existing = policyMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentToolPolicy>()
                        .eq(AgentToolPolicy::getToolName, toolName)
                        .isNull(AgentToolPolicy::getMerchantId));
        if (existing == null) {
            existing = AgentToolPolicy.builder()
                    .toolName(toolName)
                    .merchantId(null)
                    .autonomyLevel(autonomyLevel.name())
                    .category(category)
                    .description(description)
                    .requiresTenantIsolation(true)
                    .allowConcurrent(true)
                    .requiresAudit(true)
                    .maxBatchSize(0)
                    .timeoutSeconds(0)
                    .enabled(true)
                    .build();
            policyMapper.insert(existing);
        } else {
            existing.setAutonomyLevel(autonomyLevel.name());
            if (category != null) existing.setCategory(category);
            if (description != null) existing.setDescription(description);
            policyMapper.updateById(existing);
        }
        loadFromDb();
        return registry.get(toolName);
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
