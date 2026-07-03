package com.lifetips.common.enums;

/**
 * Planner 输出的动作类型。
 *
 * @author PCRao
 */
public enum ActionType {

    // 需要调用工具获取信息
    TOOL_CALL,

    // 信息充足，可以给出最终答案
    FINISH,

    // 信息不足，需要向用户追问
    CLARIFY;

    /**
     * 从字符串安全转换为枚举，做 trim + toUpperCase 归一化。
     * LLM 返回的格式不可控（大小写/空格），直接调 valueOf 可能抛异常。
     *
     * @param str 原始字符串
     * @return 匹配的枚举值，解析失败返回 null
     */
    public static ActionType fromString(String str) {
        if (str == null || str.isBlank()) {
            return null;
        }
        try {
            return valueOf(str.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
