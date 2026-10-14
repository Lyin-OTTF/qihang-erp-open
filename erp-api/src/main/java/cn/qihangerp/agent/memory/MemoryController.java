package cn.qihangerp.agent.memory;

import cn.qihangerp.agent.tenant.TenantAuthorizer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆管理接口
 *
 * 租户以登录态为准：merchantId 参数仅平台管理员可显式指定，商户用户传入其它租户将被拒绝。
 */
@RestController
@RequestMapping("/api/agent/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryManager memoryManager;
    private final TenantAuthorizer tenantAuthorizer;

    /**
     * 存储记忆
     */
    @PostMapping("/store")
    public Map<String, Object> store(@RequestBody Map<String, Object> request) {
        String layer = (String) request.get("layer");
        String scopeId = (String) request.get("scopeId");
        String key = (String) request.get("key");
        String value = (String) request.get("value");
        Long requestedMerchantId = request.get("merchantId") != null
                ? Long.valueOf(request.get("merchantId").toString()) : null;
        Long merchantId = tenantAuthorizer.requireTenant(requestedMerchantId);
        Long userId = request.get("userId") != null ? Long.valueOf(request.get("userId").toString()) : null;

        memoryManager.store(
                MemoryLayer.valueOf(layer),
                scopeId,
                key,
                value,
                merchantId,
                userId
        );

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "记忆已存储");
        return result;
    }

    /**
     * 读取记忆
     */
    @GetMapping("/retrieve")
    public Map<String, Object> retrieve(
            @RequestParam String layer,
            @RequestParam String scopeId,
            @RequestParam String key,
            @RequestParam(required = false) Long merchantId
    ) {
        Long effectiveTenant = tenantAuthorizer.requireTenant(merchantId);
        String value = memoryManager.retrieve(
                MemoryLayer.valueOf(layer),
                scopeId,
                key,
                effectiveTenant
        );

        Map<String, Object> result = new HashMap<>();
        result.put("success", value != null);
        result.put("value", value);
        return result;
    }

    /**
     * 删除记忆
     */
    @DeleteMapping("/delete")
    public Map<String, Object> delete(
            @RequestParam String layer,
            @RequestParam String scopeId,
            @RequestParam String key,
            @RequestParam(required = false) Long merchantId
    ) {
        Long effectiveTenant = tenantAuthorizer.requireTenant(merchantId);
        memoryManager.delete(
                MemoryLayer.valueOf(layer),
                scopeId,
                key,
                effectiveTenant
        );

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "记忆已删除");
        return result;
    }

    /**
     * 列出某个作用域的所有记忆键
     */
    @GetMapping("/list")
    public Map<String, Object> listKeys(
            @RequestParam String layer,
            @RequestParam String scopeId,
            @RequestParam(required = false) Long merchantId
    ) {
        Long effectiveTenant = tenantAuthorizer.requireTenant(merchantId);
        List<String> keys = memoryManager.listKeys(
                MemoryLayer.valueOf(layer),
                scopeId,
                effectiveTenant
        );

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("keys", keys);
        result.put("count", keys.size());
        return result;
    }

    /**
     * 清空某个作用域的所有记忆
     */
    @DeleteMapping("/clear")
    public Map<String, Object> clearScope(
            @RequestParam String layer,
            @RequestParam String scopeId,
            @RequestParam(required = false) Long merchantId
    ) {
        Long effectiveTenant = tenantAuthorizer.requireTenant(merchantId);
        memoryManager.clearScope(
                MemoryLayer.valueOf(layer),
                scopeId,
                effectiveTenant
        );

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "作用域记忆已清空");
        return result;
    }

    /**
     * 获取记忆统计信息
     */
    @GetMapping("/stats")
    public Map<String, Object> getStatistics() {
        return memoryManager.getStatistics();
    }

    /**
     * 手动清理过期记忆
     */
    @PostMapping("/cleanup")
    public Map<String, Object> cleanup() {
        memoryManager.cleanupExpiredMemories();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "过期记忆清理完成");
        return result;
    }
}
