# 06 DAG 与记忆篇：多步编排、失败恢复与上下文治理

> 目标：能讲清"一个售后流程为什么要用 DAG 而不是一串 if"，以及"记忆为什么会撑爆上下文"。
> 预计用时：D6 下午（约 3 小时，时间紧可只读第 1-3 节）

---

## 1. 为什么需要 DAG：串行脚本的三个死穴

售后处理流程大致是：

```
查订单 → 查售后单 → 查库存 → 校验状态 → 处理退款 → 更新备注 → 通知客户
                                              ↘ 写审计
```

如果写成串行方法：

1. **无法并行**：查订单、查售后、查库存互不依赖，却要一个个等。
2. **失败无法局部回滚**：退款改了一半失败，前面改的订单状态没人恢复。
3. **无法续跑**：进程挂了只能从头再来，重复副作用。

DAG 把流程拆成**节点 + 依赖**，换来：

- 无依赖节点并行；
- 失败按依赖传播，独立分支可取消；
- 每个成功节点带 `compensator`，失败时逆序补偿；
- 每完成一个节点存 checkpoint，挂了能从中间恢复。

---

## 2. 模型：四个类

| 类 | 职责 | 关键字段/方法 |
|----|------|---------------|
| `DAGNode` | 一个节点 | `id`、`dependencies`、`executor`、`compensator`、`maxRetries` |
| `ExecutionContext` | 一次执行的共享状态 | `nodeStatuses`、`nodeOutputs`、`errors`、`put/get` |
| `DAGScheduler` | 调度器 | `execute(nodes, context)`、线程池、重试、补偿 |
| `FailurePropagator` | 失败传播 | `shouldBeCancelled`、`areDependenciesSatisfied`、`propagateFailure` |

节点定义（`AgentWorkflowService.buildAfterSalesNodes`，节选）：

```java
nodes.add(DAGNode.builder("fetch_order")
        .executor(ctx -> { ... })
        .build());

nodes.add(DAGNode.builder("validate_transition")
        .dependsOn("fetch_order", "fetch_refund")   // 两个依赖
        .executor(ctx -> { ... })
        .build());

nodes.add(DAGNode.builder("audit_log")
        .dependsOn("fetch_inventory")                // 与主链并行
        .executor(ctx -> { ... })
        .build());
```

`audit_log` 只依赖 `fetch_inventory`，所以它和 `validate_transition` 之后的主链**可以并行**——
这就是 DAG 相对串行的实际收益。

---

## 3. 调度循环：批次屏障

`DAGScheduler.execute` 的主循环（简化）：

```java
while (!context.isAllCompleted()) {
    List<DAGNode> readyNodes = new ArrayList<>();
    for (DAGNode node : nodeMap.values()) {
        if (context.getNodeStatus(node.getId()) != PENDING) continue;

        if (failurePropagator.shouldBeCancelled(node, context)) {
            context.setNodeStatus(node.getId(), CANCELLED);
            continue;
        }
        if (failurePropagator.areDependenciesSatisfied(node, context)) {
            readyNodes.add(node);
        }
    }

    if (readyNodes.isEmpty()) {
        if (hasRunningNodes(context)) { sleepQuietly(50); continue; }
        break;
    }

    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (DAGNode node : readyNodes) {
        context.setNodeStatus(node.getId(), RUNNING);
        futures.add(CompletableFuture.runAsync(
                () -> executeNodeWithRetry(node, context, nodeMap), executor));
    }
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();  // 批次屏障
}
```

### 3.1 "批次屏障"是什么意思，代价是什么

每一轮把所有 ready 节点一起提交，然后 `allOf(...).join()` 等**这一批全部结束**才进入下一轮。

- 好处：实现简单，状态推进确定，易于保存 checkpoint。
- 代价：**长尾节点拖慢整批**。比如 A 要 10ms、B 要 2s，A 完成后本可以先跑它的下游，但要等 B。

> 面试话术：
> "这是有意的简化。真正的流水线调度要用完成队列（谁先完成谁触发下游），
> 复杂度和死锁风险都高。我的工作流节点数少、耗时不均不严重，
> 批次屏障足够；如果节点上百，我会换成事件驱动调度。"

### 3.2 失败传播

`executeNodeWithRetry` 重试 `maxRetries + 1` 次，仍失败则：

```java
context.setNodeStatus(node.getId(), FAILED);
context.setNodeError(node.getId(), e.getMessage());
failurePropagator.propagateFailure(node.getId(), nodeMap, context);
```

`propagateFailure` 沿依赖向下把所有后继标记为 `CANCELLED`（而不是 FAILED），
这样结果里能区分"自己失败的"和"被连累取消的"。

