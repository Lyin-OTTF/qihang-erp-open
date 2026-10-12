package cn.qihangerp.agent.rag;

import lombok.Data;

import java.util.List;

/**
 * RAG响应结果
 *
 * 包含生成的回答和引用列表
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@Data
public class RagResponse {

    /**
     * AI生成的回答
     */
    private String answer;

    /**
     * 引用列表
     */
    private List<Citation> citations;

    /**
     * 检索到的chunk数量
     */
    private int retrievedCount;

    /**
     * 使用的检索方法（VECTOR/BM25/HYBRID）
     */
    private String retrievalMethod;

    /**
     * 耗时（毫秒）
     */
    private long duration;

    /**
     * 生成带引用的完整回答
     *
     * 格式：
     * 回答内容[1][2]
     *
     * 参考文献：
     * [1] 文档名称 - 片段摘要 (相似度: 0.95)
     * [2] 文档名称 - 片段摘要 (相似度: 0.88)
     */
    public String toFullAnswer() {
        if (citations == null || citations.isEmpty()) {
            return answer;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(answer);
        sb.append("\n\n");
        sb.append("📚 参考文献：\n");

        for (Citation citation : citations) {
            sb.append(citation.toMarkdown());
            sb.append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 生成简洁版回答（仅含引用编号）
     */
    public String toShortAnswer() {
        return answer;
    }
}
