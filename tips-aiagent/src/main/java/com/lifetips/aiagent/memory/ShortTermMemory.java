package com.lifetips.aiagent.memory;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 短期记忆管理，使用 Spring AI MessageWindowChatMemory，自动窗口管理。
 *
 * @author PCRao
 */
@Slf4j
@Service
public class ShortTermMemory {

    // 窗口大小 20 条，超出后自动移除旧消息但保留系统消息
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .maxMessages(20)
            .build();

    /**
     * 保存一轮对话到记忆。
     */
    public void save(String chatId, String userMessage, String agentResponse) {
        chatMemory.add(chatId, new UserMessage(userMessage));
        chatMemory.add(chatId, new AssistantMessage(agentResponse));
        log.info("[Memory] 保存对话 chatId={}", chatId);
    }

    /**
     * 读取会话历史，格式化为自然语言对话记录。
     */
    public String getHistoryAsText(String chatId) {
        List<Message> messages = chatMemory.get(chatId);

        if (CollectionUtils.isEmpty(messages)) {
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
     * 获取原始 Message 列表供 Graph State 使用。
     */
    public List<Message> getMessages(String chatId) {
        List<Message> messages = chatMemory.get(chatId);
        return messages != null ? messages : List.of();
    }

    /**
     * V0 兼容：字符串累加的 preWorkResult，V1 Graph 路径已用 ReasoningVO 替代。
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
