package com.lifetips.common.enums;

/**
 * Planner 推理阶段：DIRECT → 直接解决，DIAGNOSE → 生成假设 → VERIFY → CONCLUDE
 *
 * @author PCRao
 */
public enum ReasoningStage {

    // 信息充足，直接解决（不走假设推理）
    DIRECT,

    // 信息不足，需要生成假设开始排查
    DIAGNOSE,

    // 正在逐条验证假设
    VERIFY,

    // 假设已收窄，可以给出最终结论
    CONCLUDE;

    /**
     * 从字符串安全转换为枚举，解析失败默认返回 DIRECT。
     */
    public static ReasoningStage fromString(String str) {
        if (str == null || str.isBlank()) return DIRECT;
        try {
            return valueOf(str.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DIRECT;
        }
    }
}
