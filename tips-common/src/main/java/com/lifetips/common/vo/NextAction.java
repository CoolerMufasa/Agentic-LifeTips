package com.lifetips.common.vo;

import com.lifetips.common.enums.ActionType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 下一步动作，替代 V0 分散的 action+toolName+planDetail，新增 targetHypothesisId
 *
 * @author PCRao
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NextAction {

    // 动作类型
    private ActionType type;

    // 本轮要验证的假设 ID
    private String targetHypothesisId;

    // 要调用的工具名称
    private String toolName;

    // 搜索/查询关键词
    private String query;
}
