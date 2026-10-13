# Week1-7 加固交付报告

> 日期：2026-10-12
> 范围：基于 Claude Code 留下的代码产物，对 Week1-7 现有框架进行加固
> 原则：不破坏源码、必须审查达标、不自我幻觉，有问题宁可搁置也不强行凑

---

## 一、本轮完成的加固

### 1. 阻断性编译问题修复（重大）

**根因**：`common/src/main/java/cn/qihangerp/common/R.java` 是 `public class R extends HashMap<String, Object>`（无泛型参数），而 `ActionLedgerController` 用了 `R<List<ActionLedger>>`、`R<Page<ActionLedger>>`、`R<Double>` 等参数化用法。javac 解析"无泛型类被参数化使用"时类型解析异常，**触发 Lombok 注解处理器全局中止**，导致整个 erp-api 模块 98 个 Lombok 类的生成方法（@Slf4j 的 log、@Data 的 getter、@Builder 的 builder）全部找不到，固定 200 个编译错误。

**修复**：给 R.java 加标准泛型 `<T>`，所有静态方法返回 `R<T>`。

**验证**：修复后 clean compile 成功（134 个源文件），Lombok @Builder 正常生成（确认 `ActionLedger$ActionLedgerBuilder.class` 存在）。

### 2. Week2-3 ToolGateway 审计接入

- 给 ToolGateway 注入 ActionLedgerService（@Autowired required=false，降级兼容）
- `recordAuditLog` 从 TODO 占位（只打日志）改为真实调用 `actionLedgerService.recordAction()`
- 构造完整 ActionLedgerRequest（17 个字段：traceId/toolName/toolScope/autonomyLevel/merchantId/userId/sessionId/inputParams/outputResult/success/errorMessage/startTime/endTime/durationMs/isWriteOperation/requiresApproval/ipAddress/userAgent）
- 审计失败 catch 不抛异常（不影响业务流程）

### 3. Week2-3 11 个业务 Tool 加 @ToolPolicy 注解

全部 13 个 @Tool 方法加 `@ToolPolicy(level = AutonomyLevel.OBSERVE, category = "...")`：

| Tool 类 | 方法 | 分类 |
|---------|------|------|
| OrderTools | getOrderList | 订单查询 |
| GoodsTools | searchGoods, searchSku | 商品查询 |
| InventoryTools | getInventoryList | 库存查询 |
| LogisticsTools | getWaybillList | 物流查询 |
| MemberTools | getMemberList | 会员查询 |
| PurchaseTools | getPurchaseOrderList | 采购查询 |
| RefundTools | getRefundList | 售后查询 |
| ShopTools | getShopList | 店铺查询 |
| StockFlowTools | getStockInList, getStockOutList | 库存流水查询 |
| SupplierTools | getSupplierList | 供应商查询 |
| WarehouseTools | getWarehouseList | 仓库查询 |

全部为只读查询，统一 OBSERVE 级别。

### 4. Week2-3 审计模块（从 Claude 备份取回）

- `ActionLedger.java`：@TableName("agent_action_ledger") 实体，@Data/@Builder
- `ActionLedgerMapper.java`：@Mapper extends BaseMapper
- `ActionLedgerService.java`：recordAction / 按 traceId/merchantId/userId 查询 / 失败查询 / 审批查询 / 成功率统计，审计失败不抛异常
- `ActionLedgerController.java`：@RestController @RequestMapping("/api/agent/audit")
- `ActionLedgerRequest.java`：Builder + 全 getter/setter（纯手写无 Lombok）
- `V1_14_0__create_agent_action_ledger.sql`：建表（含 idx_trace_id/tool_name/merchant_id/user_id/create_time/success/tool_scope 共 7 个索引）

### 5. Week2-3 租户上下文（从 Claude 备份取回）

- `TenantContext.java`：ThreadLocal 租户上下文
- `TenantInterceptor.java`：从 Header X-Tenant-Id 或参数 merchantId 提取，afterCompletion 清理
- `TenantWebMvcConfig.java`：注册拦截器

### 6. 启动问题修复

- **redisson 移除**：redisson-spring-boot-starter 3.35.0（Week4-6 引入、针对 Boot 3.x）在 Boot 4.1 下引用已移除的 `RedisProperties` 类导致启动失败，且项目代码完全没用 Redisson。从 erp-api pom 和 application-agent.yml 移除。
- **PGVector 排除**：`spring.ai.vectorstore.pgvector.enabled=false` 在 Spring AI 2.0/Boot 4.1 下无法禁用 PgVectorStoreAutoConfiguration（亲自 mvn spring-boot:run 复现），改用 `@SpringBootApplication(exclude=PgVectorStoreAutoConfiguration.class)`。

### 7. 已有框架确认完整（无需加固）

