package cn.qihangerp.service.statemachine;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 订单状态更新Service - 状态机集成示例
 *
 * 这是一个示例类，展示如何在Service层集成状态机进行状态校验和乐观锁更新
 * 实际生产使用时，可以将这些逻辑集成到现有的ShopOrderServiceImpl中
 *
 * @author AI Code Generator
 * @since 2026-10-10
 */
@Slf4j
@Service
public class OrderStatusUpdateService {

    private final OrderStateMachine stateMachine;

    public OrderStatusUpdateService() {
        this.stateMachine = new OrderStateMachine();
    }

    /**
     * 更新订单状态（带状态机校验和乐观锁）
     *
     * 这是一个完整的示例方法，展示了如何：
     * 1. 使用状态机校验状态迁移合法性
     * 2. 检查是否需要人工审批
     * 3. 使用乐观锁防止并发冲突
     *
     * @param orderId 订单ID（业务ID，如ORDER001）
     * @param orderDatabaseId 订单数据库主键ID
     * @param currentStatus 当前状态码
     * @param targetStatus 目标状态码
     * @param currentVersion 当前版本号（用于乐观锁）
     * @param operatorName 操作人姓名
     * @return 更新结果
     */
    public UpdateResult updateOrderStatus(
            String orderId,
            Long orderDatabaseId,
            Integer currentStatus,
            Integer targetStatus,
            Integer currentVersion,
            String operatorName) {

        // 步骤1: 状态机校验
        try {
            TransitionValidator.validateOrder(
                    stateMachine,
                    orderId,
                    currentStatus,
                    targetStatus
            );
        } catch (TransitionValidator.IllegalStateTransitionException e) {
            log.error("订单[{}]状态迁移不合法: {}", orderId, e.getMessage());
            return UpdateResult.failure("状态迁移不合法: " + e.getMessage());
        }

        // 步骤2: 检查是否需要审批
        boolean needsApproval = stateMachine.requiresApproval(currentStatus, targetStatus);
        if (needsApproval) {
            log.info("订单[{}]状态迁移需要审批: {} -> {}",
                    orderId,
                    OrderState.fromCode(currentStatus).getDescription(),
                    OrderState.fromCode(targetStatus).getDescription()
            );
            return UpdateResult.needApproval("该操作需要人工审批");
        }

        // 步骤3: 构造乐观锁更新条件（示例代码，实际需要使用Mapper）
        // LambdaUpdateWrapper<ShopOrder> updateWrapper = new LambdaUpdateWrapper<>();
        // updateWrapper
        //     .eq(ShopOrder::getId, orderDatabaseId)
        //     .eq(ShopOrder::getVersion, currentVersion)  // 乐观锁关键条件
        //     .set(ShopOrder::getOrderStatus, targetStatus)
        //     .set(ShopOrder::getVersion, currentVersion + 1)  // 版本号+1
        //     .set(ShopOrder::getUpdateBy, operatorName)
        //     .set(ShopOrder::getUpdateOn, LocalDateTime.now());
        //
        // boolean updated = shopOrderMapper.update(null, updateWrapper) > 0;

        // 模拟更新结果
        boolean updated = simulateOptimisticLockUpdate(
                orderDatabaseId,
                currentVersion,
                targetStatus
        );

        if (updated) {
            log.info("订单[{}]状态更新成功: {} -> {}，操作人: {}",
                    orderId,
                    OrderState.fromCode(currentStatus).getDescription(),
                    OrderState.fromCode(targetStatus).getDescription(),
                    operatorName
            );
            return UpdateResult.success("订单状态更新成功");
        } else {
            log.warn("订单[{}]更新失败，可能被其他请求修改（版本号不匹配）", orderId);
            return UpdateResult.failure("更新失败，订单可能已被其他人修改，请刷新后重试");
        }
    }

    /**
     * 模拟乐观锁更新逻辑
     *
     * 实际生产代码应该替换为：
     * return shopOrderMapper.update(null, updateWrapper) > 0;
     */
    private boolean simulateOptimisticLockUpdate(
            Long orderDatabaseId,
            Integer currentVersion,
            Integer targetStatus) {

        // 模拟成功更新
        return true;

        // 实际SQL会是这样的：
        // UPDATE oms_shop_order
        // SET order_status = #{targetStatus},
        //     version = version + 1,
        //     update_on = NOW()
        // WHERE id = #{orderDatabaseId}
        //   AND version = #{currentVersion};  -- 乐观锁关键条件
        //
        // 如果version不匹配，影响行数为0，返回false
    }

    /**
     * 获取订单当前可执行的操作列表
     *
     * 用于前端展示"可执行操作"按钮
     *
     * @param currentStatus 订单当前状态码
     * @return 可迁移的状态列表
     */
    public java.util.List<OrderState> getAvailableActions(Integer currentStatus) {
        return stateMachine.getAvailableTransitions(currentStatus);
    }

    /**
     * 检查操作是否需要审批
     *
     * @param currentStatus 当前状态码
     * @param targetStatus 目标状态码
     * @return true=需要审批，false=可直接执行
     */
    public boolean requiresApproval(Integer currentStatus, Integer targetStatus) {
        return stateMachine.requiresApproval(currentStatus, targetStatus);
    }

    /**
     * 更新结果封装类
     */
    public static class UpdateResult {
        private final boolean success;
        private final String message;
        private final UpdateStatus status;

        private UpdateResult(boolean success, String message, UpdateStatus status) {
            this.success = success;
            this.message = message;
            this.status = status;
        }

        public static UpdateResult success(String message) {
            return new UpdateResult(true, message, UpdateStatus.SUCCESS);
        }

        public static UpdateResult failure(String message) {
            return new UpdateResult(false, message, UpdateStatus.FAILURE);
        }

        public static UpdateResult needApproval(String message) {
            return new UpdateResult(false, message, UpdateStatus.NEED_APPROVAL);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public UpdateStatus getStatus() {
            return status;
        }

        public enum UpdateStatus {
            SUCCESS,        // 更新成功
            FAILURE,        // 更新失败（状态不合法或并发冲突）
            NEED_APPROVAL   // 需要人工审批
        }
    }
}
