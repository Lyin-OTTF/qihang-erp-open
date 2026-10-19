# 更新日志（Changelog）

> 本文件记录启航 ERP「Agent 工程魔改方案」的迭代进度，对应方案《功能更新与实施路径分析》中的阶段与任务编号。

---

## [RAG 真实 LLM 评测闭环] 2026-10-17

**目标**：修复评测集 chunk 标注与真实知识库不一致、LLM 配置默认项缺失、答案生成/裁判只使用 Top-5 等评测偏差。

### 变更

- 新增 `V1_30_0__real_rag_evaluation.sql`：91 条评测用例对齐真实 `sop_*` chunk，8 条明确标记为知识库未覆盖；新增逐用例答案与指标明细表。
- `RagEvaluator` 不再把启发式拼接当成真实生成；`LLM_STRICT` 模式下任一生成或裁判失败立即终止。
- BLEU-4 与 ROUGE-L 改为标准算法，评测报告增加 `LLM/HEURISTIC` 模式、模型名、BLEU/ROUGE 和逐用例明细。
- 生成与裁判上下文数量与评测 Top-K 对齐，可通过 `agent.evaluation.context-limit` 配置。
- `AiConfigServiceImpl.getDefaultConfig()` 在无默认项时回退到首个启用配置。
- 新增 `llm` 测试标签和 `rag-llm-eval` Maven profile，避免日常 CI 产生外部模型费用。

### 真实跑批

- 新增 23 条知识文档，补齐订单合并/拆分、操作日志、自动审核、换货、运费险、批量发货、会员营销和系统备份等缺口。
- rerank 候选从 Top-5 扩展到全部 20 个，并加入标题匹配、短语命中和向量分数。
- 99 条用例全部完成真实生成与裁判，`runId=33`，模型 `deepseek-v4-flash`。
- Recall@10 `0.9343`、MRR `0.8518`、NDCG@10 `0.8602`。
- Answer Relevancy `0.9377`、Correctness `0.7868`、Faithfulness `0.9700`。
- BLEU-4 `0.1671`、ROUGE-L `0.3144`、通过率 `84.85%`。

详细报告见 [RAG-真实评测报告.md](RAG-真实评测报告.md)。

---

## [凭据轮换：MySQL / PostgreSQL / Redis / JWT / 开放平台] 2026-10-17

**目标**：把 A 类（本机可操作）的泄露凭据全部轮换，旧值作废。

| 凭据 | 处理 |
|------|------|
| MySQL root | `ALTER USER` 轮换口令；14 个集成测试改为读取 `MYSQL_USERNAME`/`MYSQL_PASSWORD`（默认仍兼容 root/1234） |
| PostgreSQL postgres | `ALTER USER` 轮换口令；应用从 `PGVECTOR_PASSWORD` 读取 |
| Redis | 启用 `requirepass` 并用 `CONFIG REWRITE` 持久化到 `redis-service.conf`；3 个 Redis 集成测试改为读取 `REDIS_PASSWORD` |
| JWT | 轮换 `JWT_SECRET`（旧 token 全部失效，需要重新登录） |
| 开放平台客户端 | `sys_open_auth` 的 app_key/app_secret 已更新（旧客户端凭据失效） |

**本机配置**：新增仓库根目录 `application-local.yml`（已 gitignore，不提交、不打进 jar），
由 `application-agent.yml` 通过 `spring.config.import: optional:file:./application-local.yml` 加载；
部署环境没有该文件时继续使用环境变量。

**验证**：无环境变量（仅本地配置文件）启动成功，MySQL/Redis/PGVector 全部连通；`-Pall-tests` 231 个用例全绿。

---

## [本地 Ollama Embedding：真实向量化闭环打通] 2026-10-16

**目标**：不依赖付费云 Key，用本地模型把"真实 Embedding → pgvector → 语义检索"跑通。

### 变更

