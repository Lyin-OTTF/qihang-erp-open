package cn.qihangerp.erp.controller.erp;

import cn.qihangerp.common.AjaxResult;
import cn.qihangerp.common.PageQuery;
import cn.qihangerp.common.PageResult;
import cn.qihangerp.common.TableDataInfo;
import cn.qihangerp.erp.job.StockReconcileJob;
import cn.qihangerp.model.entity.StockReconcileDiff;
import cn.qihangerp.model.vo.StockReconcileReport;
import cn.qihangerp.security.common.BaseController;
import cn.qihangerp.security.common.SecurityUtils;
import cn.qihangerp.service.StockReconcileService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存对账 Controller。
 *
 * <p>提供"看差异 → 人工确认 → 补偿/忽略"的闭环入口。补偿动作逐条触发，
 * 不做批量自动修复，避免把真实库存问题静默抹平。</p>
 *
 * @author qihang
 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/erp-api/stockReconcile")
public class StockReconcileController extends BaseController {

    private final StockReconcileService reconcileService;
    private final StockReconcileJob reconcileJob;

    /**
     * 分页查询对账差异。
     *
     * @param status     0待处理 1已补偿 2已忽略，不传查全部
     * @param merchantId 商户ID，不传查全部（管理员视角）
     */
    @GetMapping("/diffs")
    public TableDataInfo diffs(@RequestParam(required = false) Integer status,
                               @RequestParam(required = false) Long merchantId,
                               PageQuery pageQuery) {
        PageResult<StockReconcileDiff> page = reconcileService.queryDiffs(status, merchantId, pageQuery);
        return getDataTable(page);
    }

    /**
     * 手工触发一次对账（不等待定时任务）。
     *
     * @param merchantId 商户ID，不传表示全库
     */
    @PostMapping("/run")
    public AjaxResult run(@RequestParam(required = false) Long merchantId) {
        StockReconcileReport report = reconcileJob.runOnce(merchantId);
        if (report == null) {
            return AjaxResult.error("对账执行失败，请查看服务端日志");
        }
        return AjaxResult.success(report);
    }

    /**
     * 人工确认后补偿一条差异。
     */
    @PostMapping("/compensate/{id}")
    public AjaxResult compensate(@PathVariable("id") Long id,
                                 @RequestParam(required = false) String remark) {
        String result = reconcileService.compensate(id, SecurityUtils.getUsername(), remark);
        return AjaxResult.success(result);
    }

    /**
     * 忽略一条差异（已知晓、暂不处理）。
     */
    @PostMapping("/ignore/{id}")
    public AjaxResult ignore(@PathVariable("id") Long id,
                             @RequestParam(required = false) String remark) {
        reconcileService.ignore(id, SecurityUtils.getUsername(), remark);
        return AjaxResult.success();
    }
}
