package cn.qihangerp.agent.dag;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * DAG节点定义。
 *
 * <p>每个节点包含：唯一ID、显示名、依赖节点ID列表、执行函数、
 * 补偿函数（用于失败回滚）、最大重试次数。</p>
 *
 * @author 回溯开发-阶段4
 * @since 2026-10-12
 */
public class DAGNode {

    private final String id;
    private final String name;
    private final List<String> dependencies;
    private final Function<ExecutionContext, Object> executor;
    private final Function<ExecutionContext, Void> compensator;
    private final int maxRetries;

    private DAGNode(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.dependencies = builder.dependencies;
        this.executor = builder.executor;
        this.compensator = builder.compensator;
        this.maxRetries = builder.maxRetries;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public List<String> getDependencies() { return dependencies; }
    public Function<ExecutionContext, Object> getExecutor() { return executor; }
    public Function<ExecutionContext, Void> getCompensator() { return compensator; }
    public int getMaxRetries() { return maxRetries; }
    public boolean hasCompensator() { return compensator != null; }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static class Builder {
        private final String id;
        private String name;
        private List<String> dependencies = new ArrayList<>();
        private Function<ExecutionContext, Object> executor;
        private Function<ExecutionContext, Void> compensator;
        private int maxRetries = 0;

        public Builder(String id) {
            this.id = id;
            this.name = id;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder dependsOn(String... nodeIds) {
            for (String dep : nodeIds) {
                this.dependencies.add(dep);
            }
            return this;
        }

        public Builder executor(Function<ExecutionContext, Object> executor) {
            this.executor = executor;
            return this;
        }

        public Builder compensator(Function<ExecutionContext, Void> compensator) {
            this.compensator = compensator;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public DAGNode build() {
            if (executor == null) {
                throw new IllegalStateException("节点 " + id + " 必须指定executor");
            }
            return new DAGNode(this);
        }
    }
}
