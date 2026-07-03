package com.lifetips.aiagent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 短期记忆管理。v0 用 ConcurrentHashMap 直接管理消息列表，
 * v1 引入语义压缩 + 持久化时再切换到 Spring AI 的 ChatMemory 抽象。
 *
 * @author PCRao
 */
@Slf4j
@Service
public class ShortTermMemory {

    // v0 不触发压缩，仅预留阈值
    private static final int COMPRESS_THRESHOLD = 20;
    private final Map<String, List<Message>> messageStore = new ConcurrentHashMap<>();

    /**
     * 保存一轮对话到记忆。
     */
    public void save(String chatId, String userMessage, String agentResponse) {
        List<Message> messages = messageStore.computeIfAbsent(chatId, k -> new ArrayList<>());
        messages.add(new UserMessage(userMessage));
        messages.add(new AssistantMessage(agentResponse));

        log.info("[Memory] 保存对话 chatId={}, 消息数={}", chatId, messages.size());
    }

    /**
     * 读取会话历史，格式化为"用户说 / Agent 说"的对话记录。
     */
    public String getHistoryAsText(String chatId) {
        List<Message> messages = messageStore.get(chatId);

        if (messages == null || messages.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            if (msg instanceof UserMessage) {
                sb.append("用户说：").append(msg.getText()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                sb.append("Agent说：").append(msg.getText()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 将本轮 thought 和 conclusion 拼接到 preWorkResult。
     * v0 用线性拼接，v1 将替换为语义压缩。
     */
    public String buildPreWorkResult(String existing, String thought, String conclusion) {
        StringBuilder sb = new StringBuilder(existing != null ? existing : "");

        if (thought != null && !thought.isBlank()) {
            sb.append("[本轮思考] ").append(thought).append("\n");
        }
        if (conclusion != null && !conclusion.isBlank()) {
            sb.append("[本轮执行结果] ").append(conclusion).append("\n");
        }
        return sb.toString();
    }
}
