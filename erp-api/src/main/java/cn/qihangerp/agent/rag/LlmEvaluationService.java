package cn.qihangerp.agent.rag;

import cn.qihangerp.model.entity.AiConfig;
import cn.qihangerp.service.AiConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM评测服务：用真实大模型生成答案并作为裁判打分。
 *
 * <p>与 RagEvaluator 中的启发式打分（Jaccard相似度）不同，
 * 本服务调用 DeepSeek 真实生成答案，并让 LLM 对答案的
 * 相关性(relevancy)、正确性(correctness)、忠实度(faithfulness)
 * 进行0-1分打分。</p>
 *
 * <p>默认关闭（agent.evaluation.llm-enabled=false），避免测试和日常启动产生API费用。
 * 开启后评测耗时从秒级变为分钟级，且产生 LLM API 调用费用。</p>
 *
 * @author 回溯开发-第三类补全
 * @since 2026-10-14
 */
@Slf4j
@Service
public class LlmEvaluationService {

    private final AiConfigService aiConfigService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Pattern jsonPattern = Pattern.compile("\\{[\\s\\S]*\\}");

    private final boolean llmEnabled;
    private final int timeoutSeconds;
    private final int contextLimit;
    private final int maxAttempts;

    private volatile ChatClient cachedChatClient;
    private volatile String cachedModelName;

