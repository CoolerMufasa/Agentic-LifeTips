package com.lifetips.common.enums;

/**
 * 假设的验证状态：PENDING → VERIFYING → CONFIRMED / RULED_OUT
 *
 * @author PCRao
 */
public enum HypothesisStatus {

    // 待验证
    PENDING,

    // 正在验证中
    VERIFYING,

    // 已确认
    CONFIRMED,

    // 已排除
    RULED_OUT
}
