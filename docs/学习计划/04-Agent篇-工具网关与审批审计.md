# 04 Agent 篇：工具网关、五级自治、审批与审计

> 目标：能讲清"AI 说了一句话，怎么变成一个受控的数据库写操作，事后怎么查得到"。
> 这是整个项目最有辨识度的部分，也是面试追问最深的部分。
> 预计用时：D5 全天（约 4 小时）

---

## 1. 问题的起点：Spring AI 的 `@Tool` 根本不受你控制

Spring AI 2.0 调用工具的方式大致是：

```java
// 框架内部（简化）
MethodToolCallback callback = new MethodToolCallback(method, toolObject, ...);
Object result = callback.call(toolInputJson);   // 最终 method.invoke(target, args)
```

它直接反射调用目标方法。后果是：

- **AOP 拦不住**：`@Tool` 注解在原始类方法上，`method.invoke(target, ...)` 绕过了 Spring 代理。
- **没有统一入口**：14 个工具类各写各的查询，租户过滤、审计、限流都要每个方法 copy 一遍。
- **不可审计**：模型调了什么、传了什么、返回什么，只有日志。

> 面试话术（可直接背）：
> "我一开始想用 AOP 切 `@Tool` 方法，后来发现不行——Spring AI 的 `MethodToolCallback`
> 是用原始 Method 反射调用的，走不到代理。所以改成**替换 ToolCallback**：
> 自己实现一个 `ToolCallback`，内部转调网关。这样模型看到的工具定义不变，
> 但实际执行换成了我的受控路径。"

这是"知道框架原理"和"只会用注解"的分水岭。

---

## 2. 接入点：自定义 ToolCallback

### 2.1 工厂（`agent/gateway/GatewayToolCallbackFactory.java`）

```java
public List<ToolCallback> create(Object... toolBeans) {
    for (Object bean : toolBeans) {
        // 1. 用官方 Provider 生成原始回调，拿到 ToolDefinition（含 JSON Schema）
        ToolCallback[] rawCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(bean).build().getToolCallbacks();
        Map<String, ToolDefinition> definitionMap = ...;

        // 2. 解析真实类（处理 CGLIB 代理）
        Class<?> userClass = ClassUtils.getUserClass(bean);
        for (Method method : userClass.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) continue;
            ToolDefinition def = definitionMap.get(toolName);
            // 3. 用自定义回调替换
            callbacks.add(new GatewayToolCallback(bean, method, def, toolGateway, objectMapper));
        }
    }
    return callbacks;
}
```

三个关键设计：

1. **复用官方 `MethodToolCallbackProvider` 生成 `ToolDefinition`**：
   入参 JSON Schema 由框架生成，保证"模型看到的"和"实际解析的"一致。自己手写 schema 迟早漂移。
2. **`ClassUtils.getUserClass`**：工具 Bean 可能被 CGLIB 代理，直接 `getClass()` 看不到 `@Tool`。
3. **一个方法一个 Callback**：粒度和框架一致，模型侧无感。

### 2.2 回调（`GatewayToolCallback.java`）

```java
@Override
public String call(String toolInput, ToolContext toolContext) {
    Map<String, Object> ctx = toolContext.getContext();
    Long merchantId = toLong(ctx.get("merchantId"));
    // ...
    TenantContext.setTenantId(merchantId);        // 透传给工具内部
    try {
        Object[] args = parseArgs(toolInput);      // JSON → 方法参数
        return (String) toolGateway.execute(toolBean, toolMethod.getName(),
                new ToolExecutionContext(merchantId, userId, traceId), args);
    } finally {
        TenantContext.clear();                     // 必须清理，线程池复用
    }
}
```

**为什么要在回调里设置 `TenantContext`？**
工具方法内部是同步执行的，但调用链上游是 Reactor 线程（SSE 流）。
ThreadLocal 不能跨线程传递，所以必须在**进入同步方法前**显式设置，执行完清理。

> 这是"ThreadLocal 在异步链路里会丢"的实战案例，比背 ThreadLocal 原理更值钱。

---

## 3. 核心：ToolGateway 的八步流水线

