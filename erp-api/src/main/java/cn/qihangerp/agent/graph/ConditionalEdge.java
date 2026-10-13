package cn.qihangerp.agent.graph;

import java.util.function.Predicate;

/**
 * 条件边（Conditional Edge）。
 *
 * <p>支持if/else路由：根据当前GraphState判断应该走哪个分支。
 * 类似LangGraph的conditional_edges。</p>
 *
 * <p>使用示例：
 * <pre>
 * ConditionalEdge edge = ConditionalEdge.builder("decision")
 *     .when(state -> state.getGlobalContext().get("result").equals("success"), "success_node")
 *     .otherwise("failure_node")
 *     .build();
 * String nextNode = edge.route(state);
 * </pre>
 * </p>
 *
 * @author 回溯开发-阶段4
 * @since 2026-10-12
 */
public class ConditionalEdge {

    private final String name;
    private final java.util.List<ConditionBranch> branches;
    private final String defaultTarget;

    private ConditionalEdge(Builder builder) {
        this.name = builder.name;
        this.branches = builder.branches;
        this.defaultTarget = builder.defaultTarget;
    }

    public String getName() { return name; }

    /**
     * 根据状态路由到下一个节点。
     *
     * @param state 当前图状态
     * @return 下一个节点ID
     */
    public String route(GraphState state) {
        for (ConditionBranch branch : branches) {
            if (branch.condition.test(state)) {
                return branch.target;
            }
        }
        return defaultTarget;
    }

    /**
     * 获取所有可能的目标节点（用于图构建验证）
     */
    public java.util.List<String> getAllTargets() {
        java.util.List<String> targets = new java.util.ArrayList<>();
        for (ConditionBranch branch : branches) {
            targets.add(branch.target);
        }
        if (defaultTarget != null) {
            targets.add(defaultTarget);
        }
        return targets;
    }

    private static class ConditionBranch {
        final Predicate<GraphState> condition;
        final String target;

        ConditionBranch(Predicate<GraphState> condition, String target) {
            this.condition = condition;
            this.target = target;
        }
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private final java.util.List<ConditionBranch> branches = new java.util.ArrayList<>();
        private String defaultTarget;

        public Builder(String name) {
            this.name = name;
        }

        /**
         * 添加条件分支：当condition为true时路由到target
         */
        public Builder when(Predicate<GraphState> condition, String target) {
            branches.add(new ConditionBranch(condition, target));
            return this;
        }

        /**
         * 默认分支（所有条件都不满足时）
         */
        public Builder otherwise(String target) {
            this.defaultTarget = target;
            return this;
        }

        public ConditionalEdge build() {
            if (branches.isEmpty() && defaultTarget == null) {
                throw new IllegalStateException("条件边 " + name + " 至少需要一个分支或默认目标");
            }
            return new ConditionalEdge(this);
        }
    }
}