### 3.3 补偿

```java
if (!context.hasFailures()) return;
List<DAGNode> reversed = new ArrayList<>(nodeMap.values());
Collections.reverse(reversed);
for (DAGNode node : reversed) {
    if (context.getNodeStatus(node.getId()) == SUCCESS && node.hasCompensator()) {
        node.getCompensator().apply(context);
    }
}
```

**诚实说明**：这里反转的是节点声明顺序，不是严格的逆拓扑序。
对当前这条链式工作流（fetch → validate → process → memo → notify）两者等价；
如果 DAG 有交叉依赖，应该按拓扑排序的逆序执行。这是可以主动指出的改进点。

> 面试价值：能指出自己实现的边界，比硬说"完全正确"更可信。

### 3.4 一个隐蔽的坑：PENDING 假成功

如果节点依赖了一个**不存在的 id**，`areDependenciesSatisfied` 永远为 false，
`readyNodes` 为空、也没有 RUNNING 节点 → `break`。
结果里该节点仍是 PENDING，而 `hasFailures()` 是 false，`WorkflowResult.success` 可能是 true。

> 修复方向：循环结束时检查是否还有 PENDING，有则标记失败。
> 这是审查报告指出的问题，也是"成功不等于全部执行"的经典反例。

---

## 4. Checkpoint：断点续跑到底怎么实现

### 4.1 保存

```java
public synchronized int saveCheckpoint(String workflowId, String executionId, GraphState state) {
    Integer maxVersion = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(checkpoint_version),0) FROM agent_graph_checkpoint WHERE execution_id = ?",
            Integer.class, executionId);
    int newVersion = (maxVersion == null ? 0 : maxVersion) + 1;
    jdbcTemplate.update("INSERT INTO agent_graph_checkpoint (...) VALUES (...)", ...);
    return newVersion;
}
```

两个细节：

- `synchronized`：同一批次的多个节点完成回调在 `dag-worker` 线程里并发触发，
  `MAX(version)+1` 是先查后插，会撞唯一键。单实例串行化解决；多实例要改成数据库原子递增。
- 每次节点完成都存一个版本，形成**版本链**，可以回看"执行到哪一步"。

### 4.2 恢复

```java
public WorkflowResult resumeWorkflow(String executionId, Long merchantId, Long approvalId) {
    ExecutionContext context = loadFromCheckpoint(executionId);
    assertWorkflowTenant(context, merchantId);
    assertWorkflowApproval(context, approvalId);
    List<DAGNode> nodes = buildAfterSalesNodes();
    dagScheduler.execute(nodes, context);   // 恢复模式：不覆盖已有状态
    saveCheckpoint(context);
    return new WorkflowResult(executionId, context);
}
```

`DAGScheduler.execute` 开头：

```java
if (!context.getNodeStatuses().containsKey(node.getId())) {
    context.setNodeStatus(node.getId(), PENDING);   // 已有的状态不覆盖
}
```

所以恢复时：已 SUCCESS 的节点不会再跑，PENDING 的继续跑。**这才是真正的续跑**。

> 早期版本只在全部结束后存一次快照，`resume` 时所有节点都是终态，什么都不跑——
> 属于"看起来有恢复能力，实际是 no-op"（事故库第 10 条）。

### 4.3 续跑与幂等的关系

续跑最大的风险是**副作用重放**：节点执行成功、但快照没存，恢复后重跑会重复退款。

两道防护：

1. 节点完成即存 checkpoint（缩小窗口，但不能消除）；
2. 写操作本身幂等（`OUT_ITEM:{id}:{累计量}` + 唯一索引），重跑不会重复扣。

> 面试话术：
> "Checkpoint 只能缩小重复执行的窗口，不能消除。真正的保证是**副作用幂等**。
> 所以我把'快照'和'幂等键'当成一对：快照负责少做事，幂等负责重复做也没事。"

---

## 5. 图引擎：另一种编排方式

除了 `DAGScheduler`，项目还有一套 `agent/graph`：

| 类 | 作用 |
|----|------|
| `GraphBuilder` | 构建节点 + 边，支持条件边、循环、最大步数 |
| `ConditionalEdge` | 根据状态路由到不同节点 |
| `GraphState` | 图状态（nodeOutputs + globalContext） |
| `StateSerializer` | JSON 序列化 |
| `CheckpointManager` | 快照 |

两套引擎并存的原因：DAG 适合"确定的并行任务"，图适合"带条件分支和循环的对话流程"。
代价是**失败语义要统一**，否则调用方要区分两套。

