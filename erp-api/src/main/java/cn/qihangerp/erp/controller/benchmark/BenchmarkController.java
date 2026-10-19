package cn.qihangerp.erp.controller.benchmark;

import cn.qihangerp.common.AjaxResult;
import cn.qihangerp.model.bo.InventoryChangeCommand;
import cn.qihangerp.model.entity.OGoods;
import cn.qihangerp.service.OGoodsInventoryService;
import cn.qihangerp.service.OGoodsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 压测专用接口（仅在 benchmark profile 下加载）。
 *
 * <p>存在的意义：让 benchmark/k6、benchmark/jmeter 里的脚本可以打真实的服务端路径，
 * 而不是只测"空转"的 ping。生产环境不激活该 profile，本 Controller 不会被注册，
 * 因此对应的 permitAll 规则在生产是惰性的。</p>
 *
 * <p>启动方式：</p>
 * <pre>
 * java -jar erp-api.jar --spring.profiles.active=benchmark
 * </pre>
 *
 * @author qihang
 */
@Slf4j
@Profile("benchmark")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/benchmark")
public class BenchmarkController {

    private static final String CACHE_PREFIX = "bench:goods:";

    private final OGoodsInventoryService inventoryService;
    private final OGoodsService goodsService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 健康检查/预热用。
     */
    @GetMapping("/ping")
    public AjaxResult ping() {
        return AjaxResult.success("pong");
    }

    /**
     * 库存扣减压测入口：走与生产完全一致的 changeStock 链路
     * （幂等校验 + 条件原子更新 + 同事务写流水）。
     *
     * @param inventoryId 库存ID
     * @param quantity    扣减数量
     * @param key         幂等键，不传则每次生成（用于测吞吐）；传固定值用于测幂等
     */
    @PostMapping("/stock/deduct")
    public AjaxResult deduct(@RequestParam Long inventoryId,
                             @RequestParam(defaultValue = "1") Integer quantity,
                             @RequestParam(required = false) String key) {
        InventoryChangeCommand cmd = InventoryChangeCommand.of(inventoryId, quantity,
                InventoryChangeCommand.TYPE_OUT);
        cmd.setIdempotencyKey(key != null && !key.isEmpty() ? key : "BENCH:" + UUID.randomUUID());
        cmd.setBizType(InventoryChangeCommand.BIZ_ORDER_OUT);
        cmd.setBizNum("BENCH");
        cmd.setOperator("benchmark");
        try {
            boolean ok = inventoryService.changeStock(cmd);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", ok);
            return AjaxResult.success(body);
        } catch (Exception e) {
            // 库存不足是压测的预期结果之一，不应作为 500 打断压测
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("error", e.getClass().getSimpleName());
            return AjaxResult.success(body);
        }
    }

    /**
     * 缓存命中/未命中对比入口。
     *
     * @param cached true=走 Redis（未命中则回源并写缓存），false=直接查库，用于对照
     */
    @GetMapping("/goods/{id}")
    public AjaxResult goods(@PathVariable("id") Long id,
                            @RequestParam(defaultValue = "true") boolean cached) {
        long begin = System.nanoTime();
        OGoods goods = cached ? readThroughCache(id) : goodsService.getById(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", goods == null ? null : goods.getId());
        body.put("cached", cached);
        body.put("costNanos", System.nanoTime() - begin);
        return AjaxResult.success(body);
    }

    private OGoods readThroughCache(Long id) {
        String key = CACHE_PREFIX + id;
        String json = redisTemplate.opsForValue().get(key);
        if (json != null) {
            // 压测只关心链路与耗时，命中后直接返回轻量标记，避免反序列化噪声掩盖 Redis 差异
            return new OGoods();
        }
        OGoods goods = goodsService.getById(id);
        if (goods != null) {
            redisTemplate.opsForValue().set(key, String.valueOf(goods.getId()), 10, TimeUnit.MINUTES);
        }
        return goods;
    }
}
