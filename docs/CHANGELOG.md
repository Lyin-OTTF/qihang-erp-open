# 更新日志（Changelog）

> 本文件记录启航 ERP「Agent 工程魔改方案」的迭代进度，对应方案《功能更新与实施路径分析》中的阶段与任务编号。

---

## [Week2-3 加固] 2026-10-12 — 工具网关审计接入 + 策略注解 + 编译修复

**对应方案进度**：第一阶段 Week2-3「工具权限与审计系统」加固
**里程碑**：ToolGateway 审计从 TODO 占位升级为真实写库，11 个业务 Tool 全部声明自治策略，阻断性编译问题彻底解决

### 新增功能

| 文件 | 说明 |
|------|------|
| `erp-api/.../agent/audit/ActionLedger.java` | 审计日志实体（@TableName agent_action_ledger） |
| `erp-api/.../agent/audit/ActionLedgerMapper.java` | MyBatis-Plus Mapper |
| `erp-api/.../agent/audit/ActionLedgerService.java` | 审计服务（recordAction / 按 traceId/merchantId/userId 查询 / 成功率统计，审计失败不抛异常） |
| `erp-api/.../agent/audit/ActionLedgerController.java` | 审计查询 REST API（/api/agent/audit） |
| `erp-api/.../agent/audit/ActionLedgerRequest.java` | 审计请求 Builder（纯手写无 Lombok） |
| `erp-api/.../agent/tenant/TenantContext.java` | ThreadLocal 租户上下文 |
| `erp-api/.../agent/tenant/TenantInterceptor.java` | 从 Header X-Tenant-Id 或参数 merchantId 提取，afterCompletion 清理 |
| `erp-api/.../agent/tenant/TenantWebMvcConfig.java` | 注册拦截器 |
| `erp-api/src/main/resources/db/migration/V1_14_0__create_agent_action_ledger.sql` | agent_action_ledger 建表（含 7 个索引） |

### 变更与说明

- **ToolGateway.java**：注入 ActionLedgerService（@Autowired required=false 降级兼容），`recordAuditLog` 从 TODO 占位（只打日志）改为真实调用 `actionLedgerService.recordAction()`，构造完整 ActionLedgerRequest（traceId/toolName/toolScope/autonomyLevel/merchantId/userId/sessionId/inputParams/outputResult/success/errorMessage/startTime/endTime/durationMs/isWriteOperation/requiresApproval/ipAddress/userAgent），审计失败 catch 不抛异常
- **11 个业务 Tool 类**：全部 13 个 @Tool 方法加 `@ToolPolicy(level = AutonomyLevel.OBSERVE, category = "...")`（OrderTools/GoodsTools/InventoryTools/LogisticsTools/MemberTools/PurchaseTools/RefundTools/ShopTools/StockFlowTools/SupplierTools/WarehouseTools），全部为只读查询统一 OBSERVE 级别
- **R.java 编译修复（重大）**：根因是 `R extends HashMap<String,Object>` 无泛型参数，而 ActionLedgerController 用了 `R<List<ActionLedger>>` 等参数化用法，javac 解析"无泛型类被参数化使用"时类型解析异常，**触发 Lombok 注解处理器全局中止**，导致整个模块 98 个 Lombok 类的生成方法全部找不到（200 个编译错误）。修复：给 R.java 加标准泛型 `<T>`，所有静态方法返回 `R<T>`
- **redisson 移除**：redisson-spring-boot-starter 3.35.0（Week4-6 引入、针对 Boot 3.x）在 Boot 4.1 下引用已移除的 `RedisProperties` 类导致启动失败，且项目代码完全没用 Redisson，从 pom 和 application-agent.yml 移除
- **PGVector 排除**：`spring.ai.vectorstore.pgvector.enabled=false` 在 Spring AI 2.0/Boot 4.1 下无法禁用 PgVectorStoreAutoConfiguration，改用 `@SpringBootApplication(exclude=PgVectorStoreAutoConfiguration.class)`

### 验证情况

