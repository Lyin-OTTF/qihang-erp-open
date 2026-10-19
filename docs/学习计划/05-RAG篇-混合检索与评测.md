# 05 RAG 篇：混合检索、融合排序与离线评测

> 目标：能讲清"为什么不能只用向量库"，能手算 RRF，能解释每个评测指标的含义与局限。
> 预计用时：D6 上午（约 3 小时）

---

## 1. 先问一个正确的问题：RAG 到底在解决什么

不是"让模型知道更多知识"，而是两件很具体的事：

1. **事实锚定**：回答必须能指到某个 chunk（`citation`），否则就是编。
2. **可评测**：换一版检索策略，能不能用数字说明它变好了？

所以这个项目的 RAG 有两个闭环：

```
写入：SQL/文档 → chunk → (embedding) → pgvector / BM25 索引
读取：query → 多路召回 → 融合 → 精排 → Top-K → 拼上下文 → LLM → 带引用回答
评测：评测集 → 跑检索/生成 → Recall@K / MRR / NDCG / LLM-judge → 报告 → BadCase
```

**读取链路（这张要能默画）**：

```
                    query
                      │
        ┌─────────────┴─────────────┐
        ▼                           ▼
┌───────────────┐           ┌───────────────┐
│ 向量召回       │           │ BM25 召回      │
│ PG → MySQL FT │           │ 内存倒排(bigram)│
│ → 空(降级)     │           │ 按租户分片      │
└───────┬───────┘           └───────┬───────┘
        │ topK*2                    │ topK*2
        └─────────────┬─────────────┘
                      ▼
              ┌───────────────┐
              │ RRF 融合       │  只用排名，不用分数
              └───────┬───────┘
                      ▼
              ┌───────────────┐
              │ Rerank 精排    │  规则加权（可解释）
              └───────┬───────┘
                      ▼
                  Top-K + chunkId
                      │
                      ▼
              SystemMessage + 引用 → LLM
```

> 面试话术：
> "我不是'接了个向量库'。我先定义了评测集和指标，再倒推需要哪些检索能力。
> 没有评测的 RAG，改参数全靠感觉。"

---

## 2. 三级降级：为什么不是"向量库一把梭"

`HybridRetriever.retrieveByVector`：

```java
// 一级：PGVector 真向量检索
if (pgVectorRetriever != null && pgVectorRetriever.isAvailable()) {
    List<RetrievalResult> r = pgVectorRetriever.retrieve(query, topK, merchantId);
    if (!r.isEmpty()) return r;
}
// 二级：MySQL FULLTEXT 全文检索
if (mysqlFullTextRetriever != null && mysqlFullTextRetriever.isAvailable()) {
    List<RetrievalResult> r = mysqlFullTextRetriever.retrieve(query, topK, merchantId);
    if (!r.isEmpty()) return r;
}
// 三级：都不可用 → 返回空，整体退化为 BM25-only
return Collections.emptyList();
```

设计动机（面试必须能讲）：

| 层级 | 能力 | 部署成本 | 失败模式 |
|------|------|----------|----------|
| PGVector | 语义相似（"退款"≈"退货"） | 要装 PG + pgvector 扩展 + embedding 模型 | PG 挂 / 模型挂 |
| MySQL FULLTEXT | 关键词相关性 | 现有 MySQL 加索引 | 中文分词差、语义弱 |
| BM25 内存 | 词频统计，零依赖 | 进程内 | 重启要重建、占内存 |

**关键点：降级不是"代码里写了 if"，而是"每一级都真的能独立提供结果"。**
早期版本 PGVector 是饿汉 Bean，PG 不通应用直接起不来，降级链第一级就卡死（事故库第 3 条）。

> 面试追问："为什么不直接上 Elasticsearch？"
> 答："ES 能同时做 BM25 和向量，但它多一个集群要运维。
> 这个项目的部署目标是'一个 MySQL + 一个 Redis 就能跑'，
> 所以选了 pgvector + MySQL FT + 内存 BM25 的组合，按部署条件逐级增强。"

