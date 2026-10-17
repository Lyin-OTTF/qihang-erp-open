# RAG 真实评测报告

## 1. 评测结论

- 评测时间：2026-10-17
- 结果记录：`agent_rag_eval_result.id = 30`
- 模型：`deepseek-v4-flash`
- 模式：`LLM_STRICT`
- 知识库：100 个 `rag_document` chunk，PGVector + MySQL FULLTEXT + BM25 混合检索
- 评测集：99 条，其中 91 条有真实 chunk 标注，8 条标记为知识库暂未覆盖
- 总耗时：约 15.4 分钟，99/99 用例完成真实答案生成和 LLM-as-judge

核心指标：

| 指标 | 结果 |
|---|---:|
| Recall@10 | 0.8626 |
| Precision@10 | 0.0967 |
| F1 | 0.1727 |
| MRR | 0.7210 |
| NDCG@10 | 0.7420 |
| Answer Relevancy | 0.7870 |
| Answer Correctness | 0.6108 |
| Faithfulness | 0.9446 |
| BLEU-4 | 0.1254 |
| ROUGE-L | 0.2467 |
| 通过用例 | 58 / 99 |
| 通过率 | 58.59% |
| 平均综合评分 | 57.52 |

## 2. 分领域表现

| 领域 | 用例数 | Recall | Relevancy | Correctness | Faithfulness |
|---|---:|---:|---:|---:|---:|
| supplier | 4 | 1.0000 | 0.9700 | 0.8150 | 0.9750 |
| purchase | 12 | 0.9583 | 0.9017 | 0.6867 | 0.9542 |
| goods | 11 | 1.0000 | 0.9345 | 0.7809 | 0.9673 |
| inventory | 15 | 0.9000 | 0.8953 | 0.6933 | 0.9567 |
| refund | 16 | 0.7188 | 0.7825 | 0.6069 | 0.9569 |
| logistics | 13 | 0.8462 | 0.6962 | 0.5300 | 0.9362 |
| order | 16 | 0.6250 | 0.7113 | 0.5281 | 0.9406 |
| member | 10 | 0.5000 | 0.5950 | 0.4630 | 0.8800 |
| integration | 1 | 1.0000 | 0.5500 | 0.3000 | 0.9500 |
| system | 1 | 0.0000 | 0.0500 | 0.0000 | 0.9000 |

## 3. 主要问题

1. `member`、`order` 和 `logistics` 是当前主要弱项。会员标签、优惠券、购买历史、黑名单等问题缺少直接知识文档，或相关文档未能进入有效上下文。
2. 19 条用例 Recall@10 为 0。部分属于知识库确实未覆盖，例如系统备份、订单合并、订单拆分、操作日志、自动审核、换货流程。
3. 37 条用例答案正确性低于 0.6。模型在检索不到充分依据时按提示回答“根据现有资料无法确定”，忠实度较高但业务答案不完整。
4. 20 条用例相关性低于 0.6，说明当前检索排序和知识覆盖仍然限制了最终回答质量。
5. Precision@10 仅 0.0967，主要因为大多数问题只有 1 个标注 chunk，而固定返回 Top-10。该指标不应单独作为系统失败依据，应结合 Recall、MRR 和 NDCG。

## 4. 上下文对齐对比

上一轮基线生成阶段只使用 Top-5，而检索指标按 Top-10 计算。本轮将生成和裁判上下文统一为 Top-10：

| 指标 | Top-5 基线 | Top-10 对齐 |
|---|---:|---:|
| Answer Relevancy | 0.7699 | 0.7870 |
| Answer Correctness | 0.5881 | 0.6108 |
| Faithfulness | 0.9521 | 0.9446 |
| Average Overall Score | 57.31 | 57.52 |
| Pass Rate | 60.61% | 58.59% |

Correctness 和 Relevancy 有提升，但上下文变长后生成耗时增加，性能分下降，导致按 60 分阈值计算的总通过率略降。后续应通过 rerank 和上下文裁剪同时保证质量与延迟。

## 5. 复现方式

真实 LLM 评测独立使用 `llm` 标签，默认单元/集成测试不会产生 API 费用：

```powershell
$env:MYSQL_PASSWORD="..."
$env:REDIS_PASSWORD="..."
$env:PGVECTOR_PASSWORD="..."
$env:PGVECTOR_DATABASE="erp_vector"
$env:PGVECTOR_USERNAME="postgres"

mvn -pl erp-api -am test -Prag-llm-eval `
  -Dtest=RagLlmEvaluationIntegrationTest
```

小样本冒烟可设置：

```powershell
$env:RAG_EVAL_MAX_CASES="3"
```

逐用例答案、检索 chunk 和各项指标保存在 `agent_rag_eval_case_result`，聚合结果保存在 `agent_rag_eval_result`。

## 6. 下一步建议

1. 补齐 8 条未标注问题对应的 SOP 文档，并为 19 条零召回问题增加知识条目。
2. 重点优化 `member`、`order`、`logistics` 领域的 rerank 权重和同义表达覆盖。
3. 对 Top-10 上下文做压缩或 rerank 到 Top-5，避免长上下文增加生成延迟。
4. 将真实 LLM 评测按周运行，保留 `agent_rag_eval_case_result` 做失败样本回流。