| 项 | 结果 |
|----|------|
| 多模块 clean compile | BUILD SUCCESS（134 个源文件） |
| 全部单元测试 | 49/49 通过（erp-api 19：ToolGatewayTest 9 + LayeredMemoryTest 10；其他模块 30） |
| Lombok @Builder 生成 | 确认正常（ActionLedger$ActionLedgerBuilder.class 存在） |

### 已知限制（诚实说明）

- **ToolGateway 不自动拦截 AI 调用**：Spring AI 的 @Tool 调用由框架默认 MethodToolCallback 直接反射执行（method.invoke），不经过 ToolGateway。因此 @ToolPolicy 注解和 ToolGateway 的策略检查/审计在 AI 自动调用时不会自动触发，需手动通过 `toolGateway.execute()` 调用才生效。AOP 自动拦截因 method.invoke 可能绕过 Spring 代理、技术风险高，暂未实施
- **11 个 Tool 未做租户数据过滤**：Tool 方法的查询没有自动带 merchantId 过滤条件（需修改每个 Tool 的查询逻辑，侵入式，暂未实施）
- **Week1-2 状态机/乐观锁/分布式锁/幂等键**：完全空白，涉及订单/售后核心流程与实体/DDL，风险高，未实施
- **Week4-6 RAG 向量检索**：HybridRetriever.retrieveByVector 是 TODO 返回硬编码假数据，PGVector 因缺 EmbeddingModel 不可用，BM25 纯内存无持久化

---

## [Week6-7] 2026-10-12 — 持久化分层记忆

**对应方案进度**：第二阶段 Week6-7「持久化分层记忆」（Task 19-23）
**里程碑**：Agent 记忆四层分级落地，USER/TEAM 长期记忆 MySQL 持久化，跨租户隔离 + 敏感信息脱敏

### 新增功能

| 文件 | 说明 |
|------|------|
| `erp-api/.../agent/memory/MemoryRepository.java` | 记忆 MySQL 持久化仓库（JdbcTemplate）：upsert / findById / deleteById / deleteExpired |
| `erp-api/src/test/java/cn/qihangerp/agent/memory/LayeredMemoryTest.java` | 分层记忆单元测试，10 用例 |

### 变更与说明

- `MemoryManager.java` 重写：Jackson 完整序列化整条记忆（替换手撸、只存 3 字段的写法）、接入 Repository、用 `Objects.equals` 修复租户隔离空指针、写入即脱敏
- `RagEvaluator.java` 补 `import lombok.Data`，修复 Week4-6 遗留编译硬伤
- 表结构以 Flyway `V1_13_0` 为权威（记忆键列名为保留字 `` `key` ``）
- 接手说明：Claude Code 的越界改动已全部备份并回退、被删通知模块已恢复（详见完成报告）

### 验证情况

| 项 | 结果 |
|----|------|
| 多模块编译 | BUILD SUCCESS（JDK 21 / Spring Boot 4.1） |
| LayeredMemoryTest | 10/10 通过 |
| SESSION/USER/TEAM 接口验证 | 见完成报告第六节手动步骤（需启动 Redis + MySQL） |

> 详见 `docs/Week6-7-Task19-23-分层记忆完成报告.md`

---

## [Week4-6] 2026-10-11 — RAG 检索能力 + 分层记忆骨架

**对应方案进度**：第二阶段 Week4-6「RAG 混合检索、离线评测、记忆骨架」
**里程碑**：RAG 全套检索 / 引用 / 离线评测代码落地，分层记忆骨架与建表脚本入库

### 新增功能

- **RAG 模块**：`BM25Retriever`、`HybridRetriever`、`Citation`、`CitationGenerator`、`RagController`、`RagResponse`、`RetrievalResult`、`RagEvaluator`、`RagEvaluationMetrics`
- **分层记忆骨架**：`MemoryLayer`（四层枚举）、`MemoryEntry`、`MemoryManager`、`MemoryController`、`SensitiveDataMasker`
- **Flyway 迁移**：`V1_12_0__create_agent_rag_tables.sql`、`V1_13_0__create_agent_memory_tables.sql`
- 独立配置 `application-agent.yml`（待合并）、`docker-compose.yml`

