package cn.qihangerp.agent.statemachine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 订单状态机。
 *
 * <p>定义 ShopOrder.orderStatus 的合法状态迁移规则，禁止非法跳转
 * （如"待发货"直接跳"已完成"而不经过"已发货"）。</p>
 *
 * <p>状态编码（来自 ShopOrder 实体注释）：
 * <ul>
 *   <li>0  新订单</li>
 *   <li>1  待发货</li>
 *   <li>2  已发货</li>
 *   <li>3  已完成</li>
 *   <li>11 已取消</li>
 *   <li>12 退款中</li>
 *   <li>13 已关闭</li>
 *   <li>21 待付款</li>
 *   <li>22 锁定</li>
 *   <li>29 删除</li>
 *   <li>101 部分发货</li>
 * </ul>
 * </p>
 *
 * @author 回溯开发-搁置项攻关
 * @since 2026-10-12
 */
public class OrderStateMachine {

    /** 状态显示名 */
    public static final Map<Integer, String> STATE_NAMES = new HashMap<>();
    static {
        STATE_NAMES.put(0, "新订单");
        STATE_NAMES.put(1, "待发货");
        STATE_NAMES.put(2, "已发货");
        STATE_NAMES.put(3, "已完成");
        STATE_NAMES.put(11, "已取消");
        STATE_NAMES.put(12, "退款中");
        STATE_NAMES.put(13, "已关闭");
        STATE_NAMES.put(21, "待付款");
        STATE_NAMES.put(22, "锁定");
        STATE_NAMES.put(29, "删除");
        STATE_NAMES.put(101, "部分发货");
    }

    /** 合法迁移表：fromState -> 允许的 toState 集合 */
    private static final Map<Integer, Set<Integer>> TRANSITIONS = new HashMap<>();

    static {
        // 新订单 → 待付款/待发货/已取消
        addTransition(0, 21, 1, 11);
        // 待付款 → 待发货/已取消/已关闭
        addTransition(21, 1, 11, 13);
        // 待发货 → 已发货/部分发货/已取消/退款中
        addTransition(1, 2, 101, 11, 12);
        // 部分发货 → 已发货/退款中
        addTransition(101, 2, 12);
        // 已发货 → 已完成/退款中
        addTransition(2, 3, 12);
        // 退款中 → 已完成/已关闭/已取消
        addTransition(12, 3, 13, 11);
        // 已完成 → （终态，不允许迁移）
        // 已取消 → （终态）
        // 已关闭 → （终态）
        // 锁定 → 待发货/已取消
        addTransition(22, 1, 11);
        // 删除 → （终态）
    }

    private static void addTransition(int from, int... toStates) {
        Set<Integer> set = TRANSITIONS.computeIfAbsent(from, k -> new HashSet<>());
        for (int to : toStates) {
            set.add(to);
        }
    }

    /**
     * 校验状态迁移是否合法
     *
     * @param fromState 源状态
     * @param toState 目标状态
     * @return true=合法，false=非法
     */
    public static boolean canTransition(Integer fromState, Integer toState) {
        if (fromState == null || toState == null) {
            return false;
        }
        if (fromState.equals(toState)) {
            return true; // 相同状态视为合法（无操作）
        }
        Set<Integer> allowed = TRANSITIONS.get(fromState);
        return allowed != null && allowed.contains(toState);
    }

    /**
     * 获取某状态允许迁移到的所有目标状态
     */
    public static Set<Integer> getAllowedTransitions(Integer fromState) {
        if (fromState == null) {
            return new HashSet<>();
        }
        return TRANSITIONS.getOrDefault(fromState, new HashSet<>());
    }

    /**
     * 判断是否为终态（不允许再迁移）
     */
    public static boolean isTerminal(Integer state) {
        if (state == null) return false;
        // 终态：已完成、已取消、已关闭、删除
        return state == 3 || state == 11 || state == 13 || state == 29;
    }

    /**
     * 获取状态显示名
     */
    public static String getStateName(Integer state) {
        if (state == null) return "未知";
        return STATE_NAMES.getOrDefault(state, "未知(" + state + ")");
    }
}
