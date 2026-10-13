package cn.qihangerp.agent.trace;

import cn.qihangerp.statemachine.IllegalTransitionException;
import cn.qihangerp.statemachine.StateTransitionEvent;
import cn.qihangerp.statemachine.TransitionValidator;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 状态机→Trace 全链路真实DB集成测试。
 *
 * <p>手动搭建"状态机校验 → 发布事件 → 监听器接收 → agent_trace 落库"
 * 的完整链路（不依赖 Spring 上下文），验证状态机确实并入了监视环节，
 * 每次状态迁移（通过/拦截）都能在 agent_trace 表中追溯。</p>
 */
class StateMachineTraceIntegrationTest {

    private TransitionValidator validator;
    private TraceMapper traceMapper;
    private JdbcTemplate jdbcTemplate;
    private String testOrderId;
    private String testRefundId;

    @BeforeEach
    void setUp() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/qihang-erp"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        ds.setUser("root");
        ds.setPassword("1234");

        jdbcTemplate = new JdbcTemplate((DataSource) ds);

        Environment environment = new Environment("sm-test", new JdbcTransactionFactory(), (DataSource) ds);
        MybatisConfiguration config = new MybatisConfiguration(environment);
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(TraceMapper.class);
        SqlSessionFactory sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(config);
        traceMapper = sqlSessionFactory.openSession(true).getMapper(TraceMapper.class);

        // 监听器（直接持有 mapper，模拟 Spring 容器中的 bean）
        StateTransitionTraceListener listener = new StateTransitionTraceListener(traceMapper);

        // 手动事件发布器：把事件直接分发给监听器（模拟 Spring 的事件机制）
        ApplicationEventPublisher publisher = new ApplicationEventPublisher() {
            @Override
            public void publishEvent(ApplicationEvent event) {
                if (event instanceof StateTransitionEvent) {
                    listener.onStateTransition((StateTransitionEvent) event);
                }
            }
            @Override
            public void publishEvent(Object event) {
                if (event instanceof StateTransitionEvent) {
                    listener.onStateTransition((StateTransitionEvent) event);
                }
            }
        };
        validator = new TransitionValidator(publisher);

        testOrderId = "SM-TEST-ORD-" + System.currentTimeMillis();
        testRefundId = "SM-TEST-REF-" + System.currentTimeMillis();
    }

    @Test
    void validOrderTransition_isRecordedInTrace() {
        // 合法迁移：待发货 → 已发货
        assertDoesNotThrow(() -> validator.validateOrderTransition(1, 2, testOrderId));

        List<TraceEntry> chain = traceMapper.findByTraceId("SM-ORDER-" + testOrderId);
        assertFalse(chain.isEmpty(), "合法迁移应在 agent_trace 中留痕");
        TraceEntry e = chain.get(chain.size() - 1);
        assertEquals("STATE_MACHINE", e.getToolName());
        assertEquals("ORDER", e.getToolCategory());
        assertEquals("SUCCESS", e.getStatus());
        assertTrue(e.getInputParams().contains("待发货"));
        assertTrue(e.getInputParams().contains("已发货"));

        System.out.println("合法订单迁移trace验证通过: traceId=SM-ORDER-" + testOrderId);
    }

    @Test
    void invalidOrderTransition_isRecordedAndThrows() {
        // 非法迁移：待发货 → 已完成（跳过已发货）
        assertThrows(IllegalTransitionException.class,
                () -> validator.validateOrderTransition(1, 3, testOrderId));

        List<TraceEntry> chain = traceMapper.findByTraceId("SM-ORDER-" + testOrderId);
        assertFalse(chain.isEmpty(), "非法拦截也应在 agent_trace 中留痕");
        TraceEntry e = chain.get(chain.size() - 1);
        assertEquals("BLOCKED", e.getStatus());
        assertEquals("ILLEGAL_TRANSITION", e.getErrorType());
        assertNotNull(e.getErrorMessage());

        System.out.println("非法订单迁移拦截trace验证通过: traceId=SM-ORDER-" + testOrderId);
    }

    @Test
    void validRefundTransition_isRecordedInTrace() {
        assertDoesNotThrow(() -> validator.validateRefundTransition(2, 3, testRefundId));

        List<TraceEntry> chain = traceMapper.findByTraceId("SM-REFUND-" + testRefundId);
        assertFalse(chain.isEmpty(), "合法售后迁移应在 agent_trace 中留痕");
        assertEquals("REFUND", chain.get(chain.size() - 1).getToolCategory());

        System.out.println("合法售后迁移trace验证通过: traceId=SM-REFUND-" + testRefundId);
    }

    @Test
    void invalidRefundTransition_isRecordedAndThrows() {
        assertThrows(IllegalTransitionException.class,
                () -> validator.validateRefundTransition(0, 4, testRefundId));

        List<TraceEntry> chain = traceMapper.findByTraceId("SM-REFUND-" + testRefundId);
        assertFalse(chain.isEmpty(), "非法售后拦截应在 agent_trace 中留痕");
        assertEquals("BLOCKED", chain.get(chain.size() - 1).getStatus());

        System.out.println("非法售后迁移拦截trace验证通过: traceId=SM-REFUND-" + testRefundId);
    }
}
