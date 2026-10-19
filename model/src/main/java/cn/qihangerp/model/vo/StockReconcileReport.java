package cn.qihangerp.model.vo;

import cn.qihangerp.model.entity.StockReconcileDiff;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次库存对账任务的执行报告。
 *
 * <p>既是定时任务的运行结果，也是告警内容的来源：任务结束后按本报告拼接告警文案。</p>
 */
@Data
public class StockReconcileReport {

    /** 对账批次ID */
    private String runId;

    /** 商户ID，null 表示全库 */
    private Long merchantId;

    /** 扫描的库存记录数 */
    private int scannedInventories;

    /** 本次新增的差异条数 */
    private int diffCount;

    /** 按差异类型统计的条数 */
    private Map<Integer, Integer> typeStats = new LinkedHashMap<>();

    /** 本次新增的差异明细（最多保留前 50 条用于告警文案） */
    private List<StockReconcileDiff> diffs = new ArrayList<>();

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    /** 耗时毫秒 */
    private long costMillis;

    public void addTypeStat(int diffType, int count) {
        typeStats.merge(diffType, count, Integer::sum);
    }
}
