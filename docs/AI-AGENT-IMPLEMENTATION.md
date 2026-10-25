# 启航ERP AI Agent 二次开发实现文档

> 本项目基于启航电商ERP开源版进行深度二次开发，围绕「AI原生售后工单Agent」构建完整的智能体能力体系。
> 开发周期：Week1 - Week10，迭代式推进，从基础底座到生产级加固。

---

## 一、项目定位与目标

### 1.1 核心目标

在启航ERP原有电商业务底座之上，构建一个**可自主决策、可审计、可评测、可恢复**的售后工单智能体：

- **自主决策**：Agent 通过 Tool 函数调用直接操作订单、售后、库存等业务，无需人工中转
- **安全可控**：四级自治等级（OBSERVE/SUGGEST/EXECUTE/ESCALATE）+ 审批流 + 租户隔离，写操作全程可管控
- **全链路审计**：每一次 Tool 调用、状态变更、审批决策都落库可追溯
- **持续优化**：离线评测 + BadCase 反馈闭环，RAG 召回率和回答质量可量化提升
- **故障恢复**：DAG 工作流支持中途 checkpoint 和断点续跑，失败自动补偿回滚

### 1.2 技术栈

| 层级 | 技术选型 | 版本 |
|------|----------|------|
| 基础框架 | Spring Boot | 4.1.0 |
| AI框架 | Spring AI | 2.0.0 |
| 大模型 | DeepSeek（对话）+ 阿里云百炼（Embedding） | - |
| 持久化 | MySQL 8.0 | 业务数据 + Agent元数据 |
| 向量存储 | PostgreSQL + pgvector | 1024维 HNSW索引 |
| 缓存/锁 | Redis | 分布式锁 + 热点缓存 + 会话记忆 |
| ORM | MyBatis-Plus | 3.5.16 |
| 监控 | Micrometer + Grafana | 8个业务面板 |
| 数据库迁移 | Flyway | 版本化表结构管理 |

---

## 二、核心架构

### 2.1 整体分层

```
┌─────────────────────────────────────────────────────────┐
│                    接入层 (Chat/SSE/API)                  │
│         ChatController │ RagController │ ToolPolicy      │
├─────────────────────────────────────────────────────────┤
│                    智能体编排层                            │
│    ChatService │ ToolGateway │ AgentWorkflowService      │
│    (对话编排)    (工具网关)     (DAG工作流)                │
├─────────────────────────────────────────────────────────┤
│                    能力层                                  │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │ 12个业务 │ │ RAG混合  │ │ 四层记忆 │ │ 审批流   │   │
│  │ Tool     │ │ 检索     │ │ 系统     │ │ 引擎     │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │ 状态机   │ │ 乐观锁   │ │ 分布式锁 │ │ 幂等键   │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
├─────────────────────────────────────────────────────────┤
│                    观测与评测层                            │
│    ActionLedger审计 │ Metrics指标 │ RagEvaluator评测     │
│    BadCase闭环 │ Trace链路追踪 │ Grafana面板             │
├─────────────────────────────────────────────────────────┤
│                    基础设施层                              │
│         MySQL │ PostgreSQL(pgvector) │ Redis             │
└─────────────────────────────────────────────────────────┘
```

### 2.2 关键设计决策

1. **ToolGateway 统一入口**：所有 Tool 调用必须经过网关，统一处理租户校验、自治等级判定、审批拦截、审计记录、分布式锁、幂等控制
2. **状态机下沉 service 模块**：订单/售后状态迁移规则与 erp-api 解耦，通过 Spring 事件驱动审计落库
3. **RAG 三级降级**：PGVector（真向量语义）→ MySQL FULLTEXT（全文相关性）→ BM25（内存倒排索引），任意一层故障自动降级
4. **记忆四层架构**：Working（对话内）→ Session（会话级）→ User（用户级）→ Team（商户级），TTL 逐层递增，MySQL 持久化

---

## 三、Week1-10 迭代路线

### Week1-2：基础底座
- 订单/售后状态机：合法迁移规则校验，非法迁移直接拒绝
- 乐观锁：库存更新、状态变更带 version 字段，并发冲突自动重试
- 幂等键：Tool 调用防重复执行，相同 traceId+toolName 只执行一次

### Week2-3：工具网关与审计
- ToolGateway：12个业务Tool统一入口，租户隔离、自治等级、审批、审计、锁、幂等一站式处理
- ActionLedger：全链路审计表，记录每次Tool调用的输入输出、耗时、结果、审批状态
- @ToolPolicy 注解：声明式配置每个Tool的自治等级、分类、是否需要审批

### Week3-4：评测闭环
- RagEvaluator：Recall@K、MRR、命中率等指标自动计算
- LLM-as-judge：用大模型对回答质量打分（相关性、准确性、完整性）
- BadCase：评测失败案例自动入库，支持人工标注后重新训练/优化
- 100条评测集：覆盖订单查询、售后处理、库存查询等典型场景