> 详见提交 `2d179a8c` 及 docs 下 Week4-6 完成报告

---

## [Week2-4] 2026-10-11 — 可信执行边界 + 评测闭环

**对应方案进度**：第一阶段 Week2-3「工具权限与审计系统」+ 第二阶段 Week3-4「评测闭环」
**里程碑**：Agent 工具调用正式纳入权限治理与指标监控，评测集数据入库

### 新增功能

#### 1. 工具权限与审计基础（受限自治层 · Task 5）

| 文件 | 说明 |
|------|------|
| `erp-api/.../agent/policy/AutonomyLevel.java` | 自治等级枚举（OBSERVE / SUGGEST / DRAFT / EXECUTE / ESCALATE 五级） |
| `erp-api/.../agent/policy/ToolPolicy.java` | 工具策略注解（声明等级、分类、是否写操作） |
| `erp-api/.../agent/gateway/ToolGateway.java` | **工具调用网关核心**：权限检查、租户隔离（merchantId 强制注入）、审计占位、ESCALATE 人工确认、并发控制 |
| `erp-api/.../agent/gateway/ToolExecutionContext.java` | Tool 执行上下文（用户 / 租户 / 工具元信息） |
| `erp-api/src/test/java/cn/qihangerp/agent/gateway/ToolGatewayTest.java` | 网关单元测试，9 个用例 100% 通过 |

> 网关设计要点：所有 AI Tool 调用统一走 ToolGateway，不能绕过策略直接调 Service；ESCALATE 级操作（取消订单、退款确认等高危动作）必须进入人工确认流程。

#### 2. 评测闭环（第二阶段 · Task 10 / 11）

| 文件 | 说明 |
|------|------|
| `erp-api/.../agent/evaluation/MetricsCollector.java` | 指标收集器：成功率 / 响应时间（平均、最小、最大）/ 调用次数 / 错误类型统计，兼容 Prometheus/Grafana |
| `erp-api/.../agent/evaluation/MetricsController.java` | 指标查询接口 |
| `erp-api/src/main/resources/db/migration/V1_11_0__create_agent_rag_evaluation_table.sql` | RAG 评测集表（含 category / difficulty / tags 维度） |
| `erp-api/src/main/resources/db/migration/V1_11_1__insert_agent_rag_evaluation_data.sql` | **100 条评测数据**（订单查询 30 / 退款流程 20 / 订单状态 15 / 库存管理 15 / 商品查询 10 / 采购管理 10） |

#### 3. 其他

- `erp-api/pom.xml`：新增 JUnit 5 测试依赖（`junit-jupiter 5.10.0`）
- `vue3/start-front.bat`：前端一键启动脚本

### 变更与说明

- `ToolGateway.java` 已集成 `MetricsCollector`，工具调用指标自动采集
- 数据库迁移脚本沿用 `V1_11_x` 版本命名，置于 `erp-api/src/main/resources/db/migration/`

### 验证情况

| 项 | 结果 |
|----|------|
| ToolGateway 单元测试 | ✅ 9/9 通过 |
| MetricsCollector | ⚠️ 核心逻辑已实现，测试用例待补充 |
| 100 条评测集数据 | ✅ 已入库（脚本） |

### 下一迭代待办（对应方案后续任务）

- Task 6-9：PolicyRegistry 策略注册表、TenantProjector 租户投影完善、ActionLedger 审计账本落地、ApprovalManager 审批流（前端确认弹窗 + 后端审批接口）
- Task 12-13：Trace 链路追踪、Bad Case 反馈收集机制
- 11 个 AI Tool 类逐一接入 `@ToolPolicy` 注解并改造为走网关

---

## [Week1-2] — 第一阶段：启航 ERP 核心后端加固

**对应方案进度**：第一阶段 Week1-2「启航 ERP 核心后端加固」
（详见提交 `2e6aae84`：状态机建模、乐观锁 version 字段、分布式锁、幂等键设计）