---

## 3. BM25：公式与中文分词

### 3.1 公式（`BM25Retriever.java` 注释里就有）

```
BM25(Q, D) = Σ IDF(q_i) * (f(q_i,D) * (k1+1)) / (f(q_i,D) + k1*(1 - b + b*|D|/avgDL))
```

| 符号 | 含义 | 默认值 |
|------|------|--------|
| `f(q_i,D)` | 词 q_i 在文档 D 中的词频 | — |
| `|D|` | 文档长度 | — |
| `avgDL` | 平均文档长度 | — |
| `k1` | 词频饱和参数（越大越不容易饱和） | 1.5 |
| `b` | 长度归一化强度（0 不归一，1 完全归一） | 0.75 |

直觉：

- 词出现越多分越高，但**边际递减**（`k1` 控制饱和）；
- 长文档天然占便宜，所以要**惩罚长度**（`b` 控制）；
- 罕见词权重高（IDF），"退款"比"的"重要。

### 3.2 中文分词：为什么用 bigram

英文按空格分词，中文不行。项目没有引入 HanLP/jieba，而是用**重叠二元组**：

```
"退款流程" → ["退款", "款流", "流程"]
```

好处：

- 零依赖，不用维护词典；
- 查询词和文档只要有连续两字相同就能命中；
- 对"退款/退货"这类近似词有一定召回。

坏处：

- 索引膨胀（字符数 × 1）；
- 会有无意义组合（"款流"）；
- 无法识别"苹果手机"是一个词。

> 面试话术：
> "bigram 是无分词器时的标准替代方案，召回够用、精度靠 RRF + Rerank 补。
> 如果要做生产级中文检索，还是得上专业分词 + 领域词典。"

### 3.3 租户分片

`BM25Retriever.retrieve(query, topK, merchantId)` 的索引按 `merchantId` 分片，
检索只在自己分片里查。这是审查报告指出"BM25 无租户、知识互相可见"后的修复。

---

## 4. RRF：把两个排序列表合成一个

### 4.1 公式

```
RRF_score(chunk) = Σ_i 1 / (k + rank_i)
```

- `rank_i`：chunk 在第 i 个列表中的排名（**从 1 开始**）；
- `k`：平滑常数，论文推荐 60；
- 只关心**排名**，不关心原始分数——这就是它最大的优点：**不同量纲的分数可以直接融合**。

### 4.2 代码（`HybridRetriever.fuseWithRRF`）

```java
for (int i = 0; i < vectorResults.size(); i++) {
    String chunkId = vectorResults.get(i).getChunkId();
    double rrfScore = 1.0 / (RRF_K + (i + 1));
    rrfScores.merge(chunkId, rrfScore, Double::sum);
}
for (int i = 0; i < bm25Results.size(); i++) {
    String chunkId = bm25Results.get(i).getChunkId();
    double rrfScore = 1.0 / (RRF_K + (i + 1));
    rrfScores.merge(chunkId, rrfScore, Double::sum);
}
```

### 4.3 手算一遍（面试可能让你算）

`k = 60`：

| chunk | 向量排名 | BM25 排名 | RRF 分数 |
|-------|---------|-----------|----------|
| A | 1 | 3 | 1/61 + 1/63 = 0.01639 + 0.01587 = **0.03226** |
| B | 2 | 1 | 1/62 + 1/61 = 0.01613 + 0.01639 = **0.03252** |
| C | 3 | — | 1/63 = **0.01587** |

结果：**B 第一**。虽然 A 在向量里排第一，但 B 在两路都靠前，融合后胜出。

> 这就是 RRF 的核心价值：**奖励"多路都认可"的结果**，而不是被单路的极端高分绑架。

### 4.4 早期实现的三个偏差（可以主动讲）

审查报告指出：