### Week4-6：RAG真实化
- PGVector手动配置：排除自动配置，手动建DataSource实现分库隔离
- BM25内存倒排索引：中文bigram分词，租户分片
- RRF融合：向量检索 + BM25 结果用 Reciprocal Rank Fusion 融合
- Rerank重排序：查询词覆盖度+位置+密度加权，Top-N候选二次排序
- 知识库加载：110个SOP文档chunk自动索引

### Week6-7：记忆层
- MemoryRepository：JdbcTemplate upsert，按租户+层级隔离
- MemoryManager：四层记忆 + Jackson序列化 + 敏感字段脱敏
- Token预算控制：每层记忆450字符预算，超预算倒序截断，防止SystemMessage无限膨胀

### Week7-10：生产级加固
- DAG工作流：售后处理流程并行编排，失败自动补偿回滚，中途checkpoint断点续跑
- Trace链路追踪：agent_trace表集中存储，完整记录Agent决策过程
- 热点商品缓存：GoodsCacheService，Redis缓存高频商品查询
- 分布式锁：StringRedisTemplate手写，SET NX + UUID令牌防误删 + 等待重试
- 审批流：ApprovalService，过期自动失效、参数摘要匹配防篡改、通过后自动落Ledger
- 监控：Micrometer 指标写入 MeterRegistry，Prometheus 抓取 `/actuator/prometheus`；`grafana/agent-metrics-dashboard.json` 为 Prometheus 数据源面板（Tool调用量、成功率、耗时P95、错误分布等 8 项）
- 库存并发测试：50线程直连MySQL真实集成测试，验证乐观锁和原子更新

---

## 四、核心模块详解

### 4.1 ToolGateway 工具网关

**职责**：所有AI Tool调用的唯一入口，统一处理横切关注点。

**处理流程**：
```
请求进入 → 租户校验(fail-closed) → 自治等级判定
    → 需要审批? → 审批通过/拒绝 → 分布式锁获取
    → 幂等键检查 → 执行Tool → 审计落库 → 释放锁
```

**关键特性**：
- 租户缺失直接拒绝（fail-closed），不默认放行
- 无策略默认 ESCALATE（需要人工审批），不默认放行
- 内存锁释放异常安全，finally块确保释放
- 审批校验包含过期判断 + 参数摘要匹配（防止审批通过后篡改参数）

### 4.2 RAG 混合检索

**检索流程**：
```
用户问题 → 顺序执行 [PGVector向量检索] + [BM25关键词检索]
    → RRF融合排序 → Rerank重排序(Top-N) → 返回Top-K
```

**接入对话**：`KnowledgeTools.searchKnowledge`（OBSERVE 级只读工具）把 RAG 接入聊天主链路——
模型遇到"流程/规则/规范"类问题时先检索知识库，工具返回带 chunkId 的引用片段，模型据此作答并标注引用编号。
（`/api/agent/rag/**` 仍保留为独立检索/评测接口。）

**Embedding**：默认走本地 Ollama（`OLLAMA_EMBEDDING_MODEL=bge-m3`，1024 维，无需云 Key）；
也可切回阿里云百炼（`EMBEDDING_PROVIDER=openai` + `OPENAI_EMBEDDING_ENABLED=true` + `DASHSCOPE_API_KEY`）。

**三级降级**：
1. PGVector（真向量语义，1024维 HNSW索引，COSINE距离）
2. MySQL FULLTEXT（全文相关性，ngram分词器）
3. BM25（内存倒排索引，中文bigram分词）

**BM25修复**：同一chunkId重复索引时，先扣除旧词频贡献再加新贡献，totalDocs仅新文档才递增，避免统计指标膨胀。

### 4.3 四层记忆系统

| 层级 | 作用域 | TTL | 存储 |
|------|--------|-----|------|
| Working | 单次对话内 | 1小时 | 内存 |
| Session | 同一会话 | 1天 | MySQL |
| User | 同一用户 | 30天 | MySQL |
| Team | 同一商户 | 90天 | MySQL |

**特性**：
- 敏感字段自动脱敏（手机号、身份证、地址等）
- Token预算控制，每层独立预算，超预算保留最新记忆
- 记忆注入SystemMessage，Agent可感知历史上下文和用户偏好

### 4.4 DAG工作流

**售后处理工作流节点**：
```
fetch_order ─┐
fetch_refund ─┼→ validate_transition → process_refund → update_memo → notify_customer
fetch_inventory ─────────────────────────────────────────→ audit_log（独立分支）
```

**特性**：
- 有界线程池并行执行无依赖节点
- 失败自动传播，取消受影响分支
- 已成功节点自动补偿回滚（逆拓扑顺序）
- 每个节点完成后保存checkpoint，支持中途崩溃后断点续跑
- process_refund同步更新订单为12(退款中)，新售后单按0→2→3分步迁移
- update_memo检查是否已追加AI标记，避免resume重复执行时重复追加

### 4.5 审批流

**审批粒度**：traceId + toolName + args摘要，三重匹配防止越权执行。