文件：`agent/gateway/ToolGateway.java`，方法 `execute(...)`。

### 3.1 全景

```
① 查方法 + 解析策略（PolicyRegistry → 注解 → 默认 ESCALATE）
② 记录 Trace 开始
③ 权限校验（写权限、批量上限）
④ 租户校验（fail-closed：没租户就拒绝）
⑤ 幂等检查（仅写工具，SET NX）
⑥ 并发控制（分布式锁 → 降级内存锁）
⑦ 审批判断（ESCALATE：无审批则创建并抛异常；DRAFT：只建草稿）
⑧ 执行 + 审计 + 指标 + Trace + 幂等完成 + 释放锁
```

### 3.2 代码骨架（删减版，保留判断顺序）

```java
public Object execute(Object toolInstance, String methodName,
                      ToolExecutionContext context, Object... args) throws ToolExecutionException {
    long startTime = System.currentTimeMillis();
    String toolName = toolInstance.getClass().getSimpleName() + "." + methodName;

    try {
        Method method = findToolMethod(toolInstance.getClass(), methodName);
        ToolPolicy policy = resolvePolicy(toolName, method);        // ①

        traceEntry = traceService.recordStart(...);                 // ②

        validatePolicy(policy, context);                            // ③

        if (policy.requiresTenantIsolation() && context.getMerchantId() == null) {
            throw new ToolExecutionException("缺少租户ID，拒绝执行（fail-closed）");  // ④
        }

        if (policy.level().allowsWrite() && idempotencyGuard != null) {
            IdempotencyResult r = idempotencyGuard.checkAndMark(...); // ⑤
            if (r.isDuplicate()) { ... }
        }

        if (!policy.allowConcurrent()) {                            // ⑥
            distLockHandle = distributedLockService.tryLock("tool:" + toolName, 30);
            if (!distLockHandle.isAcquired()) throw ...;
        }

        if (policy.level() == AutonomyLevel.ESCALATE) {              // ⑦
            if (!context.isApproved()) {
                boolean already = approvalService.isApproved(
                        context.getTraceId(), toolName, context.getMerchantId(), args);
                if (!already) {
                    Long id = approvalService.createApproval(...);
                    throw new ApprovalRequiredException("此操作需要人工确认", toolName,
                            context.getTraceId(), id);
                }
                context.setApproved(true);
                context.setApprovalId(...);
            }
        }

        Object result = method.invoke(toolInstance, args);           // ⑧

        if (policy.requiresAudit()) recordAuditLog(...);
        idempotencyGuard.complete(idempotencyKey, resultDigest);
        metricsCollector.recordSuccess(toolName, policy.category(), duration, merchantId);
        traceService.recordSuccess(traceEntry, resultStr, duration);
        return result;
    } catch (ApprovalRequiredException e) {
        idempotencyGuard.fail(idempotencyKey);   // 清幂等，允许批准后重试
        traceService.recordApprovalRequired(traceEntry, e.getMessage());
        throw e;
    } catch (Exception e) {
        // 解包 InvocationTargetException，记录失败指标/审计/Trace，清幂等
        throw new ToolExecutionException("Tool执行失败: " + toolName, e);
    } finally {
        if (distLockHandle != null && distLockHandle.isAcquired()) distributedLockService.unlock(...);
        if (inMemoryLockAcquired) executionLocks.remove(toolName);
    }
}
```

### 3.3 为什么顺序不能乱（面试高频）

| 顺序 | 如果提前/推后会怎样 |
|------|---------------------|
| 幂等必须在执行前 | 执行完再判重，已经写了一次，没意义 |
| 锁必须在幂等之后 | 先加锁再判重，重复请求会排队等锁，浪费连接 |
| 审批必须在执行前 | 先执行再审批，等于没审批 |
| 审计必须在 try/catch 两侧都能写 | 失败也要留痕，否则"失败的调用"查不到 |
| `finally` 释放锁必须在最外层 | 任何异常路径都要释放，否则死锁 |

> 面试可以主动画这条流水线，然后说"这八步的顺序本身就是设计"。

