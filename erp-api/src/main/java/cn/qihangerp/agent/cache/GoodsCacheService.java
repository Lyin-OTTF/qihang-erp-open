package cn.qihangerp.agent.cache;

import cn.qihangerp.common.PageQuery;
import cn.qihangerp.common.PageResult;
import cn.qihangerp.model.entity.OGoods;
import cn.qihangerp.model.entity.OGoodsSku;
import cn.qihangerp.model.query.GoodsQuery;
import cn.qihangerp.service.OGoodsService;
import cn.qihangerp.service.OGoodsSkuService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 商品热点缓存服务。
 *
 * <p>在商品/SKU查询层加 Redis 缓存，降低高频查询对 DB 的压力。
 * 缓存策略：
 * <ul>
 *   <li>商品搜索结果：5分钟过期（搜索词重复率高）</li>
 *   <li>SKU搜索结果：5分钟过期</li>
 *   <li>商品详情（按ID）：10分钟过期</li>
 *   <li>SKU列表（按goodsId）：10分钟过期</li>
 * </ul>
 * 缓存值用 JSON 序列化，避免 Java 序列化兼容性问题。</p>
 *
 * @author 回溯开发-Redis缓存层
 * @since 2026-10-13
 */
@Slf4j
@Component
public class GoodsCacheService {

    private final StringRedisTemplate redisTemplate;
    private final OGoodsService goodsService;
    private final OGoodsSkuService skuService;
    private final ObjectMapper objectMapper;

    private static final String GOODS_SEARCH_PREFIX = "cache:goods:search:";
    private static final String SKU_SEARCH_PREFIX = "cache:sku:search:";
    private static final String GOODS_DETAIL_PREFIX = "cache:goods:detail:";
    private static final String GOODS_SKU_PREFIX = "cache:goods:skus:";

    private static final long SEARCH_TTL_MINUTES = 5;
    private static final long DETAIL_TTL_MINUTES = 10;

    public GoodsCacheService(StringRedisTemplate redisTemplate,
                             OGoodsService goodsService,
                             OGoodsSkuService skuService,
                             ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.goodsService = goodsService;
        this.skuService = skuService;
        this.objectMapper = objectMapper;
    }

    /**
     * 搜索商品（带缓存）。
     * 缓存键：cache:goods:search:{merchantId}:{keyword}
     */
    public PageResult<OGoods> searchGoods(String keyword, Long merchantId) {
        String cacheKey = GOODS_SEARCH_PREFIX + (merchantId == null ? "public" : merchantId) + ":" + keyword;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                PageResult<OGoods> result = objectMapper.readValue(cached,
                        new TypeReference<PageResult<OGoods>>() {});
                log.debug("商品搜索命中缓存: keyword={}, merchantId={}", keyword, merchantId);
                return result;
            } catch (Exception e) {
                log.warn("商品缓存反序列化失败，回源DB: {}", e.getMessage());
            }
        }

        GoodsQuery query = new GoodsQuery();
        query.setName(keyword);
        if (merchantId != null) {
            query.setMerchantId(merchantId);
        }
        PageResult<OGoods> result = goodsService.queryPageList(query, new PageQuery());

        try {
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(cacheKey, json, SEARCH_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("商品搜索结果缓存写入失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 搜索SKU（带缓存）。
     * 缓存键：cache:sku:search:{merchantId}:{keyword}
     */
    public List<OGoodsSku> searchSku(String keyword, Long merchantId) {
        String cacheKey = SKU_SEARCH_PREFIX + (merchantId == null ? "public" : merchantId) + ":" + keyword;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                List<OGoodsSku> list = objectMapper.readValue(cached,
                        new TypeReference<List<OGoodsSku>>() {});
                log.debug("SKU搜索命中缓存: keyword={}, merchantId={}", keyword, merchantId);
                return list;
            } catch (Exception e) {
                log.warn("SKU缓存反序列化失败，回源DB: {}", e.getMessage());
            }
        }

        List<OGoodsSku> list = skuService.searchGoodsSpec(keyword, merchantId);
        if (list == null) {
            list = Collections.emptyList();
        }

        try {
            String json = objectMapper.writeValueAsString(list);
            redisTemplate.opsForValue().set(cacheKey, json, SEARCH_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("SKU搜索结果缓存写入失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * 按ID查询商品详情（带缓存）。
     * 缓存键：cache:goods:detail:{id}
     */
    public OGoods getGoodsById(Long id) {
        if (id == null) return null;
        String cacheKey = GOODS_DETAIL_PREFIX + id;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, OGoods.class);
            } catch (Exception e) {
                log.warn("商品详情缓存反序列化失败: {}", e.getMessage());
            }
        }

        OGoods goods = goodsService.selectGoodsById(id);
        if (goods != null) {
            try {
                String json = objectMapper.writeValueAsString(goods);
                redisTemplate.opsForValue().set(cacheKey, json, DETAIL_TTL_MINUTES, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.warn("商品详情缓存写入失败: {}", e.getMessage());
            }
        }
        return goods;
    }

    /**
     * 按goodsId查询SKU列表（带缓存）。
     * 缓存键：cache:goods:skus:{goodsId}
     */
    public List<OGoodsSku> getSkusByGoodsId(Long goodsId) {
        if (goodsId == null) return Collections.emptyList();
        String cacheKey = GOODS_SKU_PREFIX + goodsId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, new TypeReference<List<OGoodsSku>>() {});
            } catch (Exception e) {
                log.warn("SKU列表缓存反序列化失败: {}", e.getMessage());
            }
        }

        List<OGoodsSku> list = goodsService.querySkuByGoodsId(goodsId);
        if (list == null) list = Collections.emptyList();

        try {
            String json = objectMapper.writeValueAsString(list);
            redisTemplate.opsForValue().set(cacheKey, json, DETAIL_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("SKU列表缓存写入失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * 清除商品相关缓存（商品新增/修改/删除时调用）。
     */
    public void evictGoodsCache(Long goodsId) {
        if (goodsId != null) {
            redisTemplate.delete(GOODS_DETAIL_PREFIX + goodsId);
            redisTemplate.delete(GOODS_SKU_PREFIX + goodsId);
        }
        // 搜索结果缓存无法精确清除，依赖TTL自然过期
        log.info("已清除商品缓存: goodsId={}", goodsId);
    }

    /**
     * 清除所有商品搜索缓存（批量变更时调用）。
     */
    public void evictAllSearchCache() {
        var keys1 = redisTemplate.keys(GOODS_SEARCH_PREFIX + "*");
        var keys2 = redisTemplate.keys(SKU_SEARCH_PREFIX + "*");
        if (keys1 != null && !keys1.isEmpty()) redisTemplate.delete(keys1);
        if (keys2 != null && !keys2.isEmpty()) redisTemplate.delete(keys2);
        log.info("已清除全部商品搜索缓存");
    }
}
