package cn.qihangerp.agent.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分层记忆单元测试（魔改方案 Week6-7）
 *
 * 不启动 Spring、不依赖外部 Redis/MySQL：
 * WORKING 层只走进程内存，可直接验证「记住名字 / 跨租户隔离 / 脱敏 / 过期」核心逻辑。
 * SESSION / USER / TEAM 的介质验证见完成报告中的手动接口验证步骤。
 *
 * @author Doubao
 * @since 2026-10-12
 */
class LayeredMemoryTest {

    private MemoryManager memoryManager;

    @BeforeEach
    void setUp() {
        // WORKING 路径不触碰 Redis / Repository，构造参数传 null
        memoryManager = new MemoryManager(null, null);
    }

    /** 验收：用户说“我叫张三”，下次能记住 */
    @Test
    void workingMemory_shouldRememberName() {
        memoryManager.store(MemoryLayer.WORKING, "conv_1", "user_name", "我叫张三", 1001L, 1L);
        String value = memoryManager.retrieve(MemoryLayer.WORKING, "conv_1", "user_name", 1001L);
        assertEquals("我叫张三", value);
    }

    /** 验收：跨租户不可见 */
    @Test
    void workingMemory_crossTenant_shouldBeInvisible() {
        memoryManager.store(MemoryLayer.WORKING, "conv_1", "secret", "机密", 1001L, 1L);
        String other = memoryManager.retrieve(MemoryLayer.WORKING, "conv_1", "secret", 2002L);
        assertNull(other, "其他商户不应读到该记忆");
    }

    /** 不存在的记忆返回 null */
    @Test
    void workingMemory_notExist_returnsNull() {
        assertNull(memoryManager.retrieve(MemoryLayer.WORKING, "conv_x", "nope", 1001L));
    }

    /** 过期条目判定 */
    @Test
    void expiredEntry_shouldBeTreatedAsExpired() {
        MemoryEntry e = MemoryEntry.create(MemoryLayer.WORKING, "conv_1", "k", "v", 1001L, 1L);
        e.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertTrue(e.isExpired());
    }

    /** 记忆ID格式：layer:scopeId:key */
    @Test
    void memoryId_shouldFollowLayerScopeKeyFormat() {
        MemoryEntry e = MemoryEntry.create(MemoryLayer.USER, "user_1", "preference", "v", 1001L, 1L);
        assertEquals("USER:user_1:preference", e.getMemoryId());
    }

    /** 手机号脱敏 */
    @Test
    void phone_shouldBeMasked() {
        assertEquals("138****1234", SensitiveDataMasker.maskPhone("13812341234"));
    }

    /** 身份证脱敏 */
    @Test
    void idCard_shouldBeMasked() {
        assertEquals("110101******1234", SensitiveDataMasker.maskIdCard("110101199001011234"));
    }

    /** 银行卡脱敏 */
    @Test
    void bankCard_shouldBeMasked() {
        assertEquals("6222 **** **** 1234", SensitiveDataMasker.maskBankCard("6222021234567891234"));
    }

    /** 邮箱脱敏 */
    @Test
    void email_shouldBeMasked() {
        assertEquals("abc***@example.com", SensitiveDataMasker.maskEmail("abcdefg@example.com"));
    }

    /** 验收：记忆中的手机号不出现明文 */
    @Test
    void storeWithPhone_shouldNotKeepPlaintext() {
        memoryManager.store(MemoryLayer.WORKING, "conv_1", "phone", "我的手机号是13812341234", 1001L, 1L);
        String value = memoryManager.retrieve(MemoryLayer.WORKING, "conv_1", "phone", 1001L);
        assertNotNull(value);
        assertFalse(value.contains("13812341234"), "记忆中不应保留手机号明文");
        assertTrue(value.contains("138****1234"));
    }
}
