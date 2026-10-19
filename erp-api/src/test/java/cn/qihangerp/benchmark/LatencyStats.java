package cn.qihangerp.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * 基准测试的延迟采样与报告输出。
 *
 * <p>只做三件事：按纳秒采集单次操作耗时、算分位数、拼 Markdown。刻意不引第三方统计库，
 * 保证 benchmark 在最小依赖下可复现。</p>
 *
 * @author qihang
 */
public class LatencyStats {

    private static final DateTimeFormatter TS_FILE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private long[] samples = new long[1024];
    private int size = 0;

    public void record(long nanos) {
        if (size == samples.length) {
            samples = Arrays.copyOf(samples, samples.length * 2);
        }
        samples[size++] = nanos;
    }

    public int count() {
        return size;
    }

    /** 分位数（毫秒） */
    public double percentileMs(double p) {
        if (size == 0) {
            return Double.NaN;
        }
        long[] copy = Arrays.copyOf(samples, size);
        Arrays.sort(copy);
        int idx = (int) Math.ceil(p / 100.0 * size) - 1;
        idx = Math.max(0, Math.min(size - 1, idx));
        return copy[idx] / 1_000_000.0;
    }

    public double avgMs() {
        if (size == 0) {
            return Double.NaN;
        }
        long sum = 0;
        for (int i = 0; i < size; i++) {
            sum += samples[i];
        }
        return sum / (double) size / 1_000_000.0;
    }

    public double maxMs() {
        if (size == 0) {
            return Double.NaN;
        }
        long max = 0;
        for (int i = 0; i < size; i++) {
            max = Math.max(max, samples[i]);
        }
        return max / 1_000_000.0;
    }

    /** 吞吐（ops/s），totalMillis 为场景总耗时 */
    public double throughput(long totalMillis) {
        return totalMillis <= 0 ? 0 : size * 1000.0 / totalMillis;
    }

    public static String header(String title, String description) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append("> 生成时间：").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        sb.append("> ").append(description).append("\n\n");
        return sb.toString();
    }

    /**
     * 把一组结果按 Markdown 表格输出，并写入 benchmark/reports/。
     *
     * @param reportName 文件名（不含扩展名）
     * @param markdown   完整 Markdown 内容
     * @return 实际写入路径；写入失败返回 null（不因报告落盘失败而让基准失败）
     */
    public static Path writeReport(String reportName, String markdown) {
        Path dir = resolveReportDir();
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(reportName + "-" + LocalDateTime.now().format(TS_FILE) + ".md");
            Files.write(file, markdown.getBytes(StandardCharsets.UTF_8));
            return file;
        } catch (IOException e) {
            System.err.println("报告写入失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 定位 benchmark/reports：从当前工作目录向上找包含 benchmark/README.md 的目录。
     * 这样在根目录或 erp-api 模块目录下执行都能落到同一个报告目录。
     */
    private static Path resolveReportDir() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++) {
            if (Files.exists(dir.resolve("benchmark").resolve("README.md"))) {
                return dir.resolve("benchmark").resolve("reports");
            }
            dir = dir.getParent();
        }
        return Paths.get("benchmark", "reports").toAbsolutePath();
    }

    public static String f(double v) {
        return Double.isNaN(v) ? "-" : String.format("%.2f", v);
    }

    public static String n(double v) {
        return Double.isNaN(v) ? "-" : String.format("%.0f", v);
    }
}
