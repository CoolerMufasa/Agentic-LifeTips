package com.lifetips.common.enums;

/**
 * 用户意图类型，用于 IntentRouter 分流请求
 *
 * @author PCRao
 */
public enum IntentType {

    // 日常闲聊、打招呼、感谢等不涉及知识查询的内容
    CHAT,

    // 具体的生活问题、家居技巧、烹饪方法等需要搜索知识的内容
    PLAN;

    // 从字符串转换，解析失败默认返回 CHAT
    public static IntentType fromString(String str) {
        if (str == null || str.isBlank()) {
            return CHAT;
        }
        try {
            return valueOf(str.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CHAT;
        }
    }
}
