package cn.qihangerp.agent.approval;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批管理 REST 接口。
 *
 * <p>提供审批列表查询、批准、拒绝等接口，供前端审批弹窗调用。</p>
 *
 * @author 回溯开发-搁置项攻关
 * @since 2026-10-12
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/approval")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final cn.qihangerp.agent.tenant.TenantAuthorizer tenantAuthorizer;

    public ApprovalController(ApprovalService approvalService,
                              cn.qihangerp.agent.tenant.TenantAuthorizer tenantAuthorizer) {
        this.approvalService = approvalService;
        this.tenantAuthorizer = tenantAuthorizer;
    }

    /**
     * 查询待审批列表
     */
    @GetMapping("/pending")
    public Map<String, Object> listPending(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long effectiveTenant = tenantAuthorizer.authorize(merchantId);
        Page<ApprovalRequest> result = approvalService.listPending(effectiveTenant, page, size);
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 200);
        resp.put("data", result.getRecords());
        resp.put("total", result.getTotal());
        resp.put("page", page);
        resp.put("size", size);
        return resp;
    }

    /**
     * 按 traceId 查询审批记录
     */
    @GetMapping("/trace/{traceId}")
    public Map<String, Object> listByTraceId(@PathVariable String traceId) {
        Long effectiveTenant = tenantAuthorizer.authorize(null);
        List<ApprovalRequest> list = approvalService.listByTraceId(traceId, effectiveTenant);
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 200);
        resp.put("data", list);
        return resp;
    }

    /**
     * 批准审批
     */
    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable Long id,
                                       @RequestParam(required = false) String comment) {
        Long effectiveTenant = tenantAuthorizer.authorize(null);
        Long approverId = cn.qihangerp.security.common.SecurityUtils.getUserId();
        boolean success = approvalService.approve(id, effectiveTenant, approverId, comment);
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", success ? 200 : 400);
        resp.put("msg", success ? "审批通过" : "审批失败（状态不允许或不存在）");
        return resp;
    }

    /**
     * 拒绝审批
     */
    @PostMapping("/{id}/reject")
    public Map<String, Object> reject(@PathVariable Long id,
                                      @RequestParam(required = false) String comment) {
        Long effectiveTenant = tenantAuthorizer.authorize(null);
        Long approverId = cn.qihangerp.security.common.SecurityUtils.getUserId();
        boolean success = approvalService.reject(id, effectiveTenant, approverId, comment);
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", success ? 200 : 400);
        resp.put("msg", success ? "已拒绝" : "操作失败（状态不允许或不存在）");
        return resp;
    }

    /**
     * 查询审批详情
     */
    @GetMapping("/{id}")
    public Map<String, Object> getById(@PathVariable Long id) {
        Long effectiveTenant = tenantAuthorizer.authorize(null);
        ApprovalRequest approval = approvalService.getById(id, effectiveTenant);
        Map<String, Object> resp = new HashMap<>();
        if (approval != null) {
            resp.put("code", 200);
            resp.put("data", approval);
        } else {
            resp.put("code", 404);
            resp.put("msg", "审批记录不存在");
        }
        return resp;
    }
}
