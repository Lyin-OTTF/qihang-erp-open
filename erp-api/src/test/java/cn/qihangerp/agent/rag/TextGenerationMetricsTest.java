package cn.qihangerp.agent.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextGenerationMetricsTest {

    @Test
    void identicalChineseTextScoresOne() {
        assertEquals(1.0, TextGenerationMetrics.bleu4("退款流程说明", "退款流程说明"), 1e-9);
        assertEquals(1.0, TextGenerationMetrics.rougeL("退款流程说明", "退款流程说明"), 1e-9);
    }

    @Test
    void unrelatedTextScoresZero() {
        assertEquals(0.0, TextGenerationMetrics.bleu4("退款流程", "库存盘点"), 1e-9);
        assertEquals(0.0, TextGenerationMetrics.rougeL("退款流程", "库存盘点"), 1e-9);
    }

    @Test
    void partialOverlapIsBetweenZeroAndOne() {
        double bleu = TextGenerationMetrics.bleu4("客户申请退款后等待审核", "客户申请退款后完成审核");
        double rouge = TextGenerationMetrics.rougeL("客户申请退款后等待审核", "客户申请退款后完成审核");
        assertTrue(bleu > 0.0 && bleu < 1.0);
        assertTrue(rouge > 0.0 && rouge < 1.0);
    }
}
