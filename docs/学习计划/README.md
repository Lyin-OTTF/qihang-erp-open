# 启航 ERP 源码攻坚与面试手册

> 适用对象：以 vibecoding 为主、能改能跑但说不清底层实现，需要在 1-2 周内把项目讲到面试官满意的同学。
> 覆盖仓库：`D:/AAA.idea.code/qihang-erp-open`（HEAD `2e8342c0`，704 次提交）
> 编写时间：2026-10-18

---

## 一、先定策略：不通读代码，只建立"可控的认知"

这个仓库有 **1179 个 Java 文件、7 个 Maven 模块、25 个 Flyway 迁移、40 个测试类**。
通读既不可能也没必要。面试考的不是"你读过多少文件"，而是三件事：

1. **你能不能用 3 分钟把系统讲清楚**（架构 + 数据流）；
2. **你能不能把一个技术决策讲出取舍**（为什么这么做、不这么做会怎样）；
3. **你有没有踩过坑并留下证据**（问题 → 定位 → 修复 → 验证）。

所以本手册只做三件事：

- **划范围**：明确告诉你哪些必读（P0，约 30 个文件）、哪些知道存在即可（P1）、哪些直接跳过（P2）。
- **给讲法**：每个模块都附"面试官会怎么问 + 你该怎么答"。
- **给证据**：所有结论都落到具体文件与行为，避免背概念。

### 认知框架：三纵三横

```
            业务域            一致性              智能体
          (订单/库存)      (并发/事务/幂等)      (Tool/RAG/DAG)
数据流   拉单→入库→出库    库存三量→流水        Prompt→Tool→DB
控制流   Controller→Service  状态机→校验         Gateway→审批→执行
失败流   平台接口失败       扣减失败→回滚        工具失败→降级/补偿
```

任何一个模块，只要你能说清它在"三纵三横"里的位置，就不会迷路。

---

## 二、七天日程（建议每天 3.5 小时，可压缩到 5 天）

| 天 | 主题 | 读什么 | 当天产出（必须动手） |
|----|------|--------|---------------------|
| D1 | 地图 + 跑起来 | 本手册 01、根 `pom.xml`、`application-agent.yml` | 画出模块依赖图 + 启动成功截图 |
| D2 | 业务主线 | 02：拉单、入库、出库 | 画出"采购入库→库存→流水"时序图 |
| D3 | 库存一致性 | 02 后半 + 03 前半 | 手写一遍 `changeStock` 伪代码并解释每步 |
| D4 | 状态机与并发 | 03 后半 + 相关测试 | 白板画出订单状态迁移图，讲清 version 的边界 |
| D5 | Agent 运行时 | 04 | 画出 ToolGateway 八步流水线时序图 |
| D6 | RAG + DAG + 记忆 | 05、06 | 手算一遍 RRF 分数；讲清三级降级 |
| D7 | 迭代史 + 面试 | 07、08 | 3 分钟口述录音 3 遍；30 题自测 |

> 时间更紧的压缩法：D1+D2 合并（只读主链路）、D5+D6 合并（只读网关 + RAG 主流程），
> **但 D3/D4 不能省**——库存与并发是 Java 后端面试的主战场。

### 如果只剩 2 天（保命版）

| 时间 | 只学这些 | 必须能说 |
|------|----------|----------|
| D1 上午 | README + 01 + 02 第 5 节 | 模块图、`changeStock` 四步、为什么不会超卖 |
| D1 下午 | 03 第 1-3 节 + 07 事故 5/6/9 | 状态机表、乐观锁边界、审批五连断 |
| D1 晚上 | 04 第 1-4 节 | @Tool 为什么绕过 AOP、网关八步 |
| D2 上午 | 05 第 1-4 节 + 06 第 1-3 节 | RRF 手算、三级降级、DAG 补偿 |
| D2 下午 | 08 全部 | 3 分钟口述 + 35 题过一遍 |
| D2 晚上 | 录音复述 + 画图 | 五张图默画，卡住的回原文定位 |

放弃项：RAG 评测细节、记忆四层、DAG 源码、Spring/MySQL 八股（这些靠平时积累）。

### 每天固定动作（30-30-30 法）

1. **30 分钟读码**：只读当天清单里的文件，遇到不认识的类先记下来，不追。
2. **30 分钟画图/写伪代码**：合上代码，凭记忆画；画不出再回去看。
3. **30 分钟复述**：对着空气讲一遍，录音；听回放，删掉所有"大概是""应该是"。

---

## 三、阅读优先级（这是本手册最重要的表）

### P0：必须能脱稿讲（约 30 个文件）

