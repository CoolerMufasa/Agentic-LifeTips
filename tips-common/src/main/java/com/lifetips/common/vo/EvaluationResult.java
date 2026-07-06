package com.lifetips.common.vo;

import com.lifetips.common.enums.ReasoningStage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * evaluate 节点输出：DIRECT 还是 DIAGNOSE
 *
 * @author PCRao
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationResult {

    // DIRECT 或 DIAGNOSE
    private ReasoningStage stage;

    // 评估理由（日志用，不参与推理）
    private String reason;
}
