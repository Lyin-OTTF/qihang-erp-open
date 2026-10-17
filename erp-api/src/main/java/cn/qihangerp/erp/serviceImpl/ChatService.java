package cn.qihangerp.erp.serviceImpl;

import cn.qihangerp.agent.memory.MemoryLayer;
import cn.qihangerp.agent.memory.MemoryManager;
import cn.qihangerp.agent.tenant.TenantContext;
import cn.qihangerp.erp.serviceImpl.ai.AiOrchestrationService;
import cn.qihangerp.erp.serviceImpl.ai.GoodsTools;
import cn.qihangerp.erp.serviceImpl.ai.InventoryTools;
import cn.qihangerp.erp.serviceImpl.ai.KnowledgeTools;
import cn.qihangerp.erp.serviceImpl.ai.MemberTools;
import cn.qihangerp.erp.serviceImpl.ai.OrderTools;
import cn.qihangerp.erp.serviceImpl.ai.OrderActionTools;
import cn.qihangerp.erp.serviceImpl.ai.LogisticsTools;
import cn.qihangerp.erp.serviceImpl.ai.PurchaseTools;
import cn.qihangerp.erp.serviceImpl.ai.RefundTools;
import cn.qihangerp.erp.serviceImpl.ai.ShopTools;
import cn.qihangerp.erp.serviceImpl.ai.StockFlowTools;
import cn.qihangerp.erp.serviceImpl.ai.SupplierTools;
import cn.qihangerp.erp.serviceImpl.ai.SuggestionTools;
import cn.qihangerp.erp.serviceImpl.ai.WarehouseTools;
import cn.qihangerp.model.entity.AiConfig;
import cn.qihangerp.model.entity.AiConversationHistory;
import cn.qihangerp.service.AiConversationHistoryService;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Service
public class ChatService {

    private final AiConversationHistoryService historyService;
    private final AiOrchestrationService orchestrationService;
    private final MemoryManager memoryManager;
    private final GoodsTools goodsTools;
    private final OrderTools orderTools;
    private final RefundTools refundTools;
    private final InventoryTools inventoryTools;
    private final ShopTools shopTools;
    private final PurchaseTools purchaseTools;
    private final MemberTools memberTools;
    private final SupplierTools supplierTools;
    private final LogisticsTools logisticsTools;
    private final WarehouseTools warehouseTools;
    private final StockFlowTools stockFlowTools;
    private final OrderActionTools orderActionTools;
    private final KnowledgeTools knowledgeTools;
    private final SuggestionTools suggestionTools;

    private static final String SYSTEM_PROMPT = """
            你是启航电商ERP系统的AI助手，帮助用户处理电商运营、订单管理、商品管理、库存管理、采购管理、仓库管理、售后管理等方面的问题。请用专业、简洁的中文回答。

            你可以多次调用工具来逐步获取数据，每次根据返回的结果决定下一步查什么。
            所有工具的说明和参数已内置，由你自行决定每次调哪些工具、需要什么筛选条件。
            由你自行对数据进行分组、排序、统计、对比，得出结论。
            """;

    /** 记忆上下文总 token 预算（近似：中文1字≈1.5token，此处用字符数*1.5估算） */
    private static final int MEMORY_TOKEN_BUDGET = 2000;
    /** 单层记忆字符预算（TEAM/USER/SESSION 各占约1/3，留余量） */
    private static final int LAYER_CHAR_BUDGET = 450;
    /** 会话历史预算：最多保留最近 N 条消息 */
    private static final int MAX_HISTORY_MESSAGES = 20;
    /** 会话历史预算：最多保留最近 N 个字符（含用户/助手消息） */
    private static final int MAX_HISTORY_CHARS = 6000;

    /**
     * 按"最近优先"裁剪会话历史，防止长会话把上下文窗口撑爆。
     * 返回结果保持原有时间顺序（旧的在前、新的在后）。
     */
    private List<AiConversationHistory> trimHistory(List<AiConversationHistory> history) {
        if (history == null || history.isEmpty()) {
            return new ArrayList<>();
        }
        int fromIndex = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        List<AiConversationHistory> tail = new ArrayList<>(history.subList(fromIndex, history.size()));

        int chars = 0;
        int keepFrom = tail.size();
        for (int i = tail.size() - 1; i >= 0; i--) {
            String content = tail.get(i).getContent();
            chars += content == null ? 0 : content.length();
            if (chars > MAX_HISTORY_CHARS) {
                break;
            }
            keepFrom = i;
        }
        if (keepFrom > 0) {
            log.debug("会话历史超预算，裁剪 {} 条旧消息", keepFrom);
        }
        return new ArrayList<>(tail.subList(keepFrom, tail.size()));
    }