| 主题 | 文件 |
|------|------|
| 工程结构 | 根 `pom.xml` |
| 认证链路 | `security/.../JwtAuthenticationTokenFilter.java`、`SecurityConfig.java`、`LoginUser.java` |
| 库存核心 | `service/.../impl/OGoodsInventoryServiceImpl.java`、`mapper/.../OGoodsInventoryMapper.java`、`model/.../bo/InventoryChangeCommand.java` |
| 入库/出库 | `service/.../impl/ErpStockInServiceImpl.java`、`ErpStockOutServiceImpl.java` |
| 状态机 | `service/.../statemachine/OrderStateMachine.java`、`RefundStateMachine.java`、`TransitionValidator.java` |
| 对账 | `service/.../impl/StockReconcileServiceImpl.java`、`model/.../entity/StockReconcileDiff.java` |
| 工具网关 | `erp-api/.../agent/gateway/ToolGateway.java`、`GatewayToolCallbackFactory.java`、`GatewayToolCallback.java` |
| 策略 | `erp-api/.../agent/policy/AutonomyLevel.java`、`PolicyRegistry.java`、`ToolPolicy.java` |
| 审批/审计 | `erp-api/.../agent/approval/ApprovalService.java`、`agent/audit/ActionLedgerService.java` |
| 租户 | `erp-api/.../agent/tenant/TenantInterceptor.java`、`TenantContext.java` |
| 对话编排 | `erp-api/.../erp/serviceImpl/ChatService.java` |
| RAG | `agent/rag/HybridRetriever.java`、`BM25Retriever.java`、`Reranker.java`、`RagEvaluator.java` |
| DAG | `agent/dag/DAGScheduler.java`、`FailurePropagator.java`、`ExecutionContext.java` |
| 记忆 | `agent/memory/MemoryManager.java`、`MemoryLayer.java` |

### P1：知道职责即可（面试被问到能指路）

- `open-sdk/**`：7 个电商平台 SDK（淘宝/京东/拼多多/抖店/快手/微信小店/小红书），**不要读**，只需知道它是"适配器层"。
- `model/**` 的 314 个实体：只读 `ShopOrder`、`OOrder`、`OGoodsInventory`、`OGoodsInventoryOperation`、`StockReconcileDiff`。
- `service/**` 其余 190 个类：知道"采购/售后/发货/物流"各有一条 Service，不逐行读。
- `vue3/**`：前端，面试基本不问；只需知道有 `ChatWidget` 对接 SSE。
- `.github/workflows/ci.yml`：知道有单测/集成测试两个 Job 即可。

### P2：直接跳过

- `build*.log`、`test*.log`、`compile_check*.log`（历史构建日志，共 20+ 个文件）。
- `docs/` 下的旧方案与中间报告（除本手册 07 引用的几份）。
- `open-sdk` 内部每个平台的 request/response 模型。

---

## 四、五张必须能默画的图

面试时手边没有代码，能画图就赢一半。五张图分别是：

1. **模块依赖图**（01 篇）：7 个模块谁依赖谁，为什么 `service` 不依赖 `erp-api`。
2. **采购入库时序图**（02 篇）：Controller → Service → `changeStock` → 原子 SQL → 流水。
3. **订单状态迁移图**（03 篇）：0/1/2/3/11/12/13/21/101 的合法边。
4. **ToolGateway 八步流水线**（04 篇）：Trace → Policy → 租户 → 幂等 → 锁 → 审批 → 执行 → 审计。
5. **RAG 检索链路**（05 篇）：PGVector → MySQL FULLTEXT → BM25 的降级，与 RRF/Rerank 的位置。

---

## 五、本手册文件索引

| 文件 | 内容 | 对应天 |
|------|------|--------|
| `01-地图篇-架构与模块.md` | 三个时代、模块职责、请求链路、代码规模 | D1 |
| `02-业务篇-订单与库存一致性.md` | 拉单、入库、出库、库存三量、流水、对账 | D2-D3 |
| `03-并发篇-状态机锁与幂等.md` | 状态机、乐观锁边界、分布式锁、幂等三层 | D3-D4 |
| `04-Agent篇-工具网关与审批审计.md` | @Tool 原理、网关八步、五级自治、审批、审计、租户 | D5 |
| `05-RAG篇-混合检索与评测.md` | BM25、向量、RRF、Rerank、Recall/MRR/NDCG | D6 |
| `06-DAG与记忆篇.md` | 调度循环、失败传播、补偿、Checkpoint、四层记忆 | D6 |
| `07-迭代史与事故库.md` | 三阶段演进、15 个真实事故的现象/根因/修复 | D7 |
| `08-面试题库与简历对齐.md` | 3 分钟讲法、30 题、数字校准、能力边界 | D7 |

---

## 六、两个项目的总体节奏（本手册只覆盖第一阶段）

| 阶段 | 项目 | 目标 | 产出 |
|------|------|------|------|
| 第一阶段（本手册，7 天） | 启航 ERP | 讲清"业务底座 + Agent 治理" | 能答 30 题、画 5 张图 |
| 第二阶段（后续，5 天） | PaiAgent | 讲清"工作流编排平台 + Agent 执行模型" | 能对比两套编排、讲 HITL |
| 第三阶段（1 天） | 两者串联 | 形成"一个做治理、一个做编排"的叙事 | 3 分钟总述 |

