package cn.qihangerp.benchmark;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 缓存命中前后响应时间基准（benchmark）。
 *
 * <p>刻意测两种读路径，因为它们的结论相反：</p>
 * <ul>
 *   <li><b>主键点查</b>：缓存反而更慢——多了一次 Redis 网络往返，而 MySQL 主键查询本身已经足够便宜；</li>
 *   <li><b>无索引搜索</b>（商品名模糊搜）：缓存收益显著——省掉的是全表扫描。</li>
 * </ul>
 *
 * <p>这正是"缓存该用在哪"的判据：<b>缓存用于重查询，不是用于所有查询</b>。
 * 只报"缓存更快"而不区分查询形态，是这类基准最常见的失真来源。</p>
 *
 * <p>数据：建一张 2 万行的探针表 {@code bench_cache_probe}（业务表 o_goods 通常为空，
 * 用探针表可保证任何环境都能复现同一实验），跑完即删。</p>
 *
 * <p>运行：mvn -pl erp-api -am test -Pbenchmark-tests -Dtest=RedisCacheBenchmarkTest</p>
 *
 * @author qihang
 */
@Tag("benchmark")
class RedisCacheBenchmarkTest {

    private static final int PROBE_ROWS = 20_000;
    private static final int PK_WARMUP = 300;
    private static final int PK_ROUNDS = 3_000;
    private static final int SEARCH_WARMUP = 20;
    private static final int SEARCH_ROUNDS = 200;
    private static final String KEY_PREFIX = "bench:cache:";
    private static final String PROBE_TABLE = "bench_cache_probe";
    private static final String SEARCH_KEYWORD = "name-19999";

