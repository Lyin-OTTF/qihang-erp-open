package cn.qihangerp.statemachine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 售后单状态机。
 *
 * <p>定义 ShopRefund.status 的合法状态迁移规则。</p>
 *
 * <p>状态编码：
 * <ul>
 *   <li>0 售后申请</li>
 *   <li>1 售后关闭</li>
 *   <li>2 售后处理中</li>
 *   <li>3 退款中</li>
 *   <li>4 售后成功</li>
 *   <li>5 待用户处理</li>
 *   <li>6 待买家发货</li>
 *   <li>8 平台处理中</li>
 * </ul>
 * </p>
 *
 * @author 状态机下沉
 * @since 2026-10-13
 */
public class RefundStateMachine {

    public static final Map<Integer, String> STATE_NAMES = new HashMap<>();
    static {
        STATE_NAMES.put(0, "售后申请");
        STATE_NAMES.put(1, "售后关闭");
        STATE_NAMES.put(2, "售后处理中");
        STATE_NAMES.put(3, "退款中");
        STATE_NAMES.put(4, "售后成功");
        STATE_NAMES.put(5, "待用户处理");
        STATE_NAMES.put(6, "待买家发货");
        STATE_NAMES.put(8, "平台处理中");
    }

    private static final Map<Integer, Set<Integer>> TRANSITIONS = new HashMap<>();

    static {
        addTransition(0, 2, 1, 8);
        addTransition(2, 3, 6, 5, 1, 8);
        addTransition(6, 2, 1);
        addTransition(5, 2, 1);
        addTransition(3, 4, 1, 2);
        addTransition(8, 2, 3, 1, 4);
    }

    private static void addTransition(int from, int... toStates) {
        Set<Integer> set = TRANSITIONS.computeIfAbsent(from, k -> new HashSet<>());
        for (int to : toStates) {
            set.add(to);
        }
    }

    public static boolean canTransition(Integer fromState, Integer toState) {
        if (fromState == null || toState == null) {
            return false;
        }
        if (fromState.equals(toState)) {
            return true;
        }
        Set<Integer> allowed = TRANSITIONS.get(fromState);
        return allowed != null && allowed.contains(toState);
    }

    public static Set<Integer> getAllowedTransitions(Integer fromState) {
        if (fromState == null) {
            return new HashSet<>();
        }
        return TRANSITIONS.getOrDefault(fromState, new HashSet<>());
    }

    public static boolean isTerminal(Integer state) {
        if (state == null) return false;
        return state == 4 || state == 1;
    }

    public static String getStateName(Integer state) {
        if (state == null) return "未知";
        return STATE_NAMES.getOrDefault(state, "未知(" + state + ")");
    }
}