> 面试问"为什么不用 LangGraph4j"：
> "启航是 Java 17 + Spring Boot 4，LangGraph4j 的版本兼容要额外验证；
> 而且我想自己实现一遍调度、补偿、checkpoint，理解执行模型。
> 在我的另一个项目 PaiAgent 里用了 LangGraph4j，两边可以对比——
> 这也是我下一步要讲的。"

---

## 6. 记忆系统：四层、TTL 与预算

### 6.1 四层（`MemoryLayer.java`）

| 层 | TTL | 存储 | 作用域 | 典型内容 |
|----|-----|------|--------|----------|
| WORKING | 1 小时 | 进程内 Map | 单次对话 | "刚才提到的订单号" |
| SESSION | 1 天 | Redis | 会话 | "今天问了 3 次退款" |
| USER | 30 天 | MySQL + Redis 缓存 | 用户 | "偏好简洁回答" |
| TEAM | 90 天 | MySQL + Redis 缓存 | 商户 | "公司退货政策" |

写入统一走 `MemoryManager.store`，**先脱敏再落任何介质**：

```java
MemoryEntry entry = MemoryEntry.create(layer, scopeId, key, value, merchantId, userId);
entry.setValue(SensitiveDataMasker.autoMask(value));
switch (layer) {
    case WORKING -> workingMemoryCache.put(entry.getMemoryId(), entry);
    case SESSION -> writeRedis(entry);
    case USER, TEAM -> { repository.save(entry); writeRedis(entry); }
}
```

读取时校验 TTL 与租户，跨租户返回 null。

### 6.2 上下文预算：为什么必须有

`ChatService.buildMemoryContext` 把 TEAM/USER/SESSION 三层记忆拼进 SystemMessage。
如果不限制，长会话会把上下文撑爆（token 超限、成本飙升、模型注意力涣散）。

现在的预算：

```java
private static final int MEMORY_TOKEN_BUDGET = 2000;
private static final int LAYER_CHAR_BUDGET = 450;    // 每层约 450 字符
private static final int MAX_HISTORY_MESSAGES = 20;
private static final int MAX_HISTORY_CHARS = 6000;
```

策略：**倒序遍历 key（最新的优先），累计超预算就停**。

> 早期版本是把所有 key 全量拼进去，且每轮写一个新 key（`turn_{timestamp}`），
> 无上限增长（事故库第 13 条）。修复后有了预算和截断。

### 6.3 两个 Redis 坑

1. **`KEYS` 阻塞**：早期 `listKeys` 用 `redisTemplate.keys(pattern)`，每次对话调 3 次，
   生产环境会阻塞 Redis。现在改成 `SCAN`（`Cursor`），失败才降级 `KEYS`。
2. **清理**：WORKING 层是进程内 Map，必须定时清理过期项；MySQL 过期行由
   `cleanupExpiredMemories()` 删除；Redis 靠 TTL。

> 面试话术：
> "记忆系统的难点不是存，是'治理'：TTL 怎么设、预算怎么分、过期怎么清、
> 跨租户怎么隔离、敏感信息怎么脱敏。存储是最简单的一步。"

### 6.4 脱敏的边界

`SensitiveDataMasker` 用正则处理手机号、身份证、银行卡、邮箱。
已知不足：

- 姓名脱敏会误伤整句；
- 15 位旧身份证漏处理；
- 地址只做了部分规则。

> 正确说法："脱敏是**降低**泄露面，不是消除。真要合规，应该在写入前做字段级分类，
> 而不是在自由文本上跑正则。"

---

## 7. 本篇自测

1. 串行流程的三个死穴是什么？DAG 分别怎么解决？
2. 什么是"批次屏障"？它带来什么代价？
3. 补偿为什么应该逆拓扑？当前实现反转的是什么？
4. Checkpoint 能消除重复执行吗？不能的话靠什么兜底？
5. 四层记忆的 TTL 和存储介质分别是什么？
6. 上下文预算怎么分配？超预算时保留哪些、丢弃哪些？
7. `KEYS` 和 `SCAN` 的区别是什么？为什么生产禁用 `KEYS`？

### 第 7 题的参考答法

> "`KEYS` 是一次性遍历整个库并返回所有匹配键，复杂度 O(N)，期间阻塞单线程 Redis；
> `SCAN` 是游标式增量遍历，每次只返回一小批，不阻塞，但可能重复、需要客户端去重。
> 记忆键的数量随用户增长，必须用 `SCAN`。"

---

## 8. 下一步

进入 `07-迭代史与事故库`。前面六篇讲的是"现在是什么样"，
下一篇讲"为什么会变成这样"——这是面试里最能体现工程判断的部分。
