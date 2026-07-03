package com.lifetips.common.vo;

import com.lifetips.common.annotation.PromptField;
import lombok.Data;

/**
 * Worker 的输出契约。
 *
 * @author PCRao
 */
@Data
public class WorkDetailVO {

    @PromptField(
        value = "工具执行是否成功",
        example = "true"
    )
    private boolean success;

    @PromptField(
        value = "工具执行后的汇总结论，由 LLM 基于工具返回的原始结果进行提炼",
        example = "搜索结果显示，清洗红酒渍有多种方法：1. 白醋+小苏打法..."
    )
    private String conclusion;

    @PromptField(
        value = "实际调用的工具名称，用于日志追踪和问题定位",
        example = "tavilySearch"
    )
    private String toolName;

    // 创建失败结果，统一所有 Tool 的失败返回格式
    public static WorkDetailVO fail(String toolName, String errorMsg) {
        WorkDetailVO vo = new WorkDetailVO();
        vo.success = false;
        vo.toolName = toolName;
        vo.conclusion = "工具 [" + toolName + "] 执行失败: " + errorMsg;
        return vo;
    }
}