- **Week3-4 评测闭环**：RagEvaluator 框架完整（检索指标 Recall/Precision/F1/MRR/NDCG 全是真实算法，从数据库加载 100 条评测集，聚合统计完整），RagController 暴露了 `/api/agent/rag/evaluate` 评测 API。唯一不足是 generateAnswer 模拟（拼接前3个chunk，注释明确标注）。
- **Week6-7 记忆层**：MemoryRepository（JdbcTemplate upsert/findById/deleteById/deleteExpired）+ MemoryManager（四层记忆+Jackson 序列化+租户隔离+脱敏）+ LayeredMemoryTest（10/10 通过），上一轮已完成。

---

## 二、搁置项（诚实说明，未假装完成）

### 1. Week1-2 状态机/乐观锁/分布式锁/幂等键 — 完全未实施

- **原因**：涉及订单/售后核心业务流程的状态流转、实体字段修改、DDL 变更、并发控制，风险高、投入大。在不破坏源码的约束下，需要深入理解现有订单/售后的状态字段和业务逻辑，无法在本轮安全实施。
- **前置条件**：需要先梳理现有订单/售后状态机（ShopOrder.orderStatus、ORefundAfterSale 等），明确状态流转规则，再设计状态机框架和乐观锁字段。

### 2. ToolGateway 不自动拦截 AI 调用 — 架构限制

- **现状**：Spring AI 的 @Tool 调用由框架默认 MethodToolCallback 直接反射执行（method.invoke），不经过 ToolGateway。因此 @ToolPolicy 注解和 ToolGateway 的策略检查/审计在 AI 自动调用时不会自动触发。
- **AOP 拦截评估**：尝试用 Spring AOP 拦截 @Tool 方法，但 Spring AI 的 MethodToolCallback 用 `method.invoke(target, args)` 调用，如果 method 是从原始类获取的（@Tool 注解在原始类方法上），invoke 会绕过 Spring AOP 代理。无法在不启动 Spring 上下文的情况下 100% 确认，技术风险高，**暂未实施**。
- **@ToolPolicy 注解的价值**：虽然当前不自动生效，但注解是元数据声明（文档价值），且是 ToolGateway 手动调用（`toolGateway.execute()`）和将来 AOP 拦截的前置条件。

### 3. 11 个 Tool 未做租户数据过滤 — 侵入式修改

- **现状**：Tool 方法的查询（如 OrderTools.getOrderList）没有自动带 merchantId 过滤条件，查询的是全量数据。
- **原因**：需要修改每个 Tool 的查询逻辑加 merchantId 条件，侵入式修改业务逻辑，且需要确认每个 Tool 对应的实体是否有 merchantId 字段，风险较高。
- **前置条件**：需要逐个 Tool 确认对应实体的租户字段，设计统一的租户过滤方式（MyBatis-Plus 拦截器或手动加条件）。

### 4. Week4-6 RAG 向量检索假数据 — 依赖缺失

- **现状**：HybridRetriever.retrieveByVector 是 TODO，返回硬编码的 chunk_001/002/003 假数据。PGVector 因缺 EmbeddingModel 不可用（已排除自动配置）。BM25Retriever 纯内存 HashMap 倒排索引，无持久化、无数据自动导入。
- **原因**：需要配置可用的 EmbeddingModel（如 OpenAI/本地 embedding 服务）、PGVector 扩展、知识库导入流程，依赖外部服务和配置，本轮无法实施。
- **影响**：RagEvaluator 评测时检索到的都是假 chunk，Recall/Precision 指标会接近 0，评测结果不准确。但评测框架本身是完整可运行的。

### 5. Week3-4 评测生成答案模拟 — 未接 LLM

- **现状**：RagEvaluator.generateAnswer 是模拟的（拼接检索结果的前3个chunk），注释明确标注"模拟，实际需要调用LLM"。
- **原因**：需要接入项目现有的 AI 聊天服务（ChatController/ChatService）来生成真实答案，需要理解现有 AI 服务的调用方式。
- **前置条件**：确认项目现有 AI 服务的 API，将 generateAnswer 改为真实调用。

### 6. Week6-7 记忆层端到端验证 — 需启动环境

- **现状**：MemoryRepository/MemoryManager 单元测试 10/10 通过，但未做 API 真实读写、Redis 重启恢复的端到端验证。
- **原因**：需要启动应用（Tomcat 6149）、MySQL（qihang-erp 库）、Redis（127.0.0.1:6379），并手动调用 MemoryController 的 API 验证。
- **前置条件**：本地 MySQL 和 Redis 服务可用，Flyway 迁移执行成功（V1_13_0 建表）。

---

## 三、验证结果