**关键特性**：
- 审批有过期时间，过期自动失效
- 审批通过后校验args摘要，参数被篡改则拒绝执行
- 审批查询、批准、拒绝均绑定登录租户，跨租户ID不可见也不可操作
- 售后工作流REST执行需先申请审批，凭已批准且参数匹配的approvalId执行
- 审批通过后自动落ActionLedger审计
- markFailed清理审批人引用，避免内存泄漏
- @Scheduled定时清理过期审批

---

## 五、安全设计

### 5.1 权限体系

- **租户隔离**：所有查询强制带merchant_id过滤，ToolGateway fail-closed校验
- **自治等级**：OBSERVE（只读）/ SUGGEST（建议）/ EXECUTE（直接执行）/ ESCALATE（需审批）
- **管理员权限**：ToolPolicy配置、RAG索引管理等敏感接口要求平台管理员（user_type=00）
- **会话归属校验**：ChatController先校验会话归属再保存消息，防止越权读写他人会话

### 5.2 并发安全

- **乐观锁**：库存更新、状态变更带version，并发冲突抛异常由上层重试
- **分布式锁**：SET NX原子获取 + UUID令牌防误删 + 等待重试机制
- **唯一索引NULL问题**：AgentToolPolicy的merchant_id IS NULL时MySQL唯一索引不约束，updatePolicy加synchronized保证"查+插"原子性

### 5.3 密钥管理

- **所有密钥通过环境变量注入**，yml配置文件只保留`${ENV_VAR:}`占位符
- `application-example.yml`提供配置模板，不含真实密钥
- `.gitignore`忽略`.env`、`application-local.yml`等本地密钥文件
- 早期提交中泄露的旧密钥已全部吊销作废
- 个人项目未做全量Git历史改写（强制push风险大于收益），生产环境建议使用BFG Repo-Cleaner清洗历史

---

## 六、测试与验证

### 6.1 测试体系

| 类型 | 数量 | 说明 |
|------|------|------|
| 单元测试 | 123+ | 覆盖状态机、记忆层、RAG检索、ToolGateway、审批流等核心模块 |
| 集成测试 | 14 | 直连MySQL/Redis的真实环境测试，@Tag("it")默认跳过 |
| 并发测试 | 1 | 50线程库存并发更新，验证乐观锁和原子性 |

### 6.2 RAG评测结果

- 评测集：100条（24条核心 + 76条扩展）
- Recall@10：88.89%
- MRR：86.78%
- 知识库：110个SOP文档chunk

### 6.3 启动验证

- 应用启动时间：约7-8秒
- Tomcat端口：6149
- 激活Profile：dev, agent
- Flyway自动迁移：27个版本脚本

---

## 七、已知问题与后续迭代

### 7.1 已知限制

1. **集成测试已在CI**：`@Tag("it")` 的集成测试通过 MySQL/Redis service container 在 `integration-tests` Job 中执行；本地真实环境全量 `-Pall-tests` 229 个用例全绿
2. **Rerank为轻量级实现**：基于查询词覆盖度+位置+密度加权，未使用交叉编码器或 LLM 重排序
3. **BM25为内存实现**：应用重启后索引丢失，需重新加载知识库；多实例部署各持一份索引，生产环境建议迁移到 Elasticsearch
4. **记忆是 KV 而非语义记忆**：分层/TTL/脱敏/租户隔离已实现，但召回是键值读取，没有向量语义召回
5. **DAG/条件边是自研简化实现**：不是方案文档里提到的 PaiAgent LangGraph4j；二者不要混为一谈
6. **工作流通知依赖渠道配置**：走 `SysAlertChannel` 的飞书/钉钉/企微 Webhook，未配置渠道时只记录日志
7. **云 Embedding 未验证**：已用本地 Ollama + bge-m3 验证"真实向量化 → pgvector 写入 → 语义检索"闭环（`OllamaEmbeddingPgVectorIntegrationTest`）；阿里云百炼（DashScope）路径未跑过，需要真实 Key
8. **Flyway空占位脚本**：V1_11_1/V1_12_0/V1_16_0/V1_17_0/V1_21_0 为纯注释占位，可在确认 flyway_schema_history 后清理

### 7.2 后续迭代方向

1. **Rerank升级**：接入bge-reranker或LLM-as-reranker，提升检索精度
2. **BM25持久化**：迁移到Elasticsearch或Tantivy，支持大规模知识库
3. **多模态支持**：支持图片、PDF等非文本内容的理解和检索
4. **Agent规划能力**：引入ReAct/Plan-and-Execute框架，支持复杂多步任务自主规划
5. **CI/CD自动化**：单元测试已接入 GitHub Actions；后续用 Testcontainers 把集成测试也纳入 CI
6. **配置基线固化**：生产环境部署前统一梳理配置项与密钥来源，用配置模板 + 环境变量收敛，避免历史配置文件被误用
7. **记忆语义化**：给分层记忆加向量召回，从"键值记忆"升级为"语义记忆"
