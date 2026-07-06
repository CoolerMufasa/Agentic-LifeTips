package com.lifetips.common.vo;


import lombok.Data;

/**
 * 单次验证的输入
 *
 * @author PCRao
 */
@Data
public class VerificationInput {

    // 本轮要验证的假设 ID
    private String hypothesisId;

    // 要调用的工具名称
    private String toolName;

    // 搜索关键词
    private String query;

    /**
     * 从下一步动作中取出单次验证的输入
     *
     * @param nextAction 下一步动作
     * @return 单次验证的输入
     */
    public static VerificationInput fromNextAction(NextAction nextAction) {
        VerificationInput verificationInput = new VerificationInput();
        verificationInput.setHypothesisId(nextAction.getTargetHypothesisId());
        verificationInput.setToolName(nextAction.getToolName());
        verificationInput.setQuery(nextAction.getQuery());
        return verificationInput;
    }
}
