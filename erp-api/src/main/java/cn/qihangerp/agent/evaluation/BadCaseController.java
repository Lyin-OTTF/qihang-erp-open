package cn.qihangerp.agent.evaluation;

import cn.qihangerp.common.R;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Bad Case 反馈接口
 *
 * <p>用户可标记AI的错误回答，管理员可查询和处理。</p>
 *
 * @author 回溯开发-阶段2
 * @since 2026-10-12
 */
@Slf4j
@Tag(name = "Bad Case反馈")
@RestController
@RequestMapping("/api/agent/bad-case")
@RequiredArgsConstructor
public class BadCaseController {

    private final BadCaseService badCaseService;

    /**
     * 提交 Bad Case 反馈
     */
    @Operation(summary = "提交Bad Case反馈")
    @PostMapping("/submit")
    public R<Long> submit(@RequestBody BadCase badCase) {
        Long id = badCaseService.submit(badCase);
        return R.ok(id);
    }

    /**
     * 根据ID查询
     */
    @Operation(summary = "根据ID查询Bad Case")
    @GetMapping("/{id}")
    public R<BadCase> getById(@PathVariable Long id) {
        BadCase badCase = badCaseService.getById(id);
        return R.ok(badCase);
    }

    /**
     * 按租户分页查询
     */
    @Operation(summary = "按租户分页查询Bad Case")
    @GetMapping("/merchant/{merchantId}")
    public R<Page<BadCase>> getByMerchantId(
            @PathVariable Long merchantId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        Page<BadCase> page = badCaseService.getByMerchantId(merchantId, pageNum, pageSize);
        return R.ok(page);
    }

    /**
     * 按状态分页查询
     */
    @Operation(summary = "按状态分页查询Bad Case")
    @GetMapping("/status/{status}")
    public R<Page<BadCase>> getByStatus(
            @PathVariable String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        Page<BadCase> page = badCaseService.getByStatus(status, pageNum, pageSize);
        return R.ok(page);
    }

    /**
     * 按反馈类型统计
     */
    @Operation(summary = "按反馈类型统计数量")
    @GetMapping("/stats")
    public R<Map<String, Long>> getStats(@RequestParam(required = false) Long merchantId) {
        Map<String, Long> stats = badCaseService.countByFeedbackType(merchantId);
        return R.ok(stats);
    }

    /**
     * 更新处理状态
     */
    @Operation(summary = "更新Bad Case处理状态")
    @PutMapping("/{id}/status")
    public R<Boolean> updateStatus(
            @PathVariable Long id,
            @RequestParam String status,
            @RequestParam(required = false) String resolution,
            @RequestParam(required = false) Long resolvedBy) {
        boolean success = badCaseService.updateStatus(id, status, resolution, resolvedBy);
        return R.ok(success);
    }

    /**
     * 根据traceId查询关联的Bad Case
     */
    @Operation(summary = "根据traceId查询关联Bad Case")
    @GetMapping("/trace/{traceId}")
    public R<List<BadCase>> getByTraceId(@PathVariable String traceId) {
        List<BadCase> list = badCaseService.getByTraceId(traceId);
        return R.ok(list);
    }
}
