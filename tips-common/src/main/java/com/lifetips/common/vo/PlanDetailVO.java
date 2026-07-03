package com.lifetips.common.vo;

import com.lifetips.common.annotation.PromptField;
import lombok.Data;

/**
 * Planner 的输出契约。字段用 String 而非枚举，接收后用 fromString() 做容错转换。
 *
 * @author PCRao
 */
@Data
public class PlanDetailVO {

    @PromptField(
        value = "当前步骤的思考过程，说明为什么选择这个动作",
        example = "用户问的是红酒渍清洗方法，属于衣物护理领域，需要搜索相关资料"
    )
    private String thought;

    @PromptField(
        value = "下一步动作：TOOL_CALL（需要调工具搜索）/ FINISH（可以给出最终答案）/ CLARIFY（信息不足需要追问用户）",
        example = "TOOL_CALL"
    )
    private String action;

    @PromptField(
        value = "要调用的工具名称，仅 action=TOOL_CALL 时需要填写。可选值：tavilySearch",
        example = "tavilySearch"
    )
    private String toolName;

    @PromptField(
        value = "任务描述，action=TOOL_CALL 时需要填写。Worker 会根据此描述自动选择合适的工具",
        example = "搜索白衬衫红酒渍的清洗方法"
    )
    private String planDetail;

    @PromptField(
        value = "最终答案，仅 action=FINISH 时需要填写。需用自然语言组织好完整的回答",
        example = "清洗白衬衫上红酒渍的推荐方法：1. 立即用冷水冲洗污渍处...2. 用小苏打和白醋..."
    )
    private String conclusion;
}
