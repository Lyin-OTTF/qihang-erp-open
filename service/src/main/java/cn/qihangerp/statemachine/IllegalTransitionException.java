package cn.qihangerp.statemachine;

/**
 * 非法状态迁移异常。
 *
 * @author 状态机下沉
 * @since 2026-10-13
 */
public class IllegalTransitionException extends RuntimeException {
    public IllegalTransitionException(String message) {
        super(message);
    }
}
