package cn.qihangerp.erp.serviceImpl.ai;

import cn.qihangerp.agent.policy.AutonomyLevel;
import cn.qihangerp.agent.policy.ToolPolicy;
import cn.qihangerp.agent.rag.Citation;
import cn.qihangerp.agent.rag.CitationGenerator;
import cn.qihangerp.agent.rag.HybridRetriever;
import cn.qihangerp.agent.rag.RetrievalResult;
import cn.qihangerp.agent.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库检索工具（把 RAG 接入对话主链路）。
 *
 * <p>此前 RAG 只通过 /api/agent/rag/** 独立接口暴露，聊天助手无法使用。
 * 这里提供一个只读工具：模型在遇到"流程/规则/规范"类问题时先检索企业知识库，
 * 工具返回带 chunkId 的片段，模型据此作答并在句末标注引用编号。</p>
 *
 * <p>租户隔离：通过 {@link TenantContext}（由登录态注入）传给 HybridRetriever，
 * 只召回本租户或全局共享的文档。</p>
 *
 * @author 回溯开发-RAG接入对话
 * @since 2026-10-16
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeTools {

    private final HybridRetriever hybridRetriever;
    private final CitationGenerator citationGenerator;

    @Tool(description = "检索企业知识库（售后SOP、退款规则、物流异常、库存操作规范、采购流程等）。"
            + "当用户问的是流程、规则、规范、操作指引这类问题，而不是查询具体订单/库存数据时，"
            + "应优先调用本工具检索，再基于返回片段作答，并在句末标注 [n] 引用编号。")
    @ToolPolicy(level = AutonomyLevel.OBSERVE, category = "知识检索")
    public String searchKnowledge(
            @ToolParam(description = "检索问题或关键词，尽量保留用户原始问法") String query,
            @ToolParam(description = "返回条数，默认5，最大10") Integer topK) {
        if (query == null || query.isBlank()) {
            return "检索问题为空，无法检索知识库。";
        }
        int k = (topK == null || topK <= 0) ? 5 : Math.min(topK, 10);
        Long merchantId = TenantContext.getTenantId();

        List<RetrievalResult> results = hybridRetriever.retrieve(query, k, merchantId);
        if (results == null || results.isEmpty()) {
            return "未在知识库中找到与该问题相关的文档。请勿编造流程或规则，可告知用户当前知识库暂未收录该内容。";
        }

        List<Citation> citations = citationGenerator.generateCitations(results, k);
        StringBuilder sb = new StringBuilder();
        sb.append("知识库检索结果（共").append(citations.size())
                .append("条，请基于以下片段作答，并在句末标注 [n] 引用编号）：\n");
        for (Citation c : citations) {
            sb.append("[").append(c.getIndex()).append("] ")
                    .append(c.getDocName() != null ? c.getDocName() : "未命名文档")
                    .append("（chunkId=").append(c.getChunkId()).append("）\n")
                    .append(truncate(c.getSnippet(), 300))
                    .append("\n\n");
        }
        log.info("知识检索工具命中: query={}, merchantId={}, hits={}", query, merchantId, citations.size());
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