    /**
     * 从记忆系统读取上下文，注入到系统提示中。
     * 读取顺序：TEAM（商户共享）→ USER（用户偏好）→ SESSION（当前会话）
     *
     * <p>Token 预算控制：每层记忆有独立字符预算，超过时按 key 倒序截断
     * （保留最新的记忆），防止记忆无限膨胀撑爆 SystemMessage。</p>
     */
    private String buildMemoryContext(String sessionId, Long userId, Long merchantId) {
        StringBuilder ctx = new StringBuilder();
        int totalChars = 0;
        try {
            // TEAM层：商户/团队共享知识（优先级最高，预算最大）
            if (merchantId != null) {
                List<String> teamKeys = memoryManager.listKeys(MemoryLayer.TEAM, merchantId.toString(), merchantId);
                // 倒序：最新的 key 优先（listKeys 通常按时间排序，倒序取最新）
                Collections.reverse(teamKeys);
                int layerChars = 0;
                for (String key : teamKeys) {
                    String val = memoryManager.retrieve(MemoryLayer.TEAM, merchantId.toString(), key, merchantId);
                    if (val == null) continue;
                    String entry = "- 团队知识[" + key + "]: " + val + "\n";
                    if (layerChars + entry.length() > LAYER_CHAR_BUDGET) {
                        log.debug("TEAM层记忆超预算，截断: 已用{}字符, 当前key={}", layerChars, key);
                        break;
                    }
                    ctx.append(entry);
                    layerChars += entry.length();
                }
                totalChars += layerChars;
            }
            // USER层：用户个人偏好/习惯
            if (userId != null) {
                List<String> userKeys = memoryManager.listKeys(MemoryLayer.USER, userId.toString(), merchantId);
                Collections.reverse(userKeys);
                int layerChars = 0;
                for (String key : userKeys) {
                    String val = memoryManager.retrieve(MemoryLayer.USER, userId.toString(), key, merchantId);
                    if (val == null) continue;
                    String entry = "- 用户偏好[" + key + "]: " + val + "\n";
                    if (layerChars + entry.length() > LAYER_CHAR_BUDGET) {
                        log.debug("USER层记忆超预算，截断: 已用{}字符, 当前key={}", layerChars, key);
                        break;
                    }
                    ctx.append(entry);
                    layerChars += entry.length();
                }
                totalChars += layerChars;
            }
            // SESSION层：当前会话上下文（重启可恢复）
            if (sessionId != null) {
                List<String> sessionKeys = memoryManager.listKeys(MemoryLayer.SESSION, sessionId, merchantId);
                Collections.reverse(sessionKeys);
                int layerChars = 0;
                for (String key : sessionKeys) {
                    String val = memoryManager.retrieve(MemoryLayer.SESSION, sessionId, key, merchantId);
                    if (val == null) continue;
                    String entry = "- 会话记忆[" + key + "]: " + val + "\n";
                    if (layerChars + entry.length() > LAYER_CHAR_BUDGET) {
                        log.debug("SESSION层记忆超预算，截断: 已用{}字符, 当前key={}", layerChars, key);
                        break;
                    }
                    ctx.append(entry);
                    layerChars += entry.length();
                }
                totalChars += layerChars;
            }
            if (totalChars > 0) {
                log.debug("记忆上下文: 总{}字符(≈{}token), 预算{}token",
                        totalChars, (int)(totalChars * 1.5), MEMORY_TOKEN_BUDGET);
            }
        } catch (Exception e) {
            log.warn("读取记忆上下文失败（不影响对话）: {}", e.getMessage());
        }
        return ctx.toString();
    }

