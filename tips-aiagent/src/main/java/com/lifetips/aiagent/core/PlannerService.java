package com.lifetips.aiagent.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifetips.aiagent.config.SystemPrompt;
import com.lifetips.common.enums.ReasoningStage;
import com.lifetips.common.vo.EvaluationResult;
import com.lifetips.common.vo.PlanDetailVO;
import com.lifetips.common.vo.ReasoningVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 规划服务。调用 DeepSeek v4-pro 拆解任务，返回 PlanDetailVO 驱动 AgentEngine 的下一步决策。
 *
 * @author PCRao
 */
@Slf4j
@Service
public class PlannerService {

    private final ChatClient plannerChatClient;
    private final ObjectMapper objectMapper;

    public PlannerService(
            @Qualifier("plannerChatClient") ChatClient plannerChatClient,
            ObjectMapper objectMapper) {
        this.plannerChatClient = plannerChatClient;
        this.objectMapper = objectMapper;
    }

    public PlanDetailVO plan(String userInput, String historyContext) {
        log.info("[Planner] 开始规划, input={}", truncate(userInput, 100));

        String userMessage = buildUserMessage(userInput, historyContext);

        try {
            String rawContent = plannerChatClient.prompt()
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

    /**
     * 评估用户问题复杂度，决定走 DIRECT 快速通道还是 DIAGNOSE 假设推理通道。
     */
    public EvaluationResult evaluate(String userInput) {
        log.info("[Planner:evaluate] 开始评估, input={}", truncate(userInput, 100));

        try {
            String rawContent = plannerChatClient.prompt()
                    .system("""
                        你是一个问题复杂度评估器。根据用户输入判断应走哪条处理路径。
                        规则：
                        - DIRECT：简单明确的问题，可以直接搜索或回答（如"红酒渍怎么洗"）
                        - DIAGNOSE：复杂、不确定、或需要排查的问题（如"豆腐酸了还能吃吗"）

                        请只返回 JSON，格式：{"stage": "DIRECT", "reason": "简单搜索类问题"}
                        或：{"stage": "DIAGNOSE", "reason": "需要多假设验证"}
                        """)
                    .user(userInput)
                    .call()
                    .content();

            EvaluationResult result = objectMapper.readValue(
                    cleanJson(rawContent), EvaluationResult.class);
            log.info("[Planner:evaluate] stage={}, reason={}",
                    result.getStage(), result.getReason());
            return result;
        } catch (Exception e) {
            log.error("[Planner:evaluate] 评估异常: {}", e.getMessage(), e);
            return new EvaluationResult(ReasoningStage.DIRECT, "fallback: " + e.getMessage());
        }
    }

    /**
     * 生成多条假设，启动 DIAGNOSE 推理流程。
     */
    public ReasoningVO generateHypotheses(String userInput, String historyContext) {
        log.info("[Planner:generateHypotheses] userInput={}", truncate(userInput, 100));

        try {
            String userMessage = buildUserMessage(userInput, historyContext);

            String rawContent = plannerChatClient.prompt()
                    .system("""
                        你是一个诊断推理引擎。根据用户问题生成多条可能的假设（Hypothesis），
                        每条假设需标注置信度和验证计划。

                        请只返回 JSON，格式：
                        {
                          "stage": "VERIFY",
                          "question": "用户的核心问题",
                          "hypotheses": [
                            {"id": "h1", "description": "假设描述", "confidence": 0.8, "status": "PENDING", "verificationBasis": ""},
                            {"id": "h2", "description": "另一假设", "confidence": 0.5, "status": "PENDING", "verificationBasis": ""}
                          ],
                          "verifiedFacts": [],
                          "nextAction": {
                            "type": "TOOL_CALL",
                            "targetHypothesisId": "h1",
                            "toolName": "tavilySearch",
                            "query": "搜索关键词"
                          }
                        }
                        """)
                    .user(userMessage)
                    .call()
                    .content();

            return objectMapper.readValue(cleanJson(rawContent), ReasoningVO.class);
        } catch (Exception e) {
            log.error("[Planner:generateHypotheses] 异常: {}", e.getMessage(), e);
            ReasoningVO fallback = new ReasoningVO();
            fallback.setStage(ReasoningStage.CONCLUDE);
            fallback.setQuestion(userInput);
            return fallback;
        }
    }

    /**
     * 根据工作结果更新推理状态，标记已验证的假设并确定下一阶段。
     */
    public ReasoningVO updateReasoning(ReasoningVO reasoning, String workResult) {
        log.info("[Planner:updateReasoning] 更新推理状态");

        try {
            String reasoningJson = objectMapper.writeValueAsString(reasoning);

            String rawContent = plannerChatClient.prompt()
                    .system("""
                        你是一个推理状态更新器。根据最新的验证结果，更新每条假设的状态。
                        状态：PENDING（待验证）→ VERIFYING（验证中）→ CONFIRMED（确认）/ RULED_OUT（排除）

                        更新规则：
                        1. 当前标记为 VERIFYING 的假设，根据验证结果更新为 CONFIRMED 或 RULED_OUT
                        2. 更新 verifiedFacts 列表，添加新确认的事实
                        3. 如果还有 PENDING 的假设，将 stage 保持为 VERIFY 并设置 nextAction
                        4. 如果所有假设都已确认或排除，将 stage 设为 CONCLUDE

                        请只返回更新后的完整 ReasoningVO JSON。
                        """)
                    .user("当前推理状态：\n" + reasoningJson
                            + "\n\n最新验证结果：\n" + workResult)
                    .call()
                    .content();

            return objectMapper.readValue(cleanJson(rawContent), ReasoningVO.class);
        } catch (Exception e) {
            log.error("[Planner:updateReasoning] 异常: {}", e.getMessage(), e);
            reasoning.setStage(ReasoningStage.CONCLUDE);
            return reasoning;
        }
    }

    // 清洗 LLM 返回的 JSON：去掉 ```json 包裹、修复常见格式问题
    private String cleanJson(String raw) {
        String json = raw.trim();
        if (json.startsWith("```")) {
            int start = json.indexOf("\n");
            int end = json.lastIndexOf("```");
            if (start > 0 && end > start) {
                json = json.substring(start, end).trim();
            }
        }
        int braceStart = json.indexOf("{");
        int braceEnd = json.lastIndexOf("}");
        if (braceStart >= 0 && braceEnd > braceStart) {
            json = json.substring(braceStart, braceEnd + 1);
        }
        return json;
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
