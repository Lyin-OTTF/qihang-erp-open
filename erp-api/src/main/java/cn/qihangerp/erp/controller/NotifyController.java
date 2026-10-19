package cn.qihangerp.erp.controller;

import cn.qihangerp.security.common.SecurityUtils;
import cn.qihangerp.sse.SseService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 实时消息推送（SSE）
 *
 * P0-3 修复：clientId 原由客户端任意指定，匿名即可用他人 clientId 劫持定向消息。
 * 现从登录态派生（user_{userId}），未登录请求在 SecurityFilterChain 即被 401 拦截，
 * 本方法执行时一定存在安全上下文。
 */
@Slf4j
@AllArgsConstructor
@RestController
public class NotifyController {

    private final SseService sseService;

    @GetMapping(value = "/api/erp-api/sse/notify_msg", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter handleSse() {
        String clientId = "user_" + SecurityUtils.getUserId();
        log.info("SSE 连接建立: clientId={}", clientId);
        return sseService.createConnection(clientId);
    }
}