    /**
     * 将本轮对话关键信息保存到SESSION层记忆（Redis，1天TTL，重启可恢复）
     */
    private void saveSessionMemory(String sessionId, Long userId, Long merchantId, String userMsg, String assistantReply) {
        try {
            String key = "turn_" + System.currentTimeMillis();
            String value = "用户问: " + truncate(userMsg, 200) + " | 助手答: " + truncate(assistantReply, 300);
            memoryManager.store(MemoryLayer.SESSION, sessionId, key, value, merchantId, userId);
        } catch (Exception e) {
            log.warn("保存会话记忆失败（不影响对话）: {}", e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /**
     * 解析当前租户（权威来源为登录态）。
     * 工具实际在 reactor 线程执行，ThreadLocal 无法跨线程，故必须在请求线程把登录态租户取定并透传。
     */
    private Long resolveMerchantId() {
        try {
            cn.qihangerp.security.LoginUser loginUser =
                    cn.qihangerp.security.common.SecurityUtils.getLoginUser();
            if (loginUser != null && loginUser.getMerchantId() != null) {
                return loginUser.getMerchantId();
            }
        } catch (Exception e) {
            log.debug("从登录态取租户失败，回退 TenantContext: {}", e.getMessage());
        }
        return TenantContext.getTenantId();
    }

    public void streamResponse(AiConfig config, SseEmitter emitter, String sessionId, Long userId) {
        try {
            List<AiConversationHistory> history = trimHistory(
                    historyService.getConversationForUser(sessionId, userId));

            // 租户ID权威来源：登录态（拦截器已把登录态租户写入 TenantContext，此处再直接从登录态取，双重保障）
            Long merchantId = resolveMerchantId();

            // 读取记忆上下文，注入到系统提示
            String memoryContext = buildMemoryContext(sessionId, userId, merchantId);
            String enhancedSystemPrompt = SYSTEM_PROMPT;
            if (memoryContext != null && !memoryContext.isEmpty()) {
                enhancedSystemPrompt = SYSTEM_PROMPT + "\n\n【已知记忆上下文】\n" + memoryContext;
            }

            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(enhancedSystemPrompt));
            for (AiConversationHistory msg : history) {
                if ("user".equals(msg.getRole())) {
                    messages.add(new UserMessage(msg.getContent()));
                } else {
                    messages.add(new AssistantMessage(msg.getContent()));
                }
            }

            // traceId 会话级稳定：以 sessionId 为审批匹配键，使用户"批准后重发"能命中已有审批；
            // sessionId 为空时回退到 UUID（单次对话无审批闭环）。
            String traceId = sessionId != null ? sessionId : UUID.randomUUID().toString();
            Map<String, Object> toolContext = new HashMap<>();
            toolContext.put("merchantId", merchantId);
            toolContext.put("userId", userId);
            toolContext.put("traceId", traceId);
            toolContext.put("sessionId", sessionId);
            toolContext.put("approved", false);
            toolContext.put("source", "WebUI");
            log.info("AI对话 sessionId={} userId={} merchantId={} traceId={}", sessionId, userId, merchantId, traceId);

            ChatClient chatClient = orchestrationService.buildChatClient(config, toolContext, goodsTools, orderTools, orderActionTools, refundTools, inventoryTools, shopTools, purchaseTools, memberTools, supplierTools, logisticsTools, warehouseTools, stockFlowTools, suggestionTools, knowledgeTools);

            StringBuilder fullResponse = new StringBuilder();

            chatClient.prompt()
                    .messages(messages.toArray(new Message[0]))
                    .stream()
                    .content()
                    .subscribe(
                            chunk -> {
                                fullResponse.append(chunk);
                                sendJsonEvent(emitter, "message", chunk);
                            },
                            error -> {
                                log.error("AI stream error", error);
                                sendJsonEvent(emitter, "error", "AI服务出错: " + error.getMessage());
                                safeComplete(emitter);
                            },
                            () -> {
                                String reply = fullResponse.toString();
                                if (!reply.isEmpty()) {
                                    historyService.saveMessage(sessionId, userId, "assistant", reply);
                                    // 保存本轮对话到SESSION层记忆（Redis，重启可恢复）
                                    String lastUserMsg = history.stream()
                                            .filter(m -> "user".equals(m.getRole()))
                                            .reduce((first, second) -> second)
                                            .map(AiConversationHistory::getContent)
                                            .orElse("");
                                    saveSessionMemory(sessionId, userId, merchantId, lastUserMsg, reply);
                                }
                                sendJsonEvent(emitter, "done", "");
                                safeComplete(emitter);
                            }
                    );

        } catch (Exception e) {
            log.error("AI对话处理异常", e);
            sendJsonEvent(emitter, "error", "系统错误: " + e.getMessage());
            safeComplete(emitter);
        }
    }

    private void sendJsonEvent(SseEmitter emitter, String type, String content) {
        try {
            JSONObject event = new JSONObject();
            event.put("type", type);
            if (content != null) {
                event.put("content", content);
            }
            emitter.send(SseEmitter.event().data(event.toJSONString()));
        } catch (IOException e) {
            log.debug("发送SSE事件失败: {}", e.getMessage());
        }
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }
}