**为什么先启航**：它是"业务 + 一致性 + 权限"的主战场，面试问得最深；
PaiAgent 是"编排 + 状态图"，适合作为差异化亮点，但需要启航的并发/事务基础打底。

PaiAgent 阶段会重点讲：LangGraph4j 的状态图/条件边/checkpoint、HITL 中断续跑、
双引擎（自研 DAG vs LangGraph4j）的失败语义对比、以及"为什么平台要支持多模型/多工具"。
**先不要碰**，等启航的 D3/D4 学完再开始，否则两套概念会打架。

---

## 七、验收标准（达不到就不要投）

- [ ] 能在白板上 5 分钟画出模块图 + 入库时序图，并解释每个箭头。
- [ ] 能不看代码写出 `changeStock` 的四步，并说清"为什么回读不会读到脏数据"。
- [ ] 能说清 `version` 乐观锁在哪些路径**失效**，以及为什么仍然保留它。
- [ ] 能解释"Spring AI 的 @Tool 为什么绕过了 AOP"，以及网关是怎么接进去的。
- [ ] 能解释 RRF 公式，并手算两个列表融合后谁排第一。
- [ ] 能讲 3 个自己踩过的坑（现象、定位、修复、验证），每个不超过 2 分钟。
- [ ] 30 题自测能答上 24 题以上（08 篇）。

---

## 八、一条重要提醒：区分"上游已有"与"我做的"

这个项目是 Fork 二次开发。面试官最容易追问的一句是：
**"这些是你写的，还是原本就有的？"**

诚实且专业的答法是"分层归因"：

- 上游提供：电商业务底座（订单/商品/采购/库存/发货）、JWT+RBAC、Vue3 前端、平台 SDK。
- 我做的：Agent 运行时治理（网关/策略/审批/审计/Trace）、库存一致性重构（单一入口+原子更新+流水+对账）、状态机下沉、RAG 真实化与评测、DAG 并行与补偿。
- 我修的是：上游"能跑但不可控"的部分（库存先查后写、状态 if/else、流水不可追溯）。

08 篇有一张完整的"能力边界表"，背下来，别把上游能力说成自己的——这是减分最快的方式。

---

## 九、开始之前：把项目跑起来

```bash
# 1. 依赖：MySQL 3306 / Redis 6379 /（可选）PG 5432
# 2. 本机凭据放 application-local.yml（仓库根 + erp-api/ 各一份，硬链接同一 inode）
# 3. 启动（必须带 agent profile，否则 application-local.yml 不会被 import）
mvn -pl erp-api -am spring-boot:run -Dspring-boot.run.profiles=dev,agent

# 4. 验证
curl http://localhost:8080/actuator/health
```

启动三坑（按出现概率排序）：

1. **profile 不全**：`config.import` 只写在 `application-agent.yml` 里，
   只带 `dev` 启动会丢掉本机凭据，报 `using password: NO`。
2. **本地配置放错目录**：`optional:file:./application-local.yml` 相对**进程工作目录**解析，
   `mvn spring-boot:run` 和 IDEA 默认都在 `erp-api/`，所以该文件要放两份（根目录 + `erp-api/`）。
3. **没有 PG**：确认 `AGENT_VECTOR_STORE_ENABLED=false`，让 `PgVectorStore` 不创建，
   否则会连不上 PG 导致启动失败（事故 3）。

> 若 AI 自动配置因缺少 api-key 阻塞启动，排除对应的 `*AutoConfiguration`
> （项目运行时的模型调用走 `AiConfig` 自建客户端，不依赖自动装配）。

其余启动类问题见 07 篇事故 1-3。

> 跑不起来也能学：本手册所有代码片段都可独立阅读。但**强烈建议跑起来**——
> 面试时说"我本地跑通过、用 curl 验过接口"和"我只看了代码"是两个可信度。

---

## 十、怎么用我（讲解师的使用说明）

这本手册是"教材"，不是"聊天记录"。真正的学习发生在你和我之间：

| 你的动作 | 我的回应 |
|----------|----------|
| "讲一下 02 篇的 `changeStock`，逐行" | 我按行拆解，补上下文、补反例、补面试问法 |
| "我看不懂 `GatewayToolCallback` 的参数解析" | 我从 JSON Schema 讲到反射调用，画调用栈 |
| "检查我画的状态机图对不对" | 我指出缺的边、错的语义，并解释为什么 |
| "按 08 篇模拟面试，问我 10 题" | 我扮演面试官，连续追问三层，最后给评分 |
| "这段代码面试官会怎么追问" | 我给 3 层追问链和每层的答法 |
| "我只有 2 天了，砍到最小" | 我给 2 天压缩版（D1 地图 + D3 库存 + D4 状态机 + D5 网关 + 08 题库） |

**学习节奏建议**：每读完一篇，先别急着下一篇，用一句话向我复述；
复述不出来，说明那一篇白读了。

**不要做的事**：不要让我"把整个项目总结一遍"——那会得到一份你没消化的摘要。
要问具体问题："这个类为什么这么设计""这一步去掉会怎样""面试官追问怎么办"。