    public LlmEvaluationService(AiConfigService aiConfigService,
                                @Value("${agent.evaluation.llm-enabled:false}") boolean llmEnabled,
                                @Value("${agent.evaluation.llm-timeout-seconds:60}") int timeoutSeconds,
                                @Value("${agent.evaluation.context-limit:10}") int contextLimit,
                                @Value("${agent.evaluation.llm-max-attempts:3}") int maxAttempts) {
        this.aiConfigService = aiConfigService;
        this.llmEnabled = llmEnabled;
        this.timeoutSeconds = timeoutSeconds;
        this.contextLimit = Math.max(1, contextLimit);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public boolean isLlmEnabled() {
        return llmEnabled;
    }

    /**
     * 返回当前评测使用的模型名称。
     */
    public String getModelName() {
        if (!llmEnabled) {
            return "heuristic";
        }
        getChatClient();
        return cachedModelName;
    }

    /**
     * 用LLM基于检索上下文生成真实答案。
     */
    public String generateAnswer(String question, List<RetrievalResult> context) {
        if (!llmEnabled) return null;
        try {
            ChatClient client = getChatClient();
            StringBuilder contextStr = new StringBuilder();
            int limit = Math.min(contextLimit, context.size());
            for (int i = 0; i < limit; i++) {
                contextStr.append("[").append(i + 1).append("] ")
                        .append(context.get(i).getContent()).append("\n");
            }
            String prompt = String.format(
                    "你是一个电商ERP系统的智能客服助手。请根据以下参考资料回答用户问题。" +
                            "如果参考资料中没有相关信息，请回答\"根据现有资料无法确定\"，不要编造。\n\n" +
                            "参考资料：\n%s\n用户问题：%s\n请直接给出答案，不要重复问题。",
                    contextStr, question);
            return callWithRetry(client, prompt, "生成答案");
        } catch (Exception e) {
            log.warn("LLM生成答案失败，降级为启发式: {}", e.getMessage());
            return null;
        }
    }

    /**
     * LLM-as-judge：对生成答案进行多维度打分。
     *
     * @return double[3] = {relevancy, correctness, faithfulness}，失败返回null
     */
    public double[] judgeAnswer(String question, String expectedAnswer,
                                 String generatedAnswer, List<RetrievalResult> context) {
        if (!llmEnabled) return null;
        try {
            ChatClient client = getChatClient();
            StringBuilder contextStr = new StringBuilder();
            int limit = Math.min(contextLimit, context.size());
            for (int i = 0; i < limit; i++) {
                contextStr.append("[").append(i + 1).append("] ")
                        .append(context.get(i).getContent()).append("\n");
            }
            String prompt = String.format(
                    "你是一个严格的评测裁判。请根据以下信息对AI生成的答案打分。\n\n" +
                            "用户问题：%s\n标准答案：%s\nAI生成答案：%s\n参考资料：\n%s\n\n" +
                            "请从三个维度打分（0到1之间的小数）：\n" +
                            "1. relevancy（相关性）：答案与问题的相关程度\n" +
                            "2. correctness（正确性）：答案与标准答案的一致程度\n" +
                            "3. faithfulness（忠实度）：答案是否基于参考资料，有无编造\n\n" +
                            "只输出JSON，不要输出其他文字，格式：{\"relevancy\":0.85,\"correctness\":0.70,\"faithfulness\":0.90}",
                    question,
                    expectedAnswer != null ? expectedAnswer : "（无标准答案）",
                    generatedAnswer != null ? generatedAnswer : "（空答案）",
                    contextStr);

            Exception lastError = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    String response = client.prompt(prompt).call().content();
                    double[] scores = parseScores(response);
                    if (scores != null) {
                        return scores;
                    }
                    lastError = new IllegalStateException("无法解析裁判JSON");
                } catch (Exception e) {
                    lastError = e;
                }
                if (attempt < maxAttempts) {
                    sleepBeforeRetry(attempt, "裁判打分");
                }
            }
            throw lastError != null ? lastError : new IllegalStateException("裁判打分失败");
        } catch (Exception e) {
            log.warn("LLM裁判打分失败，降级为启发式: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从LLM响应中解析JSON分数。
     */
    private double[] parseScores(String response) {
        if (response == null || response.isBlank()) return null;
        try {
            Matcher matcher = jsonPattern.matcher(response);
            if (matcher.find()) {
                JsonNode node = objectMapper.readTree(matcher.group());
                double relevancy = node.has("relevancy") ? node.get("relevancy").asDouble() : 0.0;
                double correctness = node.has("correctness") ? node.get("correctness").asDouble() : 0.0;
                double faithfulness = node.has("faithfulness") ? node.get("faithfulness").asDouble() : 0.0;
                return new double[]{clamp(relevancy), clamp(correctness), clamp(faithfulness)};
            }
        } catch (Exception e) {
            log.warn("解析LLM打分JSON失败: response={}, error={}", response, e.getMessage());
        }
        return null;
    }

    private double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private String callWithRetry(ChatClient client, String prompt, String operation) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String content = client.prompt(prompt).call().content();
                if (content != null && !content.isBlank()) {
                    return content;
                }
                lastError = new IllegalStateException(operation + "返回空内容");
            } catch (Exception e) {
                lastError = e;
            }
            if (attempt < maxAttempts) {
                sleepBeforeRetry(attempt, operation);
            }
        }
        throw new IllegalStateException(operation + "失败: " + lastError.getMessage(), lastError);
    }

    private void sleepBeforeRetry(int attempt, String operation) {
        long delay = 500L * attempt;
        log.warn("{}第{}次失败，{}ms后重试", operation, attempt, delay);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + "重试被中断", e);
        }
    }

    /**
     * 构建（并缓存）无工具的纯聊天 ChatClient。
     */
    private ChatClient getChatClient() {
        if (cachedChatClient != null) return cachedChatClient;
        synchronized (this) {
            if (cachedChatClient != null) return cachedChatClient;
            AiConfig config = aiConfigService.getDefaultConfig();
            if (config == null) {
                throw new IllegalStateException("未找到默认AI配置(AiConfig)，无法构建LLM评测客户端");
            }
            String baseUrl = config.getApiEndpoint()
                    .replaceAll("/v1/?$", "")
                    .replaceAll("/+$", "");
            HttpClient httpClient = HttpClient.create()
                    .responseTimeout(Duration.ofSeconds(timeoutSeconds));
            ReactorClientHttpRequestFactory factory = new ReactorClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

            DeepSeekApi api = DeepSeekApi.builder()
                    .baseUrl(baseUrl)
                    .apiKey(new SimpleApiKey(config.getApiKey()))
                    .completionsPath("/v1/chat/completions")
                    .restClientBuilder(RestClient.builder().requestFactory(factory))
                    .build();
            DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                    .deepSeekApi(api)
                    .options(DeepSeekChatOptions.builder()
                            .model(config.getModelName())
                            .temperature(0.3)
                            .build())
                    .build();
            cachedChatClient = ChatClient.builder(chatModel).build();
            cachedModelName = config.getModelName();
            log.info("LLM评测客户端已构建: model={}, endpoint={}", config.getModelName(), baseUrl);
            return cachedChatClient;
        }
    }
}
