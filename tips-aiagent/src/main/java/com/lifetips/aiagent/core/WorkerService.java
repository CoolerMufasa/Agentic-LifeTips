package com.lifetips.aiagent.core;

import com.lifetips.aiagent.config.SystemPrompt;
import com.lifetips.common.vo.PlanDetailVO;
import com.lifetips.common.vo.WorkDetailVO;
import com.lifetips.tools.IAgentTool;
import lombok.extern.slf4j.Slf4j;
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
        log.info("[Worker] 准备执行, planDetail={}", truncate(plan.getPlanDetail(), 100));

        // 将所有可用 Tool 的 description 拼成列表，注入 Worker Prompt
        String toolListDesc = buildToolListDescription();

        try {
            String result = workerChatClient.prompt()
                    .system(SystemPrompt.WORKER_SYSTEM_PROMPT + toolListDesc)
                    .user("请执行以下任务：\n" + plan.getPlanDetail())
                    .toolCallbacks(toolRegistry.values().toArray(new ToolCallback[0]))
                    .call()
                    .content();

            log.info("[Worker] Tool 执行完成, resultPreview={}", truncate(result, 80));

            WorkDetailVO vo = new WorkDetailVO();
            vo.setSuccess(true);
            vo.setToolName("auto");
            vo.setConclusion(result);
            return vo;

        } catch (Exception e) {
            log.error("[Worker] Tool 执行异常: {}", e.getMessage(), e);
            return WorkDetailVO.fail("auto", e.getMessage());
        }
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
