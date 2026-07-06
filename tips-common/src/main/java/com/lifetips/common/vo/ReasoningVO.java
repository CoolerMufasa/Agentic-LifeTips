package com.lifetips.common.vo;

import com.lifetips.common.enums.ReasoningStage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 推理状态快照，替代 V0 的 thought 字符串。Graph 节点间传递，每轮只传增量
 *
 * @author PCRao
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReasoningVO {

    // 当前推理阶段
    private ReasoningStage stage;

    // 核心问题（如"豆腐是否已变质"）
    private String question;

    // 假设列表
    private List<Hypothesis> hypotheses = new ArrayList<>();

    // 已验证的事实（去重后的结构化记录）
    private List<String> verifiedFacts = new ArrayList<>();

    // 下一步动作
    private NextAction nextAction;
}
