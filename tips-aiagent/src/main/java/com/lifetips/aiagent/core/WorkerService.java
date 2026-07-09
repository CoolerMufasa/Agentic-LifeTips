package com.lifetips.aiagent.core;

import com.lifetips.aiagent.config.SystemPrompt;
import com.lifetips.common.vo.PlanDetailVO;
import com.lifetips.common.vo.WorkDetailVO;
import com.lifetips.tools.IAgentTool;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行服务。根据 Planner 的规划结果查找并执行对应的工具。
 *
 * @author PCRao
 */
@Slf4j
@Service
public class WorkerService {

    private final ChatClient workerChatClient;
    private final Map<String, ToolCallback> toolRegistry = new HashMap<>();

    /**
     * 构造时 Spring 自动注入所有 implements IAgentTool 的 Bean，
     * 遍历提取 @Tool 方法为 ToolCallback 并注册到 toolRegistry。
     */
    public WorkerService(
            @Qualifier("workerChatClient") ChatClient workerChatClient,
            List<IAgentTool> allAgentTools) {
        this.workerChatClient = workerChatClient;

        for (IAgentTool toolBean : allAgentTools) {
            ToolCallback[] callbacks = ToolCallbacks.from(toolBean);
            for (ToolCallback cb : callbacks) {
                String toolName = cb.getToolDefinition().name();
                toolRegistry.put(toolName, cb);
                log.info("[Worker] 注册 Tool: {}", toolName);
            }
        }
        log.info("[Worker] Tool 注册完成，共 {} 个", toolRegistry.size());
    }

    private static String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }

    public WorkDetailVO doWork(PlanDetailVO plan) {
        String toolName = plan.getToolName();
        String planDetail = plan.getPlanDetail();
        log.info("[Worker] 准备执行, toolName={}, planDetail={}",
                StringUtils.isNotBlank(toolName) ? toolName : "auto",
                truncate(planDetail, 100));

        try {
            String result;

            // 快速通道：Planner 已指定工具 → 跳过 LLM 选工具，直接用 ChatClient 调目标 Tool
            if (StringUtils.isNotBlank(toolName) && toolRegistry.containsKey(toolName)) {
                result = executeNamedTool(toolName, planDetail);
            } else {
                // 兼容通道：未指定工具 → LLM 自主选择（V0 行为）
                result = executeAutoSelect(planDetail);
            }

            log.info("[Worker] 执行完成, resultPreview={}", truncate(result, 80));

            WorkDetailVO vo = new WorkDetailVO();
            vo.setSuccess(true);
            vo.setToolName(StringUtils.isNotBlank(toolName) ? toolName : "auto");
            vo.setConclusion(result);
            return vo;

        } catch (Exception e) {
            log.error("[Worker] Tool 执行异常: {}", e.getMessage(), e);
            return WorkDetailVO.fail("auto", e.getMessage());
        }
    }

    /** 快速通道：直接让 LLM 调用指定 Tool，不选工具、不格式化，单次往返 */
    private String executeNamedTool(String toolName, String query) {
        ToolCallback targetTool = toolRegistry.get(toolName);
        return workerChatClient.prompt()
                .system("你是一个工具调用器。只做一件事：用 " + toolName
                        + " 工具执行用户的任务，直接返回工具的原始结果。不要思考、不要格式化、不要调用其他工具。")
                .user(query)
                .toolCallbacks(targetTool)
                .call()
                .content();
    }

    /** 兼容通道：LLM 自主选择和组合 Tool（V0 行为，较慢） */
    private String executeAutoSelect(String planDetail) {
        String toolListDesc = buildToolListDescription();
        return workerChatClient.prompt()
                .system(SystemPrompt.WORKER_SYSTEM_PROMPT + toolListDesc)
                .user("请执行以下任务：\n" + planDetail)
                .toolCallbacks(toolRegistry.values().toArray(new ToolCallback[0]))
                .call()
                .content();
    }

    // 拼接所有 Tool 的描述供 LLM 参考
    private String buildToolListDescription() {
        StringBuilder sb = new StringBuilder("\n\n【可用工具列表】\n");
        for (ToolCallback cb : toolRegistry.values()) {
            sb.append("- ").append(cb.getToolDefinition().name())
              .append(": ").append(cb.getToolDefinition().description())
              .append("\n");
        }
        return sb.toString();
    }
}
