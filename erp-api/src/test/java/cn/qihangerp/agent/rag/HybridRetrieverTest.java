package cn.qihangerp.agent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HybridRetrieverTest {

    @Test
    void rrfFusionKeepsScoresAndMetadataFromBothRetrievers() {
        HybridRetriever retriever = new HybridRetriever(null, null);

        RetrievalResult vectorResult = RetrievalResult.createVectorResult(
                "shared", "向量召回内容", 0.82, "sop_order");
        vectorResult.setDocName("订单流程");

        RetrievalResult bm25Result = RetrievalResult.createBM25Result(
                "shared", "BM25召回内容", 6.4, "sop_order");
        bm25Result.setDocName("订单流程BM25");
        bm25Result.setMetadata(Map.of("source", "bm25"));

        List<RetrievalResult> fused = ReflectionTestUtils.invokeMethod(
                retriever, "fuseWithRRF", List.of(vectorResult), List.of(bm25Result));

        assertEquals(1, fused.size());
        assertEquals(0.82, fused.get(0).getVectorScore());
        assertEquals(6.4, fused.get(0).getBm25Score());
        assertEquals("订单流程", fused.get(0).getDocName());
        assertEquals("bm25", fused.get(0).getMetadata().get("source"));
    }
}
