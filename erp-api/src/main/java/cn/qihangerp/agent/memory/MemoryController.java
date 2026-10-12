package cn.qihangerp.agent.memory;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆管理接口
 *
 * 提供记忆的存储、读取、删除等操作
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@RestController
@RequestMapping("/api/agent/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryManager memoryManager;

    /**
     * 存储记忆
     *
     * POST /api/agent/memory/store
     * Body: {
     *   "layer": "USER",
     *   "scopeId": "user_123",
     *   "key": "preference",
     *   "value": "喜欢简洁回答",
     *   "merchantId": 1001,
     *   "userId": 123
     * }
     */
    @PostMapping("/store")
    public Map<String, Object> store(@RequestBody Map<String, Object> request) {
        String layer = (String) request.get("layer");
        String scopeId = (String) request.get("scopeId");
        String key = (String) request.get("key");
        String value = (String) request.get("value");
        Long merchantId = Long.valueOf(request.get("merchantId").toString());
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
     *
     * GET /api/agent/memory/retrieve?layer=USER&scopeId=user_123&key=preference&merchantId=1001
     */
    @GetMapping("/retrieve")
    public Map<String, Object> retrieve(
            @RequestParam String layer,
            @RequestParam String scopeId,
            @RequestParam String key,
            @RequestParam Long merchantId
    ) {
        String value = memoryManager.retrieve(
                MemoryLayer.valueOf(layer),
                scopeId,
                key,
                merchantId
        );

        Map<String, Object> result = new HashMap<>();
        result.put("success", value != null);
        result.put("value", value);
        return result;
    }

    /**
     * 删除记忆
     *
     * DELETE /api/agent/memory/delete?layer=USER&scopeId=user_123&key=preference&merchantId=1001
     */
    @DeleteMapping("/delete")
    public Map<String, Object> delete(
            @RequestParam String layer,
            @RequestParam String scopeId,
            @RequestParam String key,
            @RequestParam Long merchantId
    ) {
        memoryManager.delete(
                MemoryLayer.valueOf(layer),
                scopeId,
                key,
                merchantId
        );

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "记忆已删除");
        return result;
    }

    /**
     * 列出某个作用域的所有记忆键
     *
     * GET /api/agent/memory/list?layer=USER&scopeId=user_123&merchantId=1001
     */
    @GetMapping("/list")
    public Map<String, Object> listKeys(
            @RequestParam String layer,
            @RequestParam String scopeId,
            @RequestParam Long merchantId
    ) {
        List<String> keys = memoryManager.listKeys(
                MemoryLayer.valueOf(layer),
                scopeId,
                merchantId
        );

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("keys", keys);
        result.put("count", keys.size());
        return result;
    }

    /**
     * 清空某个作用域的所有记忆
     *
     * DELETE /api/agent/memory/clear?layer=SESSION&scopeId=session_abc&merchantId=1001
     */
    @DeleteMapping("/clear")
    public Map<String, Object> clearScope(
            @RequestParam String layer,
            @RequestParam String scopeId,
            @RequestParam Long merchantId
    ) {
        memoryManager.clearScope(
                MemoryLayer.valueOf(layer),
                scopeId,
                merchantId
        );

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "作用域记忆已清空");
        return result;
    }

    /**
     * 获取记忆统计信息
     *
     * GET /api/agent/memory/stats
     */
    @GetMapping("/stats")
    public Map<String, Object> getStatistics() {
        return memoryManager.getStatistics();
    }

    /**
     * 手动清理过期记忆
     *
     * POST /api/agent/memory/cleanup
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
