package cn.qihangerp.agent.policy;

import cn.qihangerp.common.R;
import cn.qihangerp.security.LoginUser;
import cn.qihangerp.security.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Map;

/**
 * Agent工具策略动态配置接口。
 *
 * <p>查看接口(list/get)所有登录用户可访问；
 * 修改接口(update/refresh)仅平台管理员(user_type=0)可调用，
 * 且 update 校验 toolName 必须存在于 PolicyRegistry 扫描结果中，
 * 防止通过 DB 注入不存在的工具名绕过审批体系。</p>
 *
 * @author 回溯开发-第二类补全
 * @since 2026-10-14
 */
@Slf4j
@Tag(name = "工具策略管理")
@RestController
@RequestMapping("/api/agent/policy")
@RequiredArgsConstructor
public class ToolPolicyController {

    private final PolicyRegistry policyRegistry;

    /** 平台管理员 user_type = "0"（EnumUserType.SUPPER） */
    private static final String ADMIN_USER_TYPE = "0";

    /**
     * 列出所有已注册工具的当前策略（含注解默认值和DB覆盖值）。
     * 所有登录用户可查看。
     */
    @Operation(summary = "列出所有工具策略")
    @GetMapping("/list")
    public R<Collection<PolicyRegistry.ToolPolicyMetadata>> listPolicies() {
        return R.ok(policyRegistry.getAllPolicies());
    }

    /**
     * 按工具名查询策略。所有登录用户可查看。
     */
    @Operation(summary = "按工具名查询策略")
    @GetMapping("/{toolName}")
    public R<PolicyRegistry.ToolPolicyMetadata> getPolicy(@PathVariable String toolName) {
        return R.ok(policyRegistry.getPolicy(toolName));
    }

    /**
     * 动态修改工具自治等级（写入DB并立即刷新内存）。
     * 仅平台管理员可调用，且 toolName 必须存在于扫描结果中。
     */
    @Operation(summary = "动态修改工具自治等级（仅管理员）")
    @PostMapping("/update")
    public R<PolicyRegistry.ToolPolicyMetadata> updatePolicy(@RequestBody Map<String, String> body) {
        requireAdmin();

        String toolName = body.get("toolName");
        String levelStr = body.get("autonomyLevel");
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空");
        }
        // 校验 toolName 必须存在于 PolicyRegistry 扫描结果中，防止注入不存在的工具
        if (policyRegistry.getPolicy(toolName) == null) {
            throw new IllegalArgumentException("工具不存在或未注册: " + toolName + "，仅可修改已注册的工具策略");
        }
        if (levelStr == null || levelStr.isBlank()) {
            throw new IllegalArgumentException("autonomyLevel 不能为空");
        }
        AutonomyLevel level;
        try {
            level = AutonomyLevel.valueOf(levelStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的自治等级: " + levelStr + "，可选值: OBSERVE/SUGGEST/DRAFT/EXECUTE/ESCALATE");
        }
        String category = body.get("category");
        String description = body.get("description");
        PolicyRegistry.ToolPolicyMetadata updated = policyRegistry.updatePolicy(toolName, level, category, description);
        log.info("工具策略已动态更新(管理员操作): tool={}, level={}", toolName, level);
        return R.ok(updated);
    }

    /**
     * 手动触发从DB刷新策略。仅平台管理员可调用。
     */
    @Operation(summary = "从DB刷新策略（仅管理员）")
    @PostMapping("/refresh")
    public R<String> refresh() {
        requireAdmin();
        policyRegistry.refreshFromDb();
        return R.ok("策略已从DB刷新");
    }

    /**
     * 校验当前登录用户是否为平台管理员，否则抛 403。
     */
    private void requireAdmin() {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        if (loginUser == null || loginUser.getUser() == null) {
            throw new AccessDeniedException("未登录或登录态无效");
        }
        String userType = loginUser.getUser().getUserType();
        if (!ADMIN_USER_TYPE.equals(userType)) {
            throw new AccessDeniedException("仅平台管理员可修改工具策略（当前用户类型: " + userType + "）");
        }
    }
}
