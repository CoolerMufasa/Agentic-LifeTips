package com.lifetips.aiagent.core;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifetips.aiagent.memory.ShortTermMemory;
import com.lifetips.common.enums.HypothesisStatus;
import com.lifetips.common.enums.ReasoningStage;
import com.lifetips.common.vo.EvaluationResult;
import com.lifetips.common.vo.Hypothesis;
import com.lifetips.common.vo.NextAction;
import com.lifetips.common.vo.PlanDetailVO;
import com.lifetips.common.vo.ReasoningVO;
import com.lifetips.common.vo.WorkDetailVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 声明式 Graph 引擎 —— 用 StateGraph 双路径控制流替代 V0 的命令式 while + switch-case。
 *
 * @author PCRao
 */
@Slf4j
@Service
public class GraphEngine {

    // DIRECT 路径最大循环轮次（与 V0 一致）
    private static final int MAX_DIRECT_LOOP = 5;
    // DIAGNOSE 路径最大循环轮次
    private static final int MAX_DIAGNOSE_LOOP = 10;
    private final PlannerService planner;
    private final WorkerService worker;
    private final ShortTermMemory memory;
    private final ObjectMapper objectMapper;
    private volatile CompiledGraph compiledGraph;

    public GraphEngine(PlannerService planner, WorkerService worker,
                         ShortTermMemory memory, ObjectMapper objectMapper) {
        this.planner = planner;
        this.worker = worker;
        this.memory = memory;
        this.objectMapper = objectMapper;
    }

