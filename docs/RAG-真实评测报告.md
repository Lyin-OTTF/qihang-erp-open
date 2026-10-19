# RAG 真实评测报告

## 1. 评测结论

- 评测时间：2026-10-17
- 结果记录：`agent_rag_eval_result.id = 33`
- 模型：`deepseek-v4-flash`
- 模式：`LLM_STRICT`
- 知识库：123 个 `rag_document` chunk，PGVector + MySQL FULLTEXT + BM25 混合检索
- 评测集：99 条，全部绑定真实 chunk 标注
- 总耗时：约 9 分 30 秒，99/99 用例完成真实答案生成和 LLM-as-judge

核心指标：

| 指标 | 结果 |
|---|---:|
| Recall@10 | 0.9343 |
| Precision@10 | 0.1030 |
| F1 | 0.1789 |
| MRR | 0.8518 |
| NDCG@10 | 0.8602 |
| Answer Relevancy | 0.9377 |
| Answer Correctness | 0.7868 |
| Faithfulness | 0.9700 |
| BLEU-4 | 0.1671 |
| ROUGE-L | 0.3144 |
| 通过用例 | 84 / 99 |
| 通过率 | 84.85% |
| 平均综合评分 | 63.63 |

## 2. 分领域表现

| 领域 | 用例数 | Recall | Relevancy | Correctness | Faithfulness |
|---|---:|---:|---:|---:|---:|
| supplier | 4 | 1.0000 | 0.9550 | 0.8250 | 0.9625 |
| purchase | 12 | 0.9583 | 0.9192 | 0.7350 | 0.9550 |
| goods | 11 | 1.0000 | 0.9618 | 0.8209 | 0.9636 |
| inventory | 15 | 0.9000 | 0.9487 | 0.7687 | 0.9660 |
| refund | 16 | 0.9062 | 0.9562 | 0.8025 | 0.9788 |
| logistics | 13 | 0.9231 | 0.9446 | 0.7900 | 0.9708 |
| order | 16 | 1.0000 | 0.9506 | 0.8456 | 0.9744 |
| member | 10 | 0.8000 | 0.8730 | 0.7270 | 0.9810 |
| integration | 1 | 1.0000 | 0.6500 | 0.3000 | 0.9500 |
| system | 1 | 1.0000 | 1.0000 | 1.0000 | 1.0000 |

## 3. 主要问题

1. 新增 23 条知识文档后，全部 99 条评测用例都有真实标注，检索和生成质量均显著提升。
2. 仍有 5 条 Recall@10 为 0，主要集中在客户信息修改、订单备注、部分退款、采购价格变动和在途库存等存在语义近邻但无直接命中的问题。
3. 13 条用例答案正确性低于 0.6，其中部分问题知识文档已覆盖，但答案仍较保守，需要继续优化检索上下文和提示词。
4. Precision@10 仅 0.1030，主要因为大多数问题只有 1 个标注 chunk，而固定返回 Top-10。该指标不应单独作为系统失败依据，应结合 Recall、MRR 和 NDCG。
5. `integration` 仍是单条低样本领域，正确性 0.30，不能据此做稳定结论，但应补充平台对接细节文档。

## 4. 上下文对齐对比

生成上下文已统一到 Top-10，同时将 rerank 从前 5 个候选扩展到全部 20 个候选，并加入标题匹配、短语命中和向量分数：

| 指标 | Top-5 基线 | 最终优化 |
|---|---:|---:|
| Recall@10 | 0.8626 | 0.9343 |
| Answer Relevancy | 0.7699 | 0.9377 |
| Answer Correctness | 0.5881 | 0.7868 |
| Faithfulness | 0.9521 | 0.9700 |
| Average Overall Score | 57.31 | 63.63 |
| Pass Rate | 60.61% | 84.85% |

最终优化同时改善了检索排序和知识覆盖，Correctness 提升约 19.9 个百分点，通过率提升 24.2 个百分点，生成耗时反而下降到约 9.5 分钟。

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

1. 继续补齐 5 条零召回问题对应的直接操作文档，并细化平台对接、部分退款和采购价格场景。
2. 对仍低于 0.6 的 13 条用例做逐题回流，区分知识缺失、排序错误和答案生成保守。
3. 将 rerank 结果和标题命中信号接入监控，持续跟踪 Recall、MRR 和生成延迟。
4. 将真实 LLM 评测按周运行，保留 `agent_rag_eval_case_result` 做失败样本回流。
