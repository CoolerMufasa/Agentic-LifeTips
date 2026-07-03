package com.lifetips.aiagent.router;

import com.lifetips.common.enums.IntentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 意图识别路由器。用一次轻量 LLM 调用判断用户输入是闲聊还是知识问题，
 * 尽早分流以节省 ReAct 循环的 Token 消耗。
 *
 * @author PCRao
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRouter {

    // 意图识别的专用 SystemPrompt，极简——只要求输出 CHAT 或 PLAN
    private static final String ROUTER_PROMPT = """
            判断用户输入属于哪种类型：
            - CHAT：日常闲聊、打招呼、感谢等不涉及知识查询的内容
            - PLAN：具体的生活问题、家居技巧、烹饪方法、清洁窍门等需要搜索知识的内容
            仅回复 CHAT 或 PLAN，不要输出其他任何内容。
            """;
    private final ChatClient deepseekChatClient;

    // 响应式版本：将阻塞的 LLM 调用搬到 boundedElastic 线程池
    public Mono<IntentType> recognize(String userInput) {
        return Mono.fromCallable(() -> recognizeBlocking(userInput))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private IntentType recognizeBlocking(String userInput) {
        log.info("[Router] 意图识别: {}", truncate(userInput, 50));

        try {
            String result = deepseekChatClient.prompt()
                    .system(ROUTER_PROMPT)
                    .user(userInput)
                    .call()
                    .content();

            IntentType type = IntentType.fromString(result);
            log.info("[Router] 结果: input={}, intent={}", truncate(userInput, 30), type);
            return type;

        } catch (Exception e) {
            log.warn("[Router] 识别异常，默认走 PLAN: {}", e.getMessage());
            return IntentType.PLAN;
        }
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }
}
