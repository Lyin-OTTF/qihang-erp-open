package cn.qihangerp.agent.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RerankerTest {

    private Reranker reranker;

    @BeforeEach
    void setUp() {
        reranker = new Reranker();
        ReflectionTestUtils.setField(reranker, "rerankEnabled", true);
        ReflectionTestUtils.setField(reranker, "rerankTopN", 20);
    }

    @Test
    void reranksCandidatesBeyondOriginalTopFive() {
        List<RetrievalResult> candidates = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            RetrievalResult result = RetrievalResult.createHybridResult(
                    "irrelevant_" + i,
                    "订单发货与物流信息",
                    0.2,
                    8.0,
                    0.02,
                    "order");
            result.setDocName("订单发货");
            candidates.add(result);
        }

        RetrievalResult relevant = RetrievalResult.createHybridResult(
                "relevant",
                "会员优惠券可以用于营销触达和精准召回",
                0.75,
                2.0,
                0.01,
                "member");
        relevant.setDocName("会员优惠券发放规范");
        candidates.add(relevant);

        List<RetrievalResult> reranked = reranker.rerank(
                "怎么给会员发优惠券", candidates, 10);

        assertSame(relevant, reranked.get(0));
        assertEquals("relevant", reranked.get(0).getChunkId());
    }

    @Test
    void titleMatchBreaksTieWhenScoresAreClose() {
        RetrievalResult generic = RetrievalResult.createHybridResult(
                "generic", "订单处理说明", 0.5, 2.0, 0.02, "order");
        generic.setDocName("订单备注使用规范");

        RetrievalResult member = RetrievalResult.createHybridResult(
                "member", "会员数据与标签管理", 0.5, 2.0, 0.02, "member");
        member.setDocName("客户标签管理");

        List<RetrievalResult> reranked = reranker.rerank(
                "客户标签怎么用", List.of(generic, member), 2);

        assertEquals("member", reranked.get(0).getChunkId());
    }
}