    private static int parseInt(Object obj) {
        if (obj instanceof Number) return ((Number) obj).intValue();
        try {
            return Integer.parseInt(obj.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }

    /**
     * 构建 StateGraph，启动时编译一次，后续复用。
     */
    private StateGraph buildGraph() throws GraphStateException {
        // KeyStrategyFactory：定义每个 state key 的合并策略
        KeyStrategyFactory keyStrategyFactory = () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put("messages", new AppendStrategy());
            strategies.put("loopCount", new ReplaceStrategy());
            strategies.put("stage", new ReplaceStrategy());
            strategies.put("reasoningJson", new ReplaceStrategy());
            strategies.put("workResult", new ReplaceStrategy());
            strategies.put("finalAnswer", new ReplaceStrategy());
            strategies.put("userInput", new ReplaceStrategy());
            strategies.put("chatId", new ReplaceStrategy());
            strategies.put("action", new ReplaceStrategy());
            strategies.put("thought", new ReplaceStrategy());
            strategies.put("toolName", new ReplaceStrategy());
            strategies.put("planDetail", new ReplaceStrategy());
            strategies.put("conclusion", new ReplaceStrategy());
            return strategies;
        };

        StateGraph graph = new StateGraph("AgentEngineV1", keyStrategyFactory);

        // ========== evaluate 节点：首轮评估 DIRECT vs DIAGNOSE ==========
        graph.addNode("evaluate", node_async(state -> {
            String userInput = state.value("userInput").orElse("").toString();
            log.info("[Graph:evaluate] input={}", truncate(userInput, 80));

            EvaluationResult result = planner.evaluate(userInput);

            Map<String, Object> updates = new HashMap<>();
            updates.put("stage", result.getStage().name());
            log.info("[Graph:evaluate] stage={}", result.getStage());
            return updates;
        }));

        // ========== DIRECT 路径节点（复用 V0 逻辑）==========

        graph.addNode("planner", node_async(state -> {
            String userInput = state.value("userInput").orElse("").toString();
            String messagesStr = state.value("messages").orElse("").toString();

            PlanDetailVO plan = planner.plan(userInput, messagesStr);

            Map<String, Object> updates = new HashMap<>();
            updates.put("action", plan.getAction());
            updates.put("thought", plan.getThought());
            if (StringUtils.isNotBlank(plan.getToolName())) {
                updates.put("toolName", plan.getToolName());
                updates.put("planDetail", plan.getPlanDetail());
            }
            if (StringUtils.isNotBlank(plan.getConclusion())) {
                updates.put("conclusion", plan.getConclusion());
            }
            return updates;
        }));

        graph.addNode("worker", node_async(state -> {
            String toolName = state.value("toolName").orElse("").toString();
            String planDetail = state.value("planDetail").orElse("").toString();
            int loopCount = parseInt(state.value("loopCount").orElse("0"));

            PlanDetailVO plan = new PlanDetailVO();
            plan.setToolName(toolName);
            plan.setPlanDetail(planDetail);

            WorkDetailVO result = worker.doWork(plan);

            Map<String, Object> updates = new HashMap<>();
            updates.put("messages", result.getConclusion());
            updates.put("loopCount", loopCount + 1);
            return updates;
        }));

        graph.addNode("finish", node_async(state -> {
            String conclusion = state.value("conclusion").orElse("抱歉，未找到答案").toString();
            String userInput = state.value("userInput").orElse("").toString();
            String chatId = state.value("chatId").orElse("default").toString();

            memory.save(chatId, userInput, conclusion);
            return Map.of("finalAnswer", conclusion);
        }));

        // ========== DIAGNOSE 路径节点 ==========

        graph.addNode("generate_hypotheses", node_async(state -> {
            String userInput = state.value("userInput").orElse("").toString();
            String messagesStr = state.value("messages").orElse("").toString();

            ReasoningVO reasoning = planner.generateHypotheses(userInput, messagesStr);

            Map<String, Object> updates = new HashMap<>();
            updates.put("reasoningJson", toJson(reasoning));
            updates.put("stage", ReasoningStage.VERIFY.name());
            return updates;
        }));

        graph.addNode("verify", node_async(state -> {
            ReasoningVO reasoning = parseReasoning(state);
            if (reasoning == null || reasoning.getNextAction() == null) {
                return Map.of("stage", ReasoningStage.CONCLUDE.name());
            }

            NextAction nextAction = reasoning.getNextAction();
            int loopCount = parseInt(state.value("loopCount").orElse("0"));

            // 标记当前假设为 VERIFYING
            if (StringUtils.isNotBlank(nextAction.getTargetHypothesisId())
                    && CollectionUtils.isNotEmpty(reasoning.getHypotheses())) {
                for (Hypothesis h : reasoning.getHypotheses()) {
                    if (h.getId().equals(nextAction.getTargetHypothesisId())) {
                        h.setStatus(HypothesisStatus.VERIFYING);
                        break;
                    }
                }
            }

            PlanDetailVO plan = new PlanDetailVO();
            plan.setToolName(nextAction.getToolName());
            plan.setPlanDetail(nextAction.getQuery());

            WorkDetailVO result = worker.doWork(plan);

            Map<String, Object> updates = new HashMap<>();
            updates.put("workResult", result.getConclusion());
            updates.put("messages", result.getConclusion());
            updates.put("loopCount", loopCount + 1);
            updates.put("reasoningJson", toJson(reasoning));
            return updates;
        }));

        graph.addNode("update_reasoning", node_async(state -> {
            ReasoningVO reasoning = parseReasoning(state);
            if (reasoning == null) {
                return Map.of("stage", ReasoningStage.CONCLUDE.name());
            }
            String workResult = state.value("workResult").orElse("").toString();

            ReasoningVO updated = planner.updateReasoning(reasoning, workResult);

            Map<String, Object> updates = new HashMap<>();
            updates.put("reasoningJson", toJson(updated));
            updates.put("stage", updated.getStage().name());
            return updates;
        }));

        graph.addNode("conclude", node_async(state -> {
            ReasoningVO reasoning = parseReasoning(state);
            String userInput = state.value("userInput").orElse("").toString();
            String chatId = state.value("chatId").orElse("default").toString();

            StringBuilder sb = new StringBuilder();
            sb.append("📝 综合判断：\n\n");

            if (reasoning != null && CollectionUtils.isNotEmpty(reasoning.getHypotheses())) {
                for (Hypothesis h : reasoning.getHypotheses()) {
                    if (h.getStatus() == HypothesisStatus.CONFIRMED) {
                        sb.append("✅ ").append(h.getDescription())
                          .append("\n   依据：").append(h.getVerificationBasis()).append("\n\n");
                    } else if (h.getStatus() == HypothesisStatus.RULED_OUT) {
                        sb.append("❌ 排除").append(h.getDescription())
                          .append("\n   依据：").append(h.getVerificationBasis()).append("\n\n");
                    }
                }
            }

            if (reasoning != null && CollectionUtils.isNotEmpty(reasoning.getVerifiedFacts())) {
                sb.append("📋 关键事实：\n");
                for (String fact : reasoning.getVerifiedFacts()) {
                    sb.append("  · ").append(fact).append("\n");
                }
            }

            if (sb.length() == "📝 综合判断：\n\n".length()) {
                sb.append("抱歉，未能充分验证所有可能性，建议换个方式描述问题。");
            }

            memory.save(chatId, userInput, sb.toString());
            return Map.of("finalAnswer", sb.toString());
        }));

        // ========== 边定义 ==========

        graph.addEdge(START, "evaluate");

        // evaluate 分流：DIRECT -> planner, DIAGNOSE -> generate_hypotheses
        graph.addConditionalEdges("evaluate",
            edge_async(state -> state.value("stage").orElse("DIRECT").toString()),
            Map.of("DIRECT", "planner", "DIAGNOSE", "generate_hypotheses")
        );

        // DIRECT 路径
        graph.addConditionalEdges("planner",
            edge_async(state -> state.value("action").orElse("FINISH").toString()),
            Map.of("TOOL_CALL", "worker", "FINISH", "finish", "CLARIFY", END)
        );

        graph.addConditionalEdges("worker",
            edge_async(state -> {
                int loopCount = parseInt(state.value("loopCount").orElse("0"));
                return loopCount < MAX_DIRECT_LOOP ? "planner" : "finish";
            }),
            Map.of("planner", "planner", "finish", "finish")
        );

        graph.addEdge("finish", END);

        // DIAGNOSE 路径
        graph.addEdge("generate_hypotheses", "verify");
        graph.addEdge("verify", "update_reasoning");

        graph.addConditionalEdges("update_reasoning",
            edge_async(state -> {
                int loopCount = parseInt(state.value("loopCount").orElse("0"));
                if (loopCount >= MAX_DIAGNOSE_LOOP) {
                    log.warn("[Graph] DIAGNOSE 超限 loopCount={}", loopCount);
                    return "conclude";
                }

                ReasoningVO r = parseReasoning(state);
                if (r == null) return "conclude";

                if (CollectionUtils.isNotEmpty(r.getHypotheses())) {
                    for (Hypothesis h : r.getHypotheses()) {
                        if (h.getStatus() == HypothesisStatus.PENDING) {
                            return "verify";
                        }
                    }
                }
                return "conclude";
            }),
            Map.of("verify", "verify", "conclude", "conclude")
        );

        graph.addEdge("conclude", END);

        return graph;
    }

    // ========== 辅助方法 ==========

    /**
     * 获取编译后的 Graph（DCL 懒加载）。
     */
    private CompiledGraph getCompiledGraph() {
        if (compiledGraph == null) {
            synchronized (this) {
                if (compiledGraph == null) {
                    try {
                        compiledGraph = buildGraph().compile();
                        log.info("[GraphEngine] Graph 编译完成");
                    } catch (GraphStateException e) {
                        throw new IllegalStateException("Graph 构建失败", e);
                    }
                }
            }
        }
        return compiledGraph;
    }

    /**
     * 执行 Agent 推理。
     */
    public Flux<String> execute(String userInput, String chatId) {
        return Flux.<String>create(sink -> {
            try {
                Map<String, Object> input = new HashMap<>();
                input.put("userInput", userInput);
                input.put("chatId", chatId);
                input.put("loopCount", 0);
                input.put("stage", "");

                Optional<OverAllState> result = getCompiledGraph().invoke(input);

                result.ifPresentOrElse(
                    state -> {
                        String answer = state.value("finalAnswer")
                            .orElse("抱歉，未能找到满意答案").toString();
                        sink.next(answer);
                        sink.next("[DONE]");
                        sink.complete();
                    },
                    () -> {
                        sink.next("抱歉，系统遇到了意外问题～");
                        sink.next("[DONE]");
                        sink.complete();
                    }
                );
            } catch (Exception e) {
                log.error("[GraphEngine] 异常终止: {}", e.getMessage(), e);
                sink.next("抱歉，系统遇到了意外问题，请稍后重试～");
                sink.next("[DONE]");
                sink.complete();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private ReasoningVO parseReasoning(OverAllState state) {
        String json = state.value("reasoningJson").orElse("").toString();
        if (StringUtils.isBlank(json)) return null;
        try {
            return objectMapper.readValue(json, ReasoningVO.class);
        } catch (JsonProcessingException e) {
            log.error("[GraphEngine] Reasoning 反序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("[GraphEngine] 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }
}
