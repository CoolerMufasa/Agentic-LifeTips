package com.lifetips.aiagent.core;

import com.lifetips.aiagent.memory.ShortTermMemory;
import com.lifetips.common.enums.ActionType;
import com.lifetips.common.vo.PlanDetailVO;
import com.lifetips.common.vo.WorkDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Agent 引擎——v0 的控制流中枢，用 while 循环驱动 ReAct（Reason-Act-Observe）闭环。
 *
 * <p>Flux.create() 让阻塞的 while 循环运行在独立线程上，
 * 每次 sink.next() 推送进度到前端（SSE），请求线程立即释放。</p>
 *
 * @author PCRao
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEngine {

    private static final int MAX_LOOP = 5;
    private final PlannerService planner;
    private final WorkerService worker;
    private final ShortTermMemory memory;

    /**
     * 执行 ReAct 推理循环，流式返回每一步的进度和最终答案。
     */
    public Flux<String> execute(String userInput, String chatId) {
        return Flux.<String>create(sink -> {
            try {
                int loopCount = 0;
                String preWorkResult = memory.getHistoryAsText(chatId);

                while (loopCount < MAX_LOOP) {
                    final int round = loopCount + 1;

                    // ── Reason：Planner 分析 ──
                    sink.next("[步骤 " + round + "/" + MAX_LOOP + "] 🤔 分析问题中...");

                    PlanDetailVO plan = planner.plan(userInput, preWorkResult);
                    sink.next("💭 " + plan.getThought());

                    ActionType action = ActionType.fromString(plan.getAction());
                    if (action == null) {
                        log.warn("[Engine] 未知 action: {}, 默认 FINISH", plan.getAction());
                        sink.next("抱歉，我暂时无法处理这个问题，请换个方式问试试～");
                        sink.next("[DONE]");
                        sink.complete();
                        return;
                    }

                    // ── Act：根据 action 分发 ──
                    switch (action) {
                        case TOOL_CALL -> {
                            sink.next("🔍 搜索中: " + plan.getPlanDetail());

                            WorkDetailVO result = worker.doWork(plan);

                            // Observe：结果回流到上下文
                            preWorkResult = memory.buildPreWorkResult(
                                    preWorkResult, plan.getThought(), result.getConclusion());

                            sink.next("📋 搜索完成，正在整理...");
                            loopCount++;
                        }
                        case FINISH -> {
                            String conclusion = plan.getConclusion();
                            memory.save(chatId, userInput, conclusion);
                            sink.next("✅ " + conclusion);
                            sink.next("[DONE]");
                            sink.complete();
                            return;
                        }
                        case CLARIFY -> {
                            sink.next("❓ " + plan.getConclusion());
                            sink.next("[DONE]");
                            sink.complete();
                            return;
                        }
                    }
                }

                // ── 超限兜底 ──
                log.warn("[Engine] 超过最大轮次 {}，强制终止", MAX_LOOP);
                sink.next("抱歉，搜索了几轮还是没能找到满意答案。建议换个方式描述问题～");
                sink.next("[DONE]");
                sink.complete();

            } catch (Exception e) {
                log.error("[Engine] 异常终止: {}", e.getMessage(), e);
                sink.next("抱歉，系统遇到了意外问题，请稍后重试～");
                sink.next("[DONE]");
                sink.complete();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
