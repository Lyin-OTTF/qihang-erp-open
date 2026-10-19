package cn.qihangerp.erp.job;

import cn.qihangerp.erp.notify.NotifierService;
import cn.qihangerp.model.entity.StockReconcileDiff;
import cn.qihangerp.model.entity.SysMessage;
import cn.qihangerp.model.vo.StockReconcileReport;
import cn.qihangerp.service.ISysMessageService;
import cn.qihangerp.service.StockReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 库存定时对账任务。
 *
 * <p>默认每日 03:10 执行（业务低峰），可通过配置关闭或改期：
 * <pre>
 * erp:
 *   stock:
 *     reconcile:
 *       enabled: true
 *       cron: "0 10 3 * * ?"
 * </pre>
 *
 * <p>任务只做三件事：跑对账、落差异、发告警。补偿必须由人工在差异列表上确认后触发，
 * 避免"自动改账"掩盖真实库存问题。</p>
 *
 * @author qihang
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "erp.stock.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class StockReconcileJob {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StockReconcileService reconcileService;
    private final NotifierService notifierService;
    private final ISysMessageService messageService;

    /**
     * 定时入口：全库对账。
     */
    @Scheduled(cron = "${erp.stock.reconcile.cron:0 10 3 * * ?}")
    public void run() {
        log.info("库存对账定时任务开始");
        runOnce(null);
    }

    /**
     * 执行一次对账，并按结果告警。
     *
     * @param merchantId 商户ID，null 表示全库（供手工触发按商户对账）
     * @return 对账报告；执行异常时返回 null（不影响调度线程）
     */
    public StockReconcileReport runOnce(Long merchantId) {
        try {
            StockReconcileReport report = reconcileService.reconcile(merchantId);
            if (report.getDiffCount() > 0) {
                alert(report);
            } else {
                log.info("库存对账无差异: runId={}, 扫描 {} 条库存, 耗时 {}ms",
                        report.getRunId(), report.getScannedInventories(), report.getCostMillis());
            }
            return report;
        } catch (Exception e) {
            // 对账失败本身也需要告警，否则"定时任务静默失败"会让账实差异长期无人发现
            log.error("库存对账任务执行失败", e);
            safeNotify("库存对账任务执行失败",
                    "时间：" + LocalDateTime.now().format(TS) + "\n异常：" + e.getMessage());
            return null;
        }
    }

    /**
     * 差异告警：站内消息 + 外部渠道（飞书/钉钉/企业微信，取决于 sys_alert_channel 配置）。
     */
    private void alert(StockReconcileReport report) {
        String title = "库存对账发现 " + report.getDiffCount() + " 条差异";
        String content = buildAlertContent(report);
        log.warn("{} | runId={}", title, report.getRunId());

        SysMessage msg = new SysMessage();
        msg.setType("stock_reconcile");
        msg.setLevel(report.getDiffCount() > 10 ? "high" : "warning");
        msg.setTitle(title);
        msg.setContent(content.length() > 1000 ? content.substring(0, 1000) : content);
        msg.setSource("StockReconcileJob");
        msg.setIsRead(0);
        msg.setNeedNotify(1);
        msg.setCreatedTime(LocalDateTime.now());
        try {
            messageService.save(msg);
        } catch (Exception e) {
            log.warn("库存对账站内消息写入失败: {}", e.getMessage());
        }
        safeNotify(title, content);
    }

    private void safeNotify(String title, String content) {
        try {
            boolean ok = notifierService.notifyAll(title, content);
            if (!ok) {
                log.warn("库存对账告警部分渠道推送失败");
            }
        } catch (Exception e) {
            log.warn("库存对账告警推送异常: {}", e.getMessage());
        }
    }

    private String buildAlertContent(StockReconcileReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("对账批次：").append(report.getRunId()).append('\n');
        sb.append("完成时间：")
                .append(report.getFinishedAt() == null ? "-" : report.getFinishedAt().format(TS)).append('\n');
        sb.append("扫描库存：").append(report.getScannedInventories()).append(" 条，耗时 ")
                .append(report.getCostMillis()).append("ms\n");
        sb.append("差异分布：");
        for (Map.Entry<Integer, Integer> e : report.getTypeStats().entrySet()) {
            sb.append(typeName(e.getKey())).append(' ').append(e.getValue()).append(" 条；");
        }
        sb.append("\n\n前 ").append(Math.min(5, report.getDiffs().size())).append(" 条明细：\n");
        int i = 0;
        for (StockReconcileDiff d : report.getDiffs()) {
            if (i++ >= 5) {
                break;
            }
            sb.append("- [").append(typeName(d.getDiffType())).append("] ")
                    .append(d.getSkuCode() == null ? "-" : d.getSkuCode())
                    .append(" 账面=").append(d.getBookQty())
                    .append(" 实存=").append(d.getActualQty())
                    .append(" 差异=").append(d.getDiffQty())
                    .append('\n');
        }
        sb.append("\n请在「库存对账」页面逐条确认后补偿。差异ID：").append(report.getRunId());
        return sb.toString();
    }

    private String typeName(Integer diffType) {
        if (diffType == null) {
            return "未知";
        }
        return switch (diffType) {
            case StockReconcileDiff.TYPE_AVAILABLE_MISMATCH -> "可用量不一致";
            case StockReconcileDiff.TYPE_BATCH_MISMATCH -> "批次合计不一致";
            case StockReconcileDiff.TYPE_BOOK_ACTUAL_MISMATCH -> "账实不一致";
            case StockReconcileDiff.TYPE_NEGATIVE_OR_OVERLOCK -> "负库存/超锁";
            case StockReconcileDiff.TYPE_FLOW_CHAIN_BROKEN -> "流水链断裂";
            default -> "未知(" + diffType + ")";
        };
    }
}