| 文件 | 说明 |
|------|------|
| `erp-api/pom.xml` | 新增 `spring-ai-starter-model-ollama` |
| `erp-api/src/main/resources/application-agent.yml` | Embedding 提供方改为 `${EMBEDDING_PROVIDER:ollama}`；新增 `spring.ai.ollama.*`（默认模型 `bge-m3`，base-url 可配）；百炼 embedding 改为 `${OPENAI_EMBEDDING_ENABLED:false}` 开关，避免两个 EmbeddingModel Bean 冲突 |
| `erp-api/src/test/.../OllamaEmbeddingPgVectorIntegrationTest.java` | 新增 `@Tag("it")` 集成测试：用本地 Ollama bge-m3 真实向量化中文 SOP → 写入 pgvector → 自然语言检索并断言排序；Ollama/PG 不可达时自动跳过；显式放宽读超时以适配 CPU 推理 |
| `docs/DEPLOYMENT.md` | 新增 3.4 本地 Embedding（Ollama）步骤 + 4.2 环境变量说明 |

### 验证

- `ollama pull bge-m3` → 1024 维向量（与 `vector_store(embedding vector(1024))` 一致）。
- `mvn -pl erp-api -am test -Pall-tests` → **231 个用例全绿**，含真实 Ollama embedding 检索用例。
- `AGENT_VECTOR_STORE_ENABLED=true` 真实启动成功：`PgVectorStore` 建表校验 + `PGVector向量检索器已启用（VectorStore已注入）`。

---

## [PGVector 启用态验证 / Prometheus 面板 / 集成测试进 CI] 2026-10-16

**目标**：补齐上一轮遗留的两项（PGVector 启用态、Grafana 面板），并把 pgvector 也纳入集成测试。

### PGVector 启用态

| 项目 | 结果 |
|------|------|
| 开关可运维化 | `agent.vector-store.enabled` 改为 `${AGENT_VECTOR_STORE_ENABLED:false}`，可用环境变量开关，默认仍不依赖 PG |
| 启用态启动 | `AGENT_VECTOR_STORE_ENABLED=true` 启动成功：连接 `pgvector/pgvector:pg16`（`erp_vector`），`PgVectorStore` 建表校验通过，日志 `PGVector向量检索器已启用（VectorStore已注入）` |
| 检索链路 | 新增 `PgVectorStoreIntegrationTest`（@Tag("it")）：用确定性 bigram 哈希 Embedding 桩对真实 PostgreSQL 做 insert + cosine 相似度检索并断言排序；PG 不可达时自动跳过 |

### 监控面板改为 Prometheus 数据源

| 文件 | 说明 |
|------|------|
| `erp-api/src/main/resources/grafana/agent-metrics-dashboard.json` | 由 Infinity(HTTP JSON) 改为 Prometheus 数据源：全局成功率、平均耗时、总调用/失败数、各工具调用量、性能明细表、失败分布、各工具 P95 耗时（8 个面板，JSON 已校验可导入） |

### CI

| 文件 | 说明 |
|------|------|
| `.github/workflows/ci.yml` | `integration-tests` Job 增加 `pgvector/pgvector:pg16` service container，使 pgvector 集成测试在 CI 中真实执行 |

---

## [集成测试真实跑通 / 启动验证 / CI 补全] 2026-10-16

**目标**：把"测试与启动"从"编译能过"提升到"真跑过"，并让集成测试进入 CI。

### 真实环境验证（本机 MySQL 8.0 + Redis）

| 项目 | 结果 |
|------|------|
| 全量测试（单元 + 集成） | `mvn -pl erp-api -am test -Pall-tests` → **229 个用例全绿**（含 50 线程库存并发、跨租户 E2E、RAG 真实跑批、RAG 评测、记忆持久化、审批、Trace、工作流） |
| 干净库引导 | 官方基线 dump（145 张表）+ 全部 `V1_*` 迁移在临时库按序执行成功，`rag_document`/`agent_rag_evaluation` 各 100 条 |
| 应用真实启动 | Flyway 应用到 `v1.29.0`；Redisson 连接 Redis；禁用的 PGVector 正确降级到 MySQL FT；知识库加载 100 chunk；PolicyRegistry 注册 17 个工具策略；`Started ErpApi in ~13s` |
| 监控端点 | `/actuator/health` → `{"status":"UP"}`；`/actuator/prometheus` → 200 + 44KB Prometheus 文本（含 `application="qihang-erp-agent"` 标签） |