### 3.4 `resolvePolicy` 的三级降级

```java
if (policyRegistry != null) {
    metadata = policyRegistry.getPolicy(toolName);   // 1. 启动时扫描的注册表
    if (metadata != null) return policyFromMetadata(metadata);
}
ToolPolicy policy = method.getAnnotation(ToolPolicy.class);  // 2. 反射注解
if (policy != null) return policy;
return createDefaultPolicy();                                 // 3. 默认 ESCALATE
```

默认策略是 **ESCALATE**（不是 OBSERVE）：没标注策略的工具一律要人工审批。
这是"fail-closed"的体现——**忘了配置不会导致放行，只会导致不能执行**。

> 早期版本默认是 OBSERVE，等于"没配置就能随便写"，审查报告把它列为 P2 缺陷，后来改掉了。

---

## 4. 五级自治：不是五个名词，是五种执行语义

`agent/policy/AutonomyLevel.java`：

```java
OBSERVE("观察模式", "只读查询，无任何写操作", 0),
SUGGEST("建议模式", "生成建议方案，不执行", 1),
DRAFT("草稿模式", "创建草稿记录，等待审核", 2),
EXECUTE("执行模式", "直接执行写操作，记录审计", 3),
ESCALATE("升级模式", "高风险操作，必须人工确认", 4);

public boolean allowsWrite() { return this.level >= EXECUTE.level; }
public boolean requiresApproval() { return this == ESCALATE; }
```

| 级别 | 网关行为 | 典型工具 |
|------|----------|----------|
| OBSERVE | 直接执行，记审计 | `OrderTools.getOrderList` |
| SUGGEST | 直接执行（工具自己只返回建议，不写库） | `SuggestionTools.generateRestockSuggestion` |
| DRAFT | **不执行**，创建 `[草稿]` 审批单，返回草稿信息 | 暂无工具使用 |
| EXECUTE | 直接执行 + 审计 | 低风险写操作 |
| ESCALATE | 无审批 → 创建审批单并抛 `ApprovalRequiredException` | `OrderActionTools.cancelOrder` |

> 面试陷阱："SUGGEST 和 DRAFT 有什么区别？"
> 答："SUGGEST 是**工具本身只读**，它不改库，只是输出建议；DRAFT 是**工具本来要写**，
> 但网关把它拦下来只生成草稿单。前者靠工具自律，后者靠网关强制。"

**当前实现的诚实说明**：`SUGGEST` 在网关层没有特殊处理（和 OBSERVE 一样直接执行），
语义靠工具实现保证。这是可以主动说的边界，不要说成"五级都有强制拦截"。

---

## 5. 策略注册表：注解是默认值，DB 是运行时开关

`PolicyRegistry` 在 `@PostConstruct` 做两件事：

```java
public void init() {
    scanAndRegister();   // 扫描所有 Bean 的 @Tool 方法，读 @ToolPolicy
    loadFromDb();        // 从 agent_tool_policy 表加载，覆盖注解
}
```

- **扫描**：遍历 `applicationContext.getBeanDefinitionNames()`，用 `AopUtils.getTargetClass`
  取真实类，找 `@Tool` 方法。
- **DB 覆盖**：运维可以在不改代码的情况下把某个工具降级/禁用（表里有 `enabled` 等字段）。

> 面试问"策略改了要重启吗"：
> "目前 DB 策略在启动时加载，改表需要重启或调刷新接口。这是可接受的，
> 因为工具策略变更频率极低；真正的动态开关应该走配置中心，不是数据库。"

---

## 6. 审批闭环：从"能创建"到"能执行"

这是最容易讲成"我做了审批"但经不起追问的地方。完整生命周期：

```
PENDING ──approve()──▶ APPROVED ──claimApproved()──▶ EXECUTED
   │                       │
   ├──reject()──▶ REJECTED │
   └──超时──▶ EXPIRED      └──执行失败──▶ 回 PENDING（清审批人）
```

### 6.1 关键设计一：traceId 必须跨轮次稳定

`ChatService` 里：

