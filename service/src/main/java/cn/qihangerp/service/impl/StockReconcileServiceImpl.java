package cn.qihangerp.service.impl;

import cn.qihangerp.common.PageQuery;
import cn.qihangerp.common.PageResult;
import cn.qihangerp.common.ServiceException;
import cn.qihangerp.mapper.OGoodsInventoryBatchMapper;
import cn.qihangerp.mapper.OGoodsInventoryMapper;
import cn.qihangerp.mapper.OGoodsInventoryOperationMapper;
import cn.qihangerp.mapper.StockReconcileDiffMapper;
import cn.qihangerp.model.bo.InventoryChangeCommand;
import cn.qihangerp.model.entity.OGoodsInventory;
import cn.qihangerp.model.entity.OGoodsInventoryBatch;
import cn.qihangerp.model.entity.OGoodsInventoryOperation;
import cn.qihangerp.model.entity.StockReconcileDiff;
import cn.qihangerp.model.vo.InventoryBatchSum;
import cn.qihangerp.model.vo.InventoryFlowStat;
import cn.qihangerp.model.vo.StockReconcileReport;
import cn.qihangerp.service.OGoodsInventoryBatchService;
import cn.qihangerp.service.OGoodsInventoryService;
import cn.qihangerp.service.StockReconcileService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 库存对账 Service 实现。
 *
 * <p>核心原则：<b>总库存是事实，账与明细向它靠拢</b>。对账只负责发现并记录差异；
 * 补偿只调整"账"（流水）与"明细"（批次），不凭空改动总库存。
 * 总库存本身异常（负数、超锁）必须走盘点，不做自动修复。</p>
 *
 * @author qihang
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockReconcileServiceImpl implements StockReconcileService {

    /** 单批扫描的库存条数 */
    private static final int BATCH_SIZE = 500;
    /** 告警文案里保留的差异条数上限 */
    private static final int REPORT_SAMPLE_LIMIT = 50;

    private final OGoodsInventoryMapper inventoryMapper;
    private final OGoodsInventoryOperationMapper operationMapper;
    private final OGoodsInventoryBatchMapper batchMapper;
    private final StockReconcileDiffMapper diffMapper;
    private final OGoodsInventoryService inventoryService;
    private final OGoodsInventoryBatchService batchService;

    @Override
    public StockReconcileReport reconcile(Long merchantId) {
        return doReconcile(merchantId, null);
    }

    @Override
    public StockReconcileReport reconcileInventories(Long merchantId, Collection<Long> inventoryIds) {
        if (inventoryIds == null || inventoryIds.isEmpty()) {
            throw new ServiceException("请指定需要核对的库存ID");
        }
        return doReconcile(merchantId, inventoryIds);
    }

    /**
     * 对账主流程：分批扫描库存 → 一次性取流水汇总与批次汇总 → 五项校验 → 落差异。
     */
    private StockReconcileReport doReconcile(Long merchantId, Collection<Long> inventoryIds) {
        StockReconcileReport report = new StockReconcileReport();
        report.setRunId(UUID.randomUUID().toString().replace("-", ""));
        report.setMerchantId(merchantId);
        report.setStartedAt(LocalDateTime.now());

        long begin = System.currentTimeMillis();
        int pageNo = 1;

        while (true) {
            LambdaQueryWrapper<OGoodsInventory> qw = new LambdaQueryWrapper<OGoodsInventory>()
                    .eq(OGoodsInventory::getIsDelete, 0)
                    .eq(merchantId != null, OGoodsInventory::getMerchantId, merchantId)
                    .in(inventoryIds != null && !inventoryIds.isEmpty(), OGoodsInventory::getId, inventoryIds)
                    .orderByAsc(OGoodsInventory::getId);

            IPage<OGoodsInventory> page = inventoryMapper.selectPage(new Page<>(pageNo, BATCH_SIZE), qw);
            List<OGoodsInventory> inventories = page.getRecords();
            if (inventories == null || inventories.isEmpty()) {
                break;
            }

            checkBatch(inventories, report);

            if (pageNo >= page.getPages()) {
                break;
            }
            pageNo++;
        }

        report.setCostMillis(System.currentTimeMillis() - begin);
        report.setFinishedAt(LocalDateTime.now());
        log.info("库存对账完成: runId={}, merchantId={}, 扫描={}, 新增差异={}, 耗时={}ms",
                report.getRunId(), merchantId, report.getScannedInventories(),
                report.getDiffCount(), report.getCostMillis());
        return report;
    }

    /**
     * 对一批库存执行五项校验。
     */
    private void checkBatch(List<OGoodsInventory> inventories, StockReconcileReport report) {
        List<Long> ids = new ArrayList<>(inventories.size());
        for (OGoodsInventory inv : inventories) {
            ids.add(inv.getId());
        }
        report.setScannedInventories(report.getScannedInventories() + inventories.size());

        Long merchantId = report.getMerchantId();
        Map<Long, InventoryFlowStat> flowStats = toMap(operationMapper.selectFlowStats(merchantId, ids));
        Map<Long, InventoryBatchSum> batchSums = toMap2(batchMapper.sumCurrentQtyByInventory(ids));
        Set<String> openDiffKeys = loadOpenDiffKeys(ids);

        for (OGoodsInventory inv : inventories) {
            // 1) 可用量一致性：派生列是否等于 quantity - locked
            int quantity = nz(inv.getQuantity());
            int locked = nz(inv.getLockedQuantity());
            int available = nz(inv.getAvailableQuantity());
            int expectedAvailable = quantity - locked;
            if (available != expectedAvailable) {
                putDiff(report, openDiffKeys, buildDiff(report.getRunId(), inv, StockReconcileDiff.TYPE_AVAILABLE_MISMATCH,
                        (long) expectedAvailable, (long) quantity, (long) available,
                        String.valueOf(expectedAvailable), String.valueOf(available),
                        "可用库存与 quantity-locked 不一致"));
            }

            // 2) 批次一致性：批次明细合计应等于总库存
            InventoryBatchSum batchSum = batchSums.get(inv.getId());
            if (batchSum != null && batchSum.getBatchQty() != null
                    && batchSum.getBatchQty().longValue() != quantity) {
                long batchQty = batchSum.getBatchQty();
                putDiff(report, openDiffKeys, buildDiff(report.getRunId(), inv, StockReconcileDiff.TYPE_BATCH_MISMATCH,
                        (long) quantity, batchQty, batchQty - quantity,
                        String.valueOf(quantity), String.valueOf(batchQty),
                        "批次合计与总库存不一致（批次数 " + batchSum.getBatchCount() + "）"));
            }

            // 3) 账实一致性：最后一笔流水的变动后余额应等于库存表数量
            InventoryFlowStat stat = flowStats.get(inv.getId());
            if (stat != null && stat.getLastAfter() != null && stat.getLastAfter() != quantity) {
                long book = stat.getLastAfter();
                putDiff(report, openDiffKeys, buildDiff(report.getRunId(), inv, StockReconcileDiff.TYPE_BOOK_ACTUAL_MISMATCH,
                        book, (long) quantity, quantity - book,
                        String.valueOf(book), String.valueOf(quantity),
                        "流水末笔 after_qty 与库存表不一致（流水 " + stat.getFlowCount() + " 条）"));
            }

            // 4) 负库存 / 超锁：事实本身异常，只能盘点处理
            if (quantity < 0 || available < 0 || locked > quantity) {
                putDiff(report, openDiffKeys, buildDiff(report.getRunId(), inv, StockReconcileDiff.TYPE_NEGATIVE_OR_OVERLOCK,
                        (long) quantity, (long) available, (long) available,
                        "quantity>=0 且 available>=0 且 locked<=quantity",
                        "quantity=" + quantity + ", locked=" + locked + ", available=" + available,
                        "负库存或锁定量超过总库存"));
            }

            // 5) 流水链连续性：净变动量应等于 末笔after - 首笔before
            if (stat != null && stat.getFirstBefore() != null && stat.getLastAfter() != null) {
                long chainDelta = (long) stat.getLastAfter() - stat.getFirstBefore();
                long net = stat.getNetQty() == null ? 0L : stat.getNetQty();
                if (net != chainDelta) {
                    putDiff(report, openDiffKeys, buildDiff(report.getRunId(), inv, StockReconcileDiff.TYPE_FLOW_CHAIN_BROKEN,
                            chainDelta, net, net - chainDelta,
                            String.valueOf(chainDelta), String.valueOf(net),
                            "流水净额与首末余额差值不一致，存在漏记/重记流水"));
                }
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String compensate(Long diffId, String operator, String remark) {
        StockReconcileDiff diff = diffMapper.selectById(diffId);
        if (diff == null) {
            throw new ServiceException("差异记录不存在：" + diffId);
        }
        if (!Integer.valueOf(StockReconcileDiff.STATUS_PENDING).equals(diff.getStatus())) {
            throw new ServiceException("该差异已处理，不能重复补偿");
        }
        OGoodsInventory inv = inventoryMapper.selectById(diff.getInventoryId());
        if (inv == null) {
            throw new ServiceException("差异对应的库存记录已不存在，请先核对后忽略该差异");
        }

        String result;
        switch (diff.getDiffType()) {
            case StockReconcileDiff.TYPE_AVAILABLE_MISMATCH -> {
                inventoryMapper.syncAvailableQuantity(inv.getId());
                result = "可用库存已按 quantity-locked 重算为 " + (nz(inv.getQuantity()) - nz(inv.getLockedQuantity()));
            }
            case StockReconcileDiff.TYPE_BATCH_MISMATCH -> {
                long target = nz(inv.getQuantity());
                int delta = rebalanceBatches(inv, target, operator);
                result = "批次合计已按总库存校正，调整量 " + delta;
            }
            case StockReconcileDiff.TYPE_BOOK_ACTUAL_MISMATCH -> {
                long bookQty = diff.getBookQty() == null ? 0L : diff.getBookQty();
                long delta = (long) nz(inv.getQuantity()) - bookQty;
                if (delta == 0L) {
                    result = "账实已一致，无需补账";
                } else {
                    writeReconcileFlow(inv, bookQty, delta, diff, operator);
                    result = "已补记调整流水 " + delta + "，账面追平实存 " + nz(inv.getQuantity());
                }
            }
            case StockReconcileDiff.TYPE_NEGATIVE_OR_OVERLOCK ->
                    throw new ServiceException("负库存/超锁属于事实异常，需盘点后人工调整，不支持自动补偿");
            case StockReconcileDiff.TYPE_FLOW_CHAIN_BROKEN ->
                    throw new ServiceException("流水链断裂需逐笔核对流水，不支持自动补偿");
            default -> throw new ServiceException("未知的差异类型：" + diff.getDiffType());
        }

        diff.setStatus(StockReconcileDiff.STATUS_COMPENSATED);
        diff.setHandledBy(operator);
        diff.setHandledTime(LocalDateTime.now());
        diff.setHandleRemark(remark);
        diffMapper.updateById(diff);

        log.info("库存差异已补偿: diffId={}, type={}, inventoryId={}, operator={}, result={}",
                diffId, diff.getDiffType(), inv.getId(), operator, result);
        return result;
    }

    @Override
    public void ignore(Long diffId, String operator, String remark) {
        StockReconcileDiff diff = diffMapper.selectById(diffId);
        if (diff == null) {
            throw new ServiceException("差异记录不存在：" + diffId);
        }
        if (!Integer.valueOf(StockReconcileDiff.STATUS_PENDING).equals(diff.getStatus())) {
            throw new ServiceException("该差异已处理");
        }
        diff.setStatus(StockReconcileDiff.STATUS_IGNORED);
        diff.setHandledBy(operator);
        diff.setHandledTime(LocalDateTime.now());
        diff.setHandleRemark(remark);
        diffMapper.updateById(diff);
    }

    @Override
    public OGoodsInventory getInventoryForCheck(Long inventoryId) {
        return inventoryMapper.selectById(inventoryId);
    }

    @Override
    public PageResult<StockReconcileDiff> queryDiffs(Integer status, Long merchantId, PageQuery pageQuery) {
        LambdaQueryWrapper<StockReconcileDiff> qw = new LambdaQueryWrapper<StockReconcileDiff>()
                .eq(status != null, StockReconcileDiff::getStatus, status)
                .eq(merchantId != null, StockReconcileDiff::getMerchantId, merchantId)
                .orderByDesc(StockReconcileDiff::getId);
        return PageResult.build(diffMapper.selectPage(pageQuery.build(), qw));
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /**
     * 把批次合计调整到 target，采用与出库 FEFO 一致的顺序：
     * 差额为正则加到最早批次；为负则从最早批次依次扣减。
     *
     * @return 实际调整量
     */
    private int rebalanceBatches(OGoodsInventory inv, long target, String operator) {
        List<OGoodsInventoryBatch> batches = batchService.list(new LambdaQueryWrapper<OGoodsInventoryBatch>()
                .eq(OGoodsInventoryBatch::getInventoryId, inv.getId())
                .orderByAsc(OGoodsInventoryBatch::getId));
        if (batches.isEmpty()) {
            throw new ServiceException("该库存没有任何批次，无法按批次校正，请先补建批次");
        }
        long current = 0L;
        for (OGoodsInventoryBatch b : batches) {
            current += nz(b.getCurrentQty());
        }
        long delta = target - current;
        if (delta == 0L) {
            return 0;
        }

        if (delta > 0) {
            // 补到最早批次，保持 FEFO 语义
            OGoodsInventoryBatch first = batches.get(0);
            if (!batchService.adjustBatchStock(first.getId(), (int) delta)) {
                throw new ServiceException("批次补正失败：批次 " + first.getId());
            }
        } else {
            long remaining = -delta;
            for (OGoodsInventoryBatch b : batches) {
                if (remaining <= 0) {
                    break;
                }
                int qty = nz(b.getCurrentQty());
                if (qty <= 0) {
                    continue;
                }
                int cut = (int) Math.min(qty, remaining);
                if (batchService.adjustBatchStock(b.getId(), -cut)) {
                    remaining -= cut;
                }
            }
            if (remaining > 0) {
                throw new ServiceException("批次扣减不足，剩余 " + remaining + " 无法校正");
            }
        }
        log.warn("批次合计已校正: inventoryId={}, delta={}, operator={}", inv.getId(), delta, operator);
        return (int) delta;
    }

    /**
     * 补记一条调整流水，使"流水推算余额"追平实存。不修改库存表。
     */
    private void writeReconcileFlow(OGoodsInventory inv, long bookQty, long delta,
                                    StockReconcileDiff diff, String operator) {
        OGoodsInventoryOperation op = new OGoodsInventoryOperation();
        op.setMerchantId(inv.getMerchantId() != null ? inv.getMerchantId() : 0L);
        op.setGoodsId(inv.getGoodsId() != null ? inv.getGoodsId() : 0L);
        op.setGoodsNum(inv.getGoodsNum());
        op.setSkuId(inv.getSkuId() != null ? inv.getSkuId() : 0L);
        op.setSkuCode(inv.getSkuCode() != null ? inv.getSkuCode() : "");
        op.setBatchId(0L);
        op.setBatchNum("");
        op.setType(InventoryChangeCommand.TYPE_ADJUST);
        op.setInventoryDetailId(inv.getId());
        op.setQuantity((int) delta);
        op.setBeforeQty((int) bookQty);
        op.setAfterQty(nz(inv.getQuantity()));
        op.setIdempotencyKey("RECONCILE_DIFF:" + diff.getId());
        op.setLockedQuantity(0);
        op.setPrice(0.0);
        op.setBizType(InventoryChangeCommand.BIZ_RECONCILE_ADJUST);
        op.setBizId(0L);
        op.setBizNum(diff.getRunId() == null ? "" : diff.getRunId());
        op.setBizItemId(diff.getId());
        op.setStatus(1);
        op.setRemark("对账补偿：差异ID " + diff.getId() + "，补记 " + delta);
        op.setWarehouseId(inv.getWarehouseId() != null ? inv.getWarehouseId() : 0L);
        op.setPositionId(0L);
        op.setCreateBy(operator);
        op.setUpdateBy(operator);
        op.setCreateTime(LocalDateTime.now());
        op.setUpdateTime(LocalDateTime.now());
        operationMapper.insert(op);
    }

    private void putDiff(StockReconcileReport report, Set<String> openKeys, StockReconcileDiff diff) {
        String key = diff.getInventoryId() + ":" + diff.getDiffType();
        if (openKeys.contains(key)) {
            // 同一库存同一类型已有待处理差异，不重复堆积，避免每日对账刷屏
            return;
        }
        diffMapper.insert(diff);
        openKeys.add(key);

        report.setDiffCount(report.getDiffCount() + 1);
        report.addTypeStat(diff.getDiffType(), 1);
        if (report.getDiffs().size() < REPORT_SAMPLE_LIMIT) {
            report.getDiffs().add(diff);
        }
    }

    private StockReconcileDiff buildDiff(String runId, OGoodsInventory inv, int diffType,
                                         Long bookQty, Long actualQty,
                                         Long diffQty, String expected, String actual, String detail) {
        StockReconcileDiff d = new StockReconcileDiff();
        d.setRunId(runId);
        d.setMerchantId(inv.getMerchantId() != null ? inv.getMerchantId() : 0L);
        d.setInventoryId(inv.getId());
        d.setSkuId(inv.getSkuId());
        d.setSkuCode(inv.getSkuCode());
        d.setGoodsName(inv.getGoodsName());
        d.setWarehouseId(inv.getWarehouseId());
        d.setDiffType(diffType);
        d.setBookQty(bookQty);
        d.setActualQty(actualQty);
        d.setDiffQty(diffQty);
        d.setExpectedValue(expected);
        d.setActualValue(actual);
        d.setStatus(StockReconcileDiff.STATUS_PENDING);
        d.setDetail(detail);
        d.setCheckTime(LocalDateTime.now());
        return d;
    }

    /**
     * 读取已存在的待处理差异键（inventoryId:diffType），用于跨批次、跨次运行去重。
     */
    private Set<String> loadOpenDiffKeys(List<Long> inventoryIds) {
        List<StockReconcileDiff> open = diffMapper.selectList(new LambdaQueryWrapper<StockReconcileDiff>()
                .in(StockReconcileDiff::getInventoryId, inventoryIds)
                .eq(StockReconcileDiff::getStatus, StockReconcileDiff.STATUS_PENDING));
        Set<String> keys = new HashSet<>();
        for (StockReconcileDiff d : open) {
            keys.add(d.getInventoryId() + ":" + d.getDiffType());
        }
        return keys;
    }

    private Map<Long, InventoryFlowStat> toMap(List<InventoryFlowStat> list) {
        Map<Long, InventoryFlowStat> map = new HashMap<>();
        if (list != null) {
            for (InventoryFlowStat s : list) {
                map.put(s.getInventoryId(), s);
            }
        }
        return map;
    }

    private Map<Long, InventoryBatchSum> toMap2(List<InventoryBatchSum> list) {
        Map<Long, InventoryBatchSum> map = new HashMap<>();
        if (list != null) {
            for (InventoryBatchSum s : list) {
                map.put(s.getInventoryId(), s);
            }
        }
        return map;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