### 修复

| 文件 | 说明 |
|------|------|
| `security/.../JwtAuthenticationTokenFilter.java` | 白名单补 `/actuator/health`、`/actuator/prometheus`：此前该过滤器对白名单外请求直接短路返回"授权过期"JSON，导致 `SecurityConfig` 的 `permitAll` 根本不生效，Prometheus 抓取拿不到数据 |

### 工程化

| 文件 | 说明 |
|------|------|
| `.github/workflows/ci.yml` | 拆成两个 Job：`unit-tests`（默认单元测试）与 `integration-tests`（MySQL/Redis service container + 基线导入 + 迁移 + `-Pall-tests`） |
| `erp-api/src/test/.../MetricsCollectorMicrometerTest.java` | 新增单测：验证工具调用写入 Micrometer counter/timer |
| `docs/DEPLOYMENT.md` | 新增 5.5 测试章节（三种测试命令、集成测试连接参数、干净库引导步骤） |

---

## [RAG接入对话 / 监控真实化 / 工程化补强] 2026-10-16

**目标**：把上一轮评审里"名实不完全相符"和"工程化短板"的部分落到实处，而不是继续堆模块。

### RAG 接入对话主链路

| 文件 | 说明 |
|------|------|
| `erp-api/.../serviceImpl/ai/KnowledgeTools.java` | 新增只读工具 `searchKnowledge`（OBSERVE 级）：模型遇到流程/规则类问题时先检索知识库，返回带 chunkId 的引用片段 |
| `erp-api/.../serviceImpl/ChatService.java` | 工具列表接入 `knowledgeTools`；新增会话历史预算（最近 20 条 / 6000 字符） |

### 监控真实化

| 文件 | 说明 |
|------|------|
| `erp-api/pom.xml` | 新增 `spring-boot-starter-actuator` + `micrometer-registry-prometheus` |
| `erp-api/.../evaluation/MetricsCollector.java` | 工具调用同时写入 Micrometer（counter + timer 带 P50/P95/P99） |
| `erp-api/src/main/resources/application-agent.yml` | 暴露 `/actuator/prometheus`；`agent.tool.duration` 开分位直方图 |
| `security/.../SecurityConfig.java` | 匿名放行 `/actuator/health`、`/actuator/prometheus`（其余 actuator 端点仍需登录） |

### 工作流通知真实化 + 一致性修复

| 文件 | 说明 |
|------|------|
| `erp-api/.../agent/workflow/AgentWorkflowService.java` | `notify_customer` 节点改为调用 `NotifierService.notifyAll`（飞书/钉钉/企微 Webhook），无渠道时降级为日志 |
| `erp-api/.../agent/graph/CheckpointManager.java` | `saveCheckpoint` 串行化，消除节点并发保存时的 `MAX(version)+1` 竞态 |
| `erp-api/.../agent/approval/ApprovalService.java` | `getApprovedApproval` 与 `isApproved` 共用同一套过滤（状态 + 过期 + 参数摘要） |
| `erp-api/.../agent/gateway/ToolGateway.java` | 回写审批状态时传入当前参数，保证命中的是同一张审批单 |

### 工程化与代码卫生

| 文件 | 说明 |
|------|------|
| `.github/workflows/ci.yml` | 新增 GitHub Actions：`mvn -B -pl erp-api -am test`（单元测试，集成测试默认排除） |
| `service/.../service/statemachine/**` | 删除与新状态机重复的历史遗留状态机包（7 个类 + 3 个单测 + 1 个集成测试） |
| `erp-api/src/test/.../KnowledgeToolsTest.java` | 新增知识检索工具单测（空结果提示、租户透传、引用格式化） |
| `docs/AI-AGENT-IMPLEMENTATION.md` | 已知限制按事实更新（RAG 已接入对话、监控已接 Prometheus、记忆仍为 KV、DAG 为自研实现等） |

**验证**：`mvn -pl erp-api -am test` 全量单元测试通过。

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