```java
// traceId 会话级稳定：以 sessionId 为审批匹配键，使用户"批准后重发"能命中已有审批
String traceId = sessionId != null ? sessionId : UUID.randomUUID().toString();
```

早期版本每次对话都 `UUID.randomUUID()`，导致"用户批准后重发"带着新 traceId，
永远匹配不上旧审批，于是又创建一条新的 PENDING——**审批永远执行不了**（事故库第 5 条）。

> 面试话术：
> "审批的匹配键不是'用户'，是'这一次意图'。traceId 必须能跨请求标识同一次意图，
> 所以我把它提升到 session 级。这是我在联调时发现'批了也不执行'才想明白的。"

### 6.2 关键设计二：参数摘要防"批 A 执行 B"

```java
private boolean matchesArgs(ApprovalRequest approval, Object[] args) {
    String currentArgsJson = objectMapper.writeValueAsString(args);
    return currentArgsJson.equals(approval.getInputParams());
}
```

用户批准的是"取消订单 #1"，如果重发时参数变成订单 #2，摘要不一致 → 要求重新审批。

> 这是"审批单与工具、租户绑定"的具体实现，也是简历里可以写的点。

### 6.3 关键设计三：`claimApproved` 原子抢占防双执行

```java
wrapper.eq(ApprovalRequest::getId, approvalId)
       .eq(ApprovalRequest::getStatus, APPROVED)
       .gt(ApprovalRequest::getExpiresAt, LocalDateTime.now())
       .set(ApprovalRequest::getStatus, EXECUTED)
       .set(ApprovalRequest::getExecutionResult, "执行中");
int updated = approvalMapper.update(null, wrapper);
```

两个并发请求同时拿到"已批准"，只有一个能把 `APPROVED → EXECUTED`（`affected=1`），
另一个 `affected=0` 直接返回 false。**用一条条件更新做分布式抢占**，不需要额外加锁。

> 面试追问："为什么不用 SELECT FOR UPDATE？"
> 答："条件更新更轻，而且天然幂等；`SELECT FOR UPDATE` 要占着连接等锁，
> 在审批这种低频场景没必要。"

### 6.4 其他细节（被追问时的弹药）

- **创建去重**：同一 `traceId + toolName + PENDING` 不重复创建。
- **过期**：`expiresAt = now + 300s`，`@Scheduled` 扫描把过期置 `EXPIRED`。
- **失败回退**：执行失败把状态改回 `PENDING`，并清空 `approverId/approvedAt`，避免"批了但没执行"的假象。
- **审批也落审计**：`approve()` 成功后会写一条 `toolScope="审批"` 的 `ActionLedger`。

---

## 7. 三张表的分工：别把审计、链路、指标混在一起

| 表/组件 | 记什么 | 事务性 | 用途 |
|---------|--------|--------|------|
| `agent_action_ledger` | 每次工具调用的入参、出参、耗时、成功/失败、审批人 | 独立写入（失败不影响业务） | 合规审计、问题追溯 |
| `agent_trace` | 一次执行链路的节点（开始/成功/失败/待审批） | 独立写入 | 排查"Agent 为什么这么做" |
| `MetricsCollector` + Micrometer | 计数、耗时、错误分布 | 内存/Prometheus | 监控面板、告警 |
| `agent_approval_request` | 审批单状态机 | 与审批动作同事务 | 人工决策 |

> 面试话术：
> "审计和 Trace 不是一回事。审计回答'谁在什么时候对什么做了什么'，
> 是合规视角；Trace 回答'这一次执行经过了哪些步骤'，是排障视角。
> 指标是聚合视角，用来发现趋势，不用于定责。"

### 7.1 一个容易忽略的点：审计不能影响业务

```java
try {
    actionLedgerService.recordAction(request);
} catch (Exception e) {
    log.error("记录审计日志失败（不影响业务）: {}", e.getMessage());
}
```

审计表挂了，业务要继续。这是"审计是旁路"的原则；反过来说，
**真正不能丢的审计必须和业务同事务**（比如库存流水），两者要区分。

---

## 8. 租户隔离：三道闸门

