package com.lifetips.common.vo;

import com.lifetips.common.enums.HypothesisStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条假设实体，生命周期：PENDING → VERIFYING → CONFIRMED / RULED_OUT
 *
 * @author PCRao
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Hypothesis {

    // 唯一标识，如 "h1"
    private String id;

    // 假设描述，如"豆腐已严重变质，不可食用"
    private String description;

    // 置信度 0.0 ~ 1.0
    private double confidence;

    // 验证状态
    private HypothesisStatus status;

    // 确认或排除此假设的事实依据
    private String verificationBasis;
}
