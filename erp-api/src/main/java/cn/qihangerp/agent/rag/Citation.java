package cn.qihangerp.agent.rag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 引用信息
 *
 * 表示RAG回答中引用的一个文档片段
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Citation {

    /**
     * 引用编号（1-based）
     */
    private int index;

    /**
     * 文档ID
     */
    private String docId;

    /**
     * 文档名称
     */
    private String docName;

    /**
     * Chunk ID
     */
    private String chunkId;

    /**
     * 引用的文本片段
     */
    private String snippet;

    /**
     * 相似度分数
     */
    private double score;

    /**
     * 引用类型（VECTOR/BM25/HYBRID）
     */
    private String type;

    /**
     * 引用格式化文本（用于插入AI回答中）
     *
     * 格式：[1] 文档名称 [chunk_id] - 片段摘要 (相似度: 0.95)
     */
    public String toFormattedString() {
        String scoreStr = String.format("%.2f", score);
        String snippetPreview = snippet.length() > 50
                ? snippet.substring(0, 50) + "..."
                : snippet;

        return String.format("[%d] %s [%s] - %s (相似度: %s)",
                index,
                docName != null ? docName : docId,
                chunkId,
                snippetPreview,
                scoreStr);
    }

    /**
     * 引用Markdown格式（用于输出）
     *
     * 格式：[1] **文档名称** [`chunk_id`] (相似度: 0.95)
     * > 片段摘要
     */
    public String toMarkdown() {
        String snippetPreview = snippet.length() > 100
                ? snippet.substring(0, 100) + "..."
                : snippet;

        return String.format("[%d] **%s** [`%s`] (相似度: %.2f)\n> %s",
                index,
                docName != null ? docName : docId,
                chunkId,
                score,
                snippetPreview);
    }

    /**
     * 简洁引用格式（仅编号）
     *
     * 格式：[1]
     */
    public String toShortString() {
        return "[" + index + "]";
    }
}