1. 注释说"并行执行 BM25 和向量"，实际是**串行**；
2. MySQL FULLTEXT 的结果被当成"向量结果"塞进 `vectorScore`，语义混了；
3. 融合时先放向量结果、BM25 只补缺失项，导致同时命中的 chunk **丢失 bm25Score**。

修复后：两路各自保留原始分数，RRF 只做排名融合，`RetrievalResult` 同时带 `vectorScore` 和 `bm25Score`。

> 面试价值：能说出"我知道 RRF 只该用排名，不该混分数"，说明真的读过实现。

---

## 5. Rerank：在融合之后再做一次精排

`Reranker.calculateRerankScore` 是一个**可解释的加权打分**（不是模型）：

```java
return 0.32 * coverage          // 查询词覆盖度
     + 0.22 * titleCoverage     // 标题命中
     + 0.10 * phraseBonus        // 完整短语命中
     + 0.10 * positionScore      // 第一个命中词越靠前越高
     + 0.08 * densityScore       // 命中词平均位置越靠前越高
     + 0.10 * semanticScore      // 向量分（归一化）
     + 0.08 * lexicalScore;      // BM25 分（/10 归一化）
```

为什么不用 cross-encoder 模型？

- 要多部署一个推理服务，成本和延迟都上去了；
- 这个项目的语料是**结构化 SOP**（标题清晰、关键词密集），规则打分足够；
- 规则打分的权重可以解释、可以调、不需要标注数据。

> 面试话术（诚实版）：
> "这不是真正的语义 rerank，是特征加权。真正的 rerank 要用 cross-encoder
> 对 (query, passage) 打分。我用规则是因为语料是 SOP，标题和关键词信号很强；
> 如果要上生产，我会先拿评测集对比规则 vs bge-reranker，用 NDCG 决定。"

---

## 6. 评测：指标到底在量什么

### 6.1 检索指标

| 指标 | 定义 | 回答什么问题 |
|------|------|--------------|
| Recall@K | 前 K 个结果里覆盖了多少相关 chunk / 总相关数 | "该找到的找到了吗" |
| Precision@K | 前 K 个里有多少是相关的 | "找到的有多准" |
| MRR | 第一个相关结果的排名倒数（1/rank） | "第一个正确答案排多前" |
| NDCG@K | 考虑排名位置的增益折损 | "排序整体好不好" |

举例：相关 chunk 是 `{A, C}`，检索结果 `[B, A, D, C]`：

- Recall@4 = 2/2 = 1.0
- Precision@4 = 2/4 = 0.5
- MRR = 1/2 = 0.5（A 在第 2 位）
- NDCG 会把 A、C 的位置都算进去，比 MRR 更全面

### 6.2 生成指标

| 指标 | 怎么算 | 局限 |
|------|--------|------|
| Answer Relevancy | 生成答案与问题的文本相似度 | 相似不等于正确 |
| Correctness | 与标准答案的相似度 | 标准答案不唯一时误判 |
| Faithfulness | 答案中的内容有多少能在检索结果里找到 | 只能查"有没有依据"，查不了"依据对不对" |
| BLEU-4 / ROUGE-L | n-gram 重叠 / 最长公共子序列 | 中文需要分词，同义改写会低分 |
| LLM-as-judge | 用另一个模型按 rubric 打分 | 有成本、有偏差，需要固定 prompt 和模型 |

### 6.3 本项目的真实跑批（可以直接引用）

`docs/RAG-真实评测报告.md` + CHANGELOG：

| 指标 | 值 |
|------|-----|
| 用例数 | 99（全部真实生成 + 裁判） |
| Recall@10 | **0.9343** |
| MRR | **0.8518** |
| NDCG@10 | **0.8602** |
| Answer Relevancy | 0.9377 |
| Correctness | 0.7868 |
| Faithfulness | 0.9700 |
| BLEU-4 | 0.1671 |
| ROUGE-L | 0.3144 |
| 通过率 | 84.85% |
| 模型 | `deepseek-v4-flash`，`runId=33` |

