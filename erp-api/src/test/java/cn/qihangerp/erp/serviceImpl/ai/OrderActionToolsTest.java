package cn.qihangerp.erp.serviceImpl.ai;

import cn.qihangerp.agent.tenant.TenantContext;
import cn.qihangerp.model.entity.ShopOrder;
import cn.qihangerp.service.ShopOrderService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OrderActionTools 写操作工具单元测试。
 *
 * <p>用动态代理 mock ShopOrderService，验证：
 * 1. 订单存在且属于当前租户时，调用 updateById 且只传 id+sellerMemo
 * 2. 订单不存在或不属于当前租户时，不调用 updateById，返回错误
 * 3. updateById 返回 false 时返回失败信息
 * </p>
 *
 * <p>注意：ESCALATE 审批拦截由 ToolGateway 负责，已在 GatewayToolCallbackTest 中覆盖。
 * 本测试只验证工具方法本身的业务逻辑。</p>
 *
 * @author 回溯开发-阶段2
 * @since 2026-10-12
 */
class OrderActionToolsTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 创建 mock service：getOne 返回指定订单，updateById 捕获参数并返回指定结果
     */
    private ShopOrderService mockService(ShopOrder getOneResult, boolean updateResult,
                                         AtomicReference<ShopOrder> capturedUpdate,
                                         AtomicBoolean updateCalled) {
        return (ShopOrderService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{ShopOrderService.class},
                (proxy, method, args) -> {
                    if ("getOne".equals(method.getName())) {
                        return getOneResult;
                    }
                    if ("updateById".equals(method.getName()) && args != null && args.length >= 1) {
                        updateCalled.set(true);
                        if (args[0] instanceof ShopOrder) {
                            capturedUpdate.set((ShopOrder) args[0]);
                        }
                        return updateResult;
                    }
                    // IService 接口的其他默认方法可能被调用，返回null
                    return null;
                }
        );
    }

    @Test
    void updateOrderSellerMemo_success_whenOrderBelongsToTenant() {
        ShopOrder existing = new ShopOrder();
        existing.setId(100L);
        existing.setMerchantId(500L);
        existing.setSellerMemo("旧备注");

        AtomicReference<ShopOrder> captured = new AtomicReference<>();
        AtomicBoolean updateCalled = new AtomicBoolean(false);
        ShopOrderService mockSvc = mockService(existing, true, captured, updateCalled);

        OrderActionTools tools = new OrderActionTools(mockSvc);
        TenantContext.setTenantId(500L);

        String result = tools.updateOrderSellerMemo(100L, "新的商家备注");

        assertTrue(result.contains("已更新"), "应返回成功信息，实际: " + result);
        assertTrue(updateCalled.get(), "应调用 updateById");

        ShopOrder updateEntity = captured.get();
        assertNotNull(updateEntity);
        assertEquals(100L, updateEntity.getId(), "更新实体应携带正确的订单ID");
        assertEquals("新的商家备注", updateEntity.getSellerMemo(), "应更新为新备注");
        // 关键：只更新了 id 和 sellerMemo，其他字段为 null（MyBatis-Plus 只更新非null字段）
        assertNull(updateEntity.getOrderStatus(), "不应携带 orderStatus（避免误更新）");
        assertNull(updateEntity.getOrderAmount(), "不应携带 orderAmount（避免误更新）");
        assertNull(updateEntity.getMerchantId(), "不应携带 merchantId（避免误更新）");
    }

    @Test
    void updateOrderSellerMemo_blocked_whenOrderNotInTenant() {
        // 订单属于租户500，但当前租户是999
        ShopOrder existing = new ShopOrder();
        existing.setId(100L);
        existing.setMerchantId(500L);

        AtomicReference<ShopOrder> captured = new AtomicReference<>();
        AtomicBoolean updateCalled = new AtomicBoolean(false);
        // getOne 返回 null（因为 wrapper 中加了 merchant_id=999 条件，查不到）
        ShopOrderService mockSvc = mockService(null, true, captured, updateCalled);

        OrderActionTools tools = new OrderActionTools(mockSvc);
        TenantContext.setTenantId(999L);

        String result = tools.updateOrderSellerMemo(100L, "恶意修改备注");

        assertTrue(result.contains("不存在或不属于"), "跨租户操作应被拒绝，实际: " + result);
        assertFalse(updateCalled.get(), "跨租户时不应调用 updateById");
    }

    @Test
    void updateOrderSellerMemo_returnsFailure_whenUpdateFails() {
        ShopOrder existing = new ShopOrder();
        existing.setId(100L);
        existing.setMerchantId(500L);

        AtomicReference<ShopOrder> captured = new AtomicReference<>();
        AtomicBoolean updateCalled = new AtomicBoolean(false);
        ShopOrderService mockSvc = mockService(existing, false, captured, updateCalled);

        OrderActionTools tools = new OrderActionTools(mockSvc);
        TenantContext.setTenantId(500L);

        String result = tools.updateOrderSellerMemo(100L, "备注");

        assertTrue(result.contains("失败"), "updateById返回false时应返回失败信息，实际: " + result);
        assertTrue(updateCalled.get(), "应调用过 updateById");
    }

    @Test
    void updateOrderSellerMemo_worksWithoutTenantContext() {
        // 不设置 TenantContext → merchantId=null → 查询不加租户条件（向后兼容）
        ShopOrder existing = new ShopOrder();
        existing.setId(100L);

        AtomicReference<ShopOrder> captured = new AtomicReference<>();
        AtomicBoolean updateCalled = new AtomicBoolean(false);
        ShopOrderService mockSvc = mockService(existing, true, captured, updateCalled);

        OrderActionTools tools = new OrderActionTools(mockSvc);
        // 不设置 TenantContext

        String result = tools.updateOrderSellerMemo(100L, "无租户备注");

        assertTrue(result.contains("已更新"), "无租户上下文时应正常执行，实际: " + result);
        assertTrue(updateCalled.get());
    }
}