| 验证项 | 结果 |
|--------|------|
| 多模块 clean compile | BUILD SUCCESS（erp-api 134 个源文件 + common/service/security 等依赖模块） |
| erp-api 单元测试 | 19/19 通过（ToolGatewayTest 9 + LayeredMemoryTest 10） |
| 其他模块单元测试 | 30/30 通过 |
| Lombok @Builder 生成 | 确认正常（ActionLedger$ActionLedgerBuilder.class 存在） |
| 应用启动 | Tomcat 6149 启动成功，MySQL 连接池正常，`Started ErpApi`（redisson 移除 + PGVector 排除后） |

---

## 四、Claude Code 产物处置总结

| 分类 | 内容 | 处置 |
|------|------|------|
| 取回 | TenantContext/TenantInterceptor/TenantWebMvcConfig | 标准 ThreadLocal+MVC 拦截器，已合入 |
| 取回 | ActionLedger/ActionLedgerMapper/ActionLedgerService/ActionLedgerController/ActionLedgerRequest | MyBatis-Plus 写库、审计失败不抛异常，已合入 |
| 取回 | V1_14_0 Flyway 脚本 | agent_action_ledger 建表，已合入 |
| 搁置 | Claude 改版 ToolPolicy 注解（autonomyLevel/write/scope/idempotent/requiresApproval/maxRetries/audit/compensationMethod，仅 METHOD） | 与当前代码 ToolPolicy（level/category/description/requiresTenantIsolation/requiresAudit/maxBatchSize/timeoutSeconds/allowConcurrent，METHOD+TYPE）完全不兼容，直接合入会编译失败。PolicyRegistry/PolicyScanner/PolicyEvaluator/ToolPolicyMetadata 及 11 个 Tool 的注解都基于 Claude 改版注解，未合入 |
| 抛弃 | RedisDistributedLock+RedisLockController | 手撸 SETNX、错放 memory 包，未合入 |
| 抛弃 | 删除通知模块 5 个源文件 | 越界破坏，已恢复 |
| 抛弃 | Claude 版 MemoryManager/MemoryEntry | 反序列化丢 layer/merchantId 字段必空指针，已用重写版替换 |

备份目录：`claude-week67-backup/`（含 files/、removed/、manifest.txt、tracked.patch）

---

## 五、后续建议（按优先级）

1. **高优先级**：Week1-2 状态机/乐观锁 — 需要先梳理现有订单/售后状态字段，设计状态机框架，这是方案的核心任务点
2. **中优先级**：Tool 租户数据过滤 — 逐个 Tool 加 merchantId 过滤，或用 MyBatis-Plus 租户拦截器统一处理
3. **中优先级**：RAG 真实化 — 配置 EmbeddingModel + PGVector，实现知识库导入，替换 retrieveByVector 的假数据
4. **低优先级**：AOP 拦截 @Tool — 需启动 Spring 上下文验证 method.invoke 是否触发代理，确认可行后再实施
5. **低优先级**：评测生成答案接 LLM — 将 generateAnswer 改为调用项目现有 AI 服务

---

## 六、本轮修改文件清单

### 修改的文件
- `common/src/main/java/cn/qihangerp/common/R.java` — 加泛型 `<T>`（编译修复）
- `erp-api/src/main/java/cn/qihangerp/agent/gateway/ToolGateway.java` — 注入 ActionLedgerService，实现 recordAuditLog
- `erp-api/src/main/java/cn/qihangerp/erp/ErpApi.java` — exclude PgVectorStoreAutoConfiguration
- `erp-api/pom.xml` — 移除 redisson 依赖
- `erp-api/src/main/resources/application-agent.yml` — 移除 redisson 段
- `erp-api/src/main/java/cn/qihangerp/erp/serviceImpl/ai/*.java`（11 个文件）— 加 @ToolPolicy 注解
- `docs/CHANGELOG.md` — 追加本轮加固记录

### 新增的文件
- `erp-api/src/main/java/cn/qihangerp/agent/audit/ActionLedger.java`
- `erp-api/src/main/java/cn/qihangerp/agent/audit/ActionLedgerMapper.java`
- `erp-api/src/main/java/cn/qihangerp/agent/audit/ActionLedgerService.java`
- `erp-api/src/main/java/cn/qihangerp/agent/audit/ActionLedgerController.java`
- `erp-api/src/main/java/cn/qihangerp/agent/audit/ActionLedgerRequest.java`
- `erp-api/src/main/java/cn/qihangerp/agent/tenant/TenantContext.java`
- `erp-api/src/main/java/cn/qihangerp/agent/tenant/TenantInterceptor.java`
- `erp-api/src/main/java/cn/qihangerp/agent/tenant/TenantWebMvcConfig.java`
- `erp-api/src/main/resources/db/migration/V1_14_0__create_agent_action_ledger.sql`
- `docs/Week1-7-加固交付报告.md`（本文件）
