package cn.qihangerp.agent.evaluation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Bad Case 反馈服务
 *
 * <p>提供 Bad Case 的提交、查询、状态流转功能。</p>
 *
 * @author 回溯开发-阶段2
 * @since 2026-10-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BadCaseService {

    private final BadCaseMapper badCaseMapper;

    /** 合法的反馈类型 */
    private static final List<String> VALID_FEEDBACK_TYPES =
            Arrays.asList("WRONG_ANSWER", "UNHELPFUL", "UNSAFE", "OFF_TOPIC", "OTHER");

    /** 合法的状态流转 */
    private static final List<String> VALID_STATUSES =
            Arrays.asList("OPEN", "PROCESSING", "RESOLVED", "IGNORED");

    /**
     * 提交 Bad Case 反馈
     *
     * @param badCase 反馈内容
     * @return 记录ID
     */
    public Long submit(BadCase badCase) {
        // 参数校验
        if (badCase.getFeedbackType() == null || badCase.getFeedbackType().isBlank()) {
            throw new IllegalArgumentException("feedbackType 不能为空");
        }
        if (!VALID_FEEDBACK_TYPES.contains(badCase.getFeedbackType())) {
            throw new IllegalArgumentException("feedbackType 非法，合法值: " + VALID_FEEDBACK_TYPES);
        }
        if (badCase.getQuestion() == null || badCase.getQuestion().isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }

        badCase.setStatus("OPEN");
        badCase.setCreatedAt(LocalDateTime.now());
        badCase.setUpdatedAt(LocalDateTime.now());

        badCaseMapper.insert(badCase);
        log.info("Bad Case 提交成功: id={}, type={}, userId={}, traceId={}",
                badCase.getId(), badCase.getFeedbackType(), badCase.getUserId(), badCase.getTraceId());
        return badCase.getId();
    }

    /**
     * 根据ID查询
     */
    public BadCase getById(Long id) {
        return badCaseMapper.selectById(id);
    }

    /**
     * 按租户分页查询
     */
    public Page<BadCase> getByMerchantId(Long merchantId, int pageNum, int pageSize) {
        Page<BadCase> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<BadCase> wrapper = new LambdaQueryWrapper<BadCase>()
                .eq(merchantId != null, BadCase::getMerchantId, merchantId)
                .orderByDesc(BadCase::getCreatedAt);
        return badCaseMapper.selectPage(page, wrapper);
    }

    /**
     * 按状态分页查询
     */
    public Page<BadCase> getByStatus(String status, int pageNum, int pageSize) {
        Page<BadCase> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<BadCase> wrapper = new LambdaQueryWrapper<BadCase>()
                .eq(status != null, BadCase::getStatus, status)
                .orderByDesc(BadCase::getCreatedAt);
        return badCaseMapper.selectPage(page, wrapper);
    }

    /**
     * 按反馈类型统计数量
     */
    public java.util.Map<String, Long> countByFeedbackType(Long merchantId) {
        LambdaQueryWrapper<BadCase> wrapper = new LambdaQueryWrapper<BadCase>()
                .eq(merchantId != null, BadCase::getMerchantId, merchantId)
                .select(BadCase::getFeedbackType);
        List<BadCase> all = badCaseMapper.selectList(wrapper);
        java.util.Map<String, Long> result = new java.util.HashMap<>();
        for (BadCase bc : all) {
            result.merge(bc.getFeedbackType(), 1L, Long::sum);
        }
        return result;
    }

    /**
     * 更新处理状态
     *
     * @param id         Bad Case ID
     * @param status     新状态
     * @param resolution 处理结果（可选）
     * @param resolvedBy 处理人ID（可选）
     */
    public boolean updateStatus(Long id, String status, String resolution, Long resolvedBy) {
        if (!VALID_STATUSES.contains(status)) {
            throw new IllegalArgumentException("status 非法，合法值: " + VALID_STATUSES);
        }
        BadCase existing = badCaseMapper.selectById(id);
        if (existing == null) {
            return false;
        }

        BadCase update = new BadCase();
        update.setId(id);
        update.setStatus(status);
        update.setResolution(resolution);
        update.setResolvedBy(resolvedBy);
        if ("RESOLVED".equals(status) || "IGNORED".equals(status)) {
            update.setResolvedAt(LocalDateTime.now());
        }
        update.setUpdatedAt(LocalDateTime.now());

        int rows = badCaseMapper.updateById(update);
        log.info("Bad Case 状态更新: id={}, {} -> {}, rows={}", id, existing.getStatus(), status, rows);
        return rows > 0;
    }

    /**
     * 根据traceId查询关联的Bad Case
     */
    public List<BadCase> getByTraceId(String traceId) {
        return badCaseMapper.selectList(
                new LambdaQueryWrapper<BadCase>()
                        .eq(BadCase::getTraceId, traceId)
                        .orderByAsc(BadCase::getCreatedAt)
        );
    }
}
