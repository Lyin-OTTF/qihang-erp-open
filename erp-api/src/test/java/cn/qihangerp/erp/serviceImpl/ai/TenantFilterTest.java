package cn.qihangerp.erp.serviceImpl.ai;

import cn.qihangerp.agent.tenant.TenantContext;
import cn.qihangerp.model.entity.ShopOrder;
import cn.qihangerp.service.ShopOrderService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具层租户数据过滤测试。
 *
 * 用动态代理拦截 ShopOrderService.list(wrapper)，捕获实际执行的查询条件，
 * 验证：
 * 1. 设置 TenantContext 后，wrapper 包含 merchant_id 过滤
 * 2. 未设置 TenantContext 时，wrapper 不包含 merchant_id（向后兼容）
 *
 * 其余 10 个工具使用完全相同的 wrapper.eq(Entity::getMerchantId, merchantId) 模式，
 * OrderTools 为代表用例。
 *
 * @author 回溯开发-阶段1
 * @since 2026-10-12
 */
class TenantFilterTest {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        // 纯单测无 Spring 上下文，需手动初始化 MyBatis-Plus 的 TableInfo 缓存，
        // 否则 LambdaQueryWrapper.getSqlSegment() 会报 "can not find lambda cache"
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ShopOrder.class
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ShopOrderService capturingService(AtomicReference<Wrapper<ShopOrder>> captured) {
        return (ShopOrderService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{ShopOrderService.class},
                (proxy, method, args) -> {
                    if ("list".equals(method.getName())
                            && args != null && args.length == 1 && args[0] instanceof Wrapper) {
                        captured.set((Wrapper<ShopOrder>) args[0]);
                        return Collections.emptyList();
                    }
                    return null;
                }
        );
    }

    @Test
    void orderTools_addsMerchantIdFilterWhenTenantSet() {
        AtomicReference<Wrapper<ShopOrder>> captured = new AtomicReference<>();
        OrderTools orderTools = new OrderTools(capturingService(captured));

        TenantContext.setTenantId(123L);
        orderTools.getOrderList(30, null, 10);

        Wrapper<ShopOrder> wrapper = captured.get();
        assertNotNull(wrapper, "OrderTools 应调用 orderService.list(wrapper)");

        String sqlSegment = wrapper.getSqlSegment();
        assertNotNull(sqlSegment, "wrapper.getSqlSegment() 不应为 null");
        assertTrue(sqlSegment.contains("merchant_id"),
                "设置租户后查询条件应包含 merchant_id 过滤，实际: " + sqlSegment);
    }

    @Test
    void orderTools_noMerchantIdFilterWhenTenantNotSet() {
        AtomicReference<Wrapper<ShopOrder>> captured = new AtomicReference<>();
        OrderTools orderTools = new OrderTools(capturingService(captured));

        // 不设置 TenantContext
        orderTools.getOrderList(30, null, 10);

        Wrapper<ShopOrder> wrapper = captured.get();
        assertNotNull(wrapper);

        String sqlSegment = wrapper.getSqlSegment();
        assertNotNull(sqlSegment);
        assertFalse(sqlSegment.contains("merchant_id"),
                "未设置租户时查询条件不应包含 merchant_id 过滤，实际: " + sqlSegment);
    }
}