> 面试怎么讲这组数字：
> "检索指标 0.93/0.85，说明知识库和召回没问题；Correctness 只有 0.79，
> 说明瓶颈在**生成**而不是检索——模型有时答得对但表述和标准答案不一致。
> Faithfulness 0.97 说明它基本不编，答案都能在 chunk 里找到依据。
> 这组数字让我能定位下一步该优化 prompt 还是优化检索，而不是盲目调参。"

**能解释"哪个指标低说明什么"**，比报数字重要得多。

---

## 7. 评测最容易自欺的四个地方（事故库第 12 条）

1. **假向量**：`retrieveByVector` 返回硬编码 `chunk_001/002/003`，评测当然高分/低分都没意义。
2. **假答案**：`generateAnswer` 只是拼接前 3 个 chunk，却当成"生成质量"来评。
3. **假指标**：BLEU 和 ROUGE 用同一个 Jaccard 实现，中文按空格切分 → 几乎退化成整串比较。
4. **评测集与知识库不对齐**：标注的 `relevant_chunks` 在真实库里不存在，Recall 恒为 0。

修复方式：

- 评测集 91 条对齐真实 `sop_*` chunk，8 条明确标记"知识库未覆盖"（不参与 Recall 分母）；
- `LLM_STRICT` 模式下生成/裁判失败立即终止，不静默降级；
- BLEU-4 / ROUGE-L 改为标准算法；
- 生成与裁判的上下文数量与评测 Top-K 对齐（`agent.evaluation.context-limit`）。

> 面试话术：
> "评测最怕的不是指标低，是指标假。我做过一次'评测全绿但线上答非所问'，
> 后来发现是生成环节在拼字符串。所以我把评测模式分成 HEURISTIC 和 LLM_STRICT，
> 报告里明确标注用的是哪种——不允许'看起来很好'。"

---

## 8. 引用（Citation）：让答案可追溯

`CitationGenerator` 把检索结果转成 `[chunkId] 标题` 形式，附在回答后面。
`KnowledgeTools.searchKnowledge` 作为 OBSERVE 级工具暴露给模型：

```java
@Tool(description = "检索企业知识库（售后SOP、退款规则、物流异常等）。返回带 chunkId 的引用片段。")
@ToolPolicy(level = AutonomyLevel.OBSERVE, category = "知识检索")
public String searchKnowledge(@ToolParam(...) String query, ...) { ... }
```

模型遇到"流程/规则"类问题时先检索，再回答，回答里带 chunkId。

> 面试问"引用有什么用"：
> "两个作用：一是用户能点开验证，降低幻觉的伤害；二是评测时能判断
> '答案有没有依据'（Faithfulness）。没有引用的 RAG 只是一个更会说话的搜索框。"

---

## 9. 本篇自测

1. 为什么需要混合检索？只用向量或只用 BM25 各自会漏什么？
2. 写出 BM25 公式，并解释 `k1` 和 `b` 的作用。
3. 中文 bigram 分词的优缺点各两条。
4. 手算：`k=60`，A 在向量排 1、BM25 排 3；B 在向量排 2、BM25 排 1。谁的分高？
5. Recall@K 和 MRR 分别在什么场景下更有意义？
6. 如果 Recall 高但 Correctness 低，问题出在哪一环？
7. 说出三个"评测自欺"的例子。

### 第 6 题的参考答法

> "Recall 高说明相关文档都召回了，Correctness 低说明生成环节没用好这些文档：
> 可能是 context 拼得太长导致中间信息丢失（lost in the middle），
> 可能是 prompt 没要求'只根据给定资料回答'，也可能是标准答案的表述差异。
> 我会先看 BadCase 里 Faithfulness 的分布：如果 Faithfulness 高但 Correctness 低，
> 多半是表述/口径问题；如果 Faithfulness 也低，才是检索或 prompt 的问题。"

---

## 10. 下一步

进入 `06-DAG与记忆篇`。RAG 解决"知识从哪来"，DAG 解决"多步任务怎么编排、失败怎么办"。