1. **入口**：`TenantInterceptor` 只从登录态取 `merchantId`，忽略客户端请求头（管理员除外）。
2. **工具层**：`ToolGateway` 对 `requiresTenantIsolation` 的工具做 fail-closed 校验。
3. **查询层**：每个 Tool 内部 `wrapper.eq(ShopOrder::getMerchantId, merchantId)`。

> 面试追问："如果某个 Tool 忘了加查询条件呢？"
> 答："网关只能保证'有租户'，保证不了'SQL 带了租户'。
> 真正的兜底是 MyBatis-Plus 的多租户插件或数据库行级权限；
> 目前是三重防护里的第三重靠人工，这是已知缺口。"

**主动承认边界**比假装完美更加分。

---

## 9. 对话编排：ChatService 做的四件事

文件：`erp-api/.../erp/serviceImpl/ChatService.java`

```java
public void streamResponse(AiConfig config, SseEmitter emitter, String sessionId, Long userId) {
    List<AiConversationHistory> history = trimHistory(...);        // 1. 历史裁剪
    Long merchantId = resolveMerchantId();                         // 2. 租户解析
    String memoryContext = buildMemoryContext(...);                // 3. 记忆注入
    ChatClient chatClient = orchestrationService.buildChatClient(  // 4. 工具装配
            config, toolContext, goodsTools, orderTools, orderActionTools, ...);
    chatClient.prompt().messages(messages).stream().content()
            .subscribe(chunk -> sendJsonEvent(emitter, "message", chunk),
                       error -> ...,
                       () -> { saveMessage(...); saveSessionMemory(...); });
}
```

### 9.1 历史裁剪：两道预算

```java
private static final int MAX_HISTORY_MESSAGES = 20;
private static final int MAX_HISTORY_CHARS = 6000;
```

先按条数截断，再按字符数从后往前保留——**保留最近的，丢弃最旧的**，且保持时间顺序。

### 9.2 为什么用 SSE 而不是 WebSocket

- 对话是**单向流式**（服务端 → 客户端），SSE 足够；
- SSE 走普通 HTTP，天然过网关/鉴权，不用额外握手协议；
- 缺点是只能服务端推、断线重连要自己做，本项目用 `SseEmitter` + 前端重连解决。

### 9.3 工具很多，怎么让模型不乱调

`ChatService` 构造 `ChatClient` 时把 14 个工具类全部注册。
模型根据 `@Tool(description=...)` 自己选。控制手段是：

- description 写清楚"什么时候用、返回什么"；
- 只读工具用 OBSERVE，写工具用 ESCALATE；
- 系统提示明确"可以多次调用工具，自行决定下一步"。

> 面试问"工具太多会不会选错"：
> "会。缓解手段是描述精确 + 分类（订单/库存/采购/售后）+ 只读优先。
> 更彻底的做法是工具检索（按 query 召回 top-k 工具）或分层 Agent，
> 但这个项目规模还没到需要那一步。"

---

## 10. 本篇自测

1. 为什么 AOP 拦不住 `@Tool`？你是怎么接进网关的？
2. 画出 ToolGateway 八步，并解释为什么幂等要在锁之前。
3. `ESCALATE` 和 `DRAFT` 的区别是什么？`SUGGEST` 在网关层有特殊处理吗？
4. 审批的匹配键是什么？为什么不能用"用户 + 工具"？
5. `claimApproved` 如何防止同一审批被执行两次？
6. 审计、Trace、指标三者的边界是什么？
7. 租户隔离的三道闸门分别在哪？第三道为什么可能漏？

### 第 2 题的参考答法（30 秒版）

> "先解析策略，再记 Trace，然后校验写权限和租户，接着做幂等标记，
> 再拿分布式锁，然后判断是否需要审批，最后执行并写审计/指标/Trace。
> 幂等必须在锁前面：如果先加锁，重复请求会排在锁上等，既浪费连接又没意义；
> 幂等用 SET NX 直接拒绝，成本最低。"

---

## 11. 下一步

进入 `05-RAG篇`。Agent 会"做事"之后，下一个问题是"它怎么知道该怎么做"——
这就是知识库与检索。
