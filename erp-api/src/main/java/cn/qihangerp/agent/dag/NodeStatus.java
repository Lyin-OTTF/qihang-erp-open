package cn.qihangerp.agent.dag;

/**
 * DAG节点执行状态枚举。
 *
 * @author 回溯开发-阶段4
 * @since 2026-10-12
 */
public enum NodeStatus {
    /** 等待执行 */
    PENDING,
    /** 正在执行 */
    RUNNING,
    /** 执行成功 */
    SUCCESS,
    /** 执行失败 */
    FAILED,
    /** 因依赖失败被取消 */
    CANCELLED,
    /** 因条件不满足被跳过 */
    SKIPPED
}
