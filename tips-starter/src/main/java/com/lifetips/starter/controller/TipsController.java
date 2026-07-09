package com.lifetips.starter.controller;

import com.lifetips.aiagent.core.AgentEngine;
import com.lifetips.aiagent.core.GraphEngine;
import com.lifetips.aiagent.router.IntentRouter;
import com.lifetips.common.enums.IntentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.UUID;

/**
 * SSE 流式接口。闲聊直接回复，知识问题进入 GraphEngine（V1）。
 * V0 引擎保留在 /api/v0/chat 用于对比演示。
 *
 * @author PCRao
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class TipsController {

    private final IntentRouter router;
    private final GraphEngine graphEngine;
    private final AgentEngine agentEngine;
    private final ChatClient workerChatClient;

    public TipsController(IntentRouter router,
            GraphEngine graphEngine,
            AgentEngine agentEngine,
            @Qualifier("workerChatClient") ChatClient workerChatClient) {
        this.router = router;
        this.graphEngine = graphEngine;
        this.agentEngine = agentEngine;
        this.workerChatClient = workerChatClient;
    }

    /**
     * V1 核心对话接口——Graph 引擎，SSE 消息按 type 分类。
     */
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(
            @RequestParam String message,
            @RequestParam(required = false, defaultValue = "") String chatId
    ) {
        String effectiveChatId = chatId.isBlank()
                ? UUID.randomUUID().toString().substring(0, 8)
                : chatId;

        log.info("[Controller] 收到请求 chatId={}, message={}",
                effectiveChatId, truncate(message, 50));

        return router.recognize(message)
                .flatMapMany(intent ->
                    intent == IntentType.CHAT
                        ? handleChat(message)
                        : graphEngine.execute(message, effectiveChatId)
                )
                .map(text -> ServerSentEvent.<String>builder().data(text).build())
                .mergeWith(Flux.interval(Duration.ofSeconds(30))
                        .map(i -> ServerSentEvent.<String>builder().comment("heartbeat").build()));
    }

    /**
     * V0 对话接口——保留旧版 AgentEngine，用于面试时对比演示 V0 vs V1。
     */
    @GetMapping(value = "/v0/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatV0(
            @RequestParam String message,
            @RequestParam(required = false, defaultValue = "") String chatId
    ) {
        String effectiveChatId = chatId.isBlank()
                ? UUID.randomUUID().toString().substring(0, 8)
                : chatId;

        log.info("[Controller:V0] 收到请求 chatId={}, message={}",
                effectiveChatId, truncate(message, 50));

        return router.recognize(message)
                .flatMapMany(intent ->
                    intent == IntentType.CHAT
                        ? handleChat(message)
                        : agentEngine.execute(message, effectiveChatId)
                )
                .map(text -> ServerSentEvent.<String>builder().data(text).build())
                .mergeWith(Flux.interval(Duration.ofSeconds(30))
                        .map(i -> ServerSentEvent.<String>builder().comment("heartbeat").build()));
    }

    // 闲聊：阻塞调用搬到 boundedElastic 线程池
    private Flux<String> handleChat(String message) {
        return Mono.fromCallable(() ->
                workerChatClient.prompt()
                        .system("你是一个友好的生活助手，用轻松自然的语气和用户聊天。")
                        .user(message)
                        .call()
                        .content()
        ).subscribeOn(Schedulers.boundedElastic()).flux();
    }

    @GetMapping("/health")
    public Mono<String> health() {
        return Mono.just("OK - Agentic LifeTips is running");
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }
}