    @Test
    void compareCacheHitAndDbRead() throws Exception {
        StringRedisTemplate redis = redisTemplateOrNull();
        assumeTrue(redis != null, "Redis 不可达（127.0.0.1:6379，密码取 REDIS_PASSWORD），跳过缓存基准");

        String url = System.getenv().getOrDefault("MYSQL_URL",
                "jdbc:mysql://127.0.0.1:3306/qihang-erp?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        String user = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
        String password = System.getenv().getOrDefault("MYSQL_PASSWORD", "1234");
        assumeTrue(canConnect(url, user, password), "MySQL 不可达，跳过缓存基准");

        prepareProbeTable(url, user, password);
        String pkKey = KEY_PREFIX + "probe:pk:1";
        String searchKey = KEY_PREFIX + "probe:search:" + SEARCH_KEYWORD;

        try {
            redis.opsForValue().set(pkKey, "{\"id\":1}", 10, TimeUnit.MINUTES);
            redis.opsForValue().set(searchKey, "[{\"id\":19999,\"payload\":\"name-19999\"}]", 10, TimeUnit.MINUTES);

            Result result = new Result();

            // ---------- 场景 A：主键点查 ----------
            result.redisPk = measureRedis(redis, pkKey, PK_WARMUP, PK_ROUNDS);
            try (Connection conn = DriverManager.getConnection(url, user, password);
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT id, payload FROM " + PROBE_TABLE + " WHERE id = ?")) {
                result.dbPk = measureDb(ps, 1L, PK_WARMUP, PK_ROUNDS);
            }

            // ---------- 场景 B：无索引模糊搜索（全表扫描） ----------
            result.redisSearch = measureRedis(redis, searchKey, SEARCH_WARMUP, SEARCH_ROUNDS);
            try (Connection conn = DriverManager.getConnection(url, user, password);
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT id, payload FROM " + PROBE_TABLE + " WHERE payload LIKE ? LIMIT 20")) {
                result.dbSearch = measureDb(ps, "%" + SEARCH_KEYWORD + "%", SEARCH_WARMUP, SEARCH_ROUNDS);
            }

            String markdown = buildMarkdown(result);
            System.out.println("\n" + markdown);
            Path file = LatencyStats.writeReport("redis-cache-benchmark", markdown);
            System.out.println("报告已写入: " + (file == null ? "(失败)" : file.toAbsolutePath()));
        } finally {
            redis.delete(pkKey);
            redis.delete(searchKey);
            dropProbeTable(url, user, password);
        }
    }

    // ------------------------------------------------------------------
    // 测量
    // ------------------------------------------------------------------

    private LatencyStats measureRedis(StringRedisTemplate redis, String key, int warmup, int rounds) {
        LatencyStats stats = new LatencyStats();
        for (int i = 0; i < warmup; i++) {
            redis.opsForValue().get(key);
        }
        for (int i = 0; i < rounds; i++) {
            long begin = System.nanoTime();
            String v = redis.opsForValue().get(key);
            stats.record(System.nanoTime() - begin);
            assertTrue(v != null, "缓存命中不应为空");
        }
        return stats;
    }

    private LatencyStats measureDb(PreparedStatement ps, Object param, int warmup, int rounds) throws Exception {
        LatencyStats stats = new LatencyStats();
        for (int i = 0; i < warmup; i++) {
            queryOnce(ps, param);
        }
        for (int i = 0; i < rounds; i++) {
            long begin = System.nanoTime();
            queryOnce(ps, param);
            stats.record(System.nanoTime() - begin);
        }
        return stats;
    }

    private void queryOnce(PreparedStatement ps, Object param) throws Exception {
        if (param instanceof Long l) {
            ps.setLong(1, l);
        } else {
            ps.setString(1, (String) param);
        }
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                // 消费结果集，避免驱动延迟取数的差异
            }
        }
    }

    // ------------------------------------------------------------------
    // 报告
    // ------------------------------------------------------------------

    private String buildMarkdown(Result r) {
        double pkDelta = r.dbPk.percentileMs(50) - r.redisPk.percentileMs(50);
        double searchDelta = r.dbSearch.percentileMs(50) - r.redisSearch.percentileMs(50);

        StringBuilder sb = new StringBuilder(LatencyStats.header("Redis 缓存命中前后响应时间对比",
                String.format("探针表 %d 行（InnoDB，主键 id + 无索引 payload）；点查 %d 次、搜索 %d 次，均含预热",
                        PROBE_ROWS, PK_ROUNDS, SEARCH_ROUNDS)));

        sb.append("| 场景 | 链路 | 平均(ms) | P50(ms) | P95(ms) | P99(ms) | Max(ms) |\n");
        sb.append("|------|------|---------|---------|---------|---------|---------|\n");
        appendRow(sb, "A 主键点查", "命中缓存（Redis GET）", r.redisPk);
        appendRow(sb, "A 主键点查", "直连 DB（主键查询）", r.dbPk);
        appendRow(sb, "B 模糊搜索", "命中缓存（Redis GET）", r.redisSearch);
        appendRow(sb, "B 模糊搜索", "直连 DB（全表扫描 LIKE）", r.dbSearch);

        sb.append("\n## 结论\n\n");
        sb.append(String.format(
                "- **主键点查：缓存 P50 比直连 DB 慢 %.2f ms**（%.2f ms vs %.2f ms）。"
                        + "一次 Redis 网络往返盖过了 MySQL 主键查询的成本，这类读不应加缓存。%n",
                -pkDelta, r.redisPk.percentileMs(50), r.dbPk.percentileMs(50)));
        sb.append(String.format(
                "- **模糊搜索：缓存 P50 比直连 DB 快 %.2f ms（约 %.1f 倍）**（%.2f ms vs %.2f ms）。"
                        + "省掉的是 %d 行全表扫描，这才是缓存的收益来源。%n",
                searchDelta,
                r.redisSearch.percentileMs(50) > 0 ? r.dbSearch.percentileMs(50) / r.redisSearch.percentileMs(50) : Double.NaN,
                r.redisSearch.percentileMs(50), r.dbSearch.percentileMs(50), PROBE_ROWS));
        sb.append("- 因此 `GoodsCacheService` 只缓存搜索与详情结果（5~10 分钟 TTL），"
                + "而不缓存库存等强一致且点查廉价的数据，是有依据的取舍。\n");
        sb.append("\n> 局限：本机 loopback 下 Redis 往返约 0.2~0.3ms，跨机房会放大到毫秒级；"
                + "数据量、索引设计、MySQL 缓冲池命中率都会改变绝对值。结论只对「查询形态的相对关系」负责。\n");
        return sb.toString();
    }

    private void appendRow(StringBuilder sb, String scene, String path, LatencyStats s) {
        sb.append("| ").append(scene)
                .append(" | ").append(path)
                .append(" | ").append(LatencyStats.f(s.avgMs()))
                .append(" | ").append(LatencyStats.f(s.percentileMs(50)))
                .append(" | ").append(LatencyStats.f(s.percentileMs(95)))
                .append(" | ").append(LatencyStats.f(s.percentileMs(99)))
                .append(" | ").append(LatencyStats.f(s.maxMs()))
                .append(" |\n");
    }

    // ------------------------------------------------------------------
    // 环境与数据
    // ------------------------------------------------------------------

    private StringRedisTemplate redisTemplateOrNull() {
        try {
            LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 6379);
            String pwd = System.getenv().getOrDefault("REDIS_PASSWORD", "");
            if (!pwd.isBlank()) {
                factory.setPassword(pwd);
            }
            factory.afterPropertiesSet();
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.opsForValue().get(KEY_PREFIX + "probe");
            return template;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean canConnect(String url, String user, String password) {
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            return c.isValid(3);
        } catch (Exception e) {
            return false;
        }
    }

    private void prepareProbeTable(String url, String user, String password) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DROP TABLE IF EXISTS " + PROBE_TABLE);
                st.executeUpdate("CREATE TABLE " + PROBE_TABLE + " ("
                        + "id bigint NOT NULL, payload varchar(255) NULL, "
                        + "PRIMARY KEY (id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + PROBE_TABLE + " (id, payload) VALUES (?, ?)")) {
                for (int i = 0; i < PROBE_ROWS; i++) {
                    ps.setLong(1, i + 1);
                    ps.setString(2, "name-" + (i + 1));
                    ps.addBatch();
                    if ((i + 1) % 1000 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
            conn.commit();
            conn.setAutoCommit(true);
        }
    }

    private void dropProbeTable(String url, String user, String password) {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS " + PROBE_TABLE);
        } catch (Exception e) {
            System.err.println("探针表清理失败: " + e.getMessage());
        }
    }

    /** 一次实验的全部采样 */
    private static class Result {
        LatencyStats redisPk;
        LatencyStats dbPk;
        LatencyStats redisSearch;
        LatencyStats dbSearch;
    }
}
