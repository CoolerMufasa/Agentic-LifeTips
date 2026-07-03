package com.lifetips.aiagent.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifetips.aiagent.config.SystemPrompt;
import com.lifetips.common.vo.PlanDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 规划服务。调用 DeepSeek 拆解任务，返回 PlanDetailVO 驱动 AgentEngine 的下一步决策。
 *
 * @author PCRao
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlannerService {

    private final ChatClient deepseekChatClient;
    private final ObjectMapper objectMapper;

    public PlanDetailVO plan(String userInput, String historyContext) {
        log.info("[Planner] 开始规划, input={}", truncate(userInput, 100));

        String userMessage = buildUserMessage(userInput, historyContext);

        try {
            String rawContent = deepseekChatClient.prompt()
                    .system(SystemPrompt.PLANNER_SYSTEM_PROMPT)
                    .user(userMessage)
                    .call()
                    .content();

            PlanDetailVO result = parseAndClean(rawContent);

            log.info("[Planner] 规划完成, action={}, thought={}",
                    result.getAction(), truncate(result.getThought(), 80));
            return result;
        } catch (Exception e) {
            log.error("[Planner] 规划异常: {}", e.getMessage(), e);
            return fallbackPlan(e.getMessage());
        }
    }

    // 清洗 LLM 返回的 JSON：去掉 ```json 包裹、修复常见格式问题
    private PlanDetailVO parseAndClean(String raw) {
        String json = raw.trim();

        // 去掉 markdown 代码块包裹
        if (json.startsWith("```")) {
            int start = json.indexOf("\n");
            int end = json.lastIndexOf("```");
            if (start > 0 && end > start) {
                json = json.substring(start, end).trim();
            }
        }

        try {
            return objectMapper.readValue(json, PlanDetailVO.class);
        } catch (JsonProcessingException e) {
            log.warn("[Planner] JSON 解析失败，尝试修复: {}", truncate(json, 200));
            // 尝试提取 JSON 对象
            int braceStart = json.indexOf("{");
            int braceEnd = json.lastIndexOf("}");
            if (braceStart >= 0 && braceEnd > braceStart) {
                try {
                    String extracted = json.substring(braceStart, braceEnd + 1);
                    return objectMapper.readValue(extracted, PlanDetailVO.class);
                } catch (JsonProcessingException ex) {
                    throw new RuntimeException("JSON 修复失败", ex);
                }
            }
            throw new RuntimeException("无法修复 JSON", e);
        }
    }

    // 构建 userMessage：历史上下文在前，用户问题在后
    private String buildUserMessage(String userInput, String historyContext) {
        if (historyContext == null || historyContext.isBlank()) {
            return "【用户问题】\n" + userInput;
        }
        return "【之前的执行记录】\n" + historyContext
                + "\n\n【用户原始问题】\n" + userInput
                + "\n\n请根据以上执行记录，判断是否还需要继续搜索，或可以给出最终答案。";
    }

    // 异常兜底
    private PlanDetailVO fallbackPlan(String errorMsg) {
        PlanDetailVO vo = new PlanDetailVO();
        vo.setAction("CLARIFY");
        vo.setThought("LLM 调用异常: " + errorMsg);
        vo.setConclusion("抱歉，我暂时遇到了一些问题，请稍等片刻再试～");
        return vo;
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }
}
