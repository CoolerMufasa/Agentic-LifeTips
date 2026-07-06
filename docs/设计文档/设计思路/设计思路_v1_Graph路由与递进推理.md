# 🗺️ v1：递进推理 Agent —— 从 ReAct 循环到假设驱动的智能排查

> 记录于 V1 设计阶段（2026-07-06），为 V1 唯一设计文档。
> 前置阅读：[[设计思路_v0_ReAct]]

---

## 一、V0 回顾：已解决的问题与留下的缺口

### 1.1 V0 做了什么

基于 Spring AI + DeepSeek，用**单模型 + while 循环 + 直接挂载 Tool** 跑通了 ReAct 闭环：

```java
// AgentEngine.java — V0 核心
while (loopCount < MAX_LOOP) {
    PlanDetailVO plan = planner.plan(userInput, preWorkResult);
    switch (plan.getAction()) {
        case "TOOL_CALL" -> { worker.doWork(plan); loopCount++; }
        case "FINISH"    -> { return; }
        case "CLARIFY"   -> { return; }
    }
    preWorkResult += plan.getThought() + result.getConclusion(); // 字符串累加
}
```

用户问 → Planner 分析 → Worker 搜 → 结果回流 → 下一轮判断，链路完整。

### 1.2 V0 缺口（V1 要解决的）

| 缺口 | V0 现状 | 导致的问题 | 严重程度 |
|------|--------|-----------|---------|
| ① 没有假设能力 | Planner 只能表达"下一步做什么" | 遇到模糊问题（"豆腐有点酸能吃吗"）不会列出可能性再逐条排查 | 高 |
| ② 上下文线性膨胀 | `preWorkResult` 是不断累加的字符串 | 3-4 轮后 Token 爆炸，且信息密度低 | 高 |
| ③ 控制流僵硬 | `while + switch-case` 硬编码 | 新增 Action 类型需改 Engine 源码 | 高 |
| ④ 推理过程黑盒 | 用户只看到"思考中...搜索中...回答" | 不知道 Agent 为什么得出这个结论 | 中 |
| ⑤ 搜索结果噪音 | 只有 Tavily 互联网搜索 | SEO 内容干扰，缺乏结构化知识锚点 | 中 |
| ⑥ 无会话持久化 | 内存 ChatMemory | 重启丢失，延后到 V2 | 低 |

---

## 二、V1 业务定位

### 2.1 版本演进

```
V0：生活小百科（一米宽、一寸深）
  ├── 衣物护理  ─┐
  ├── 厨房清洁   ├── V1 全部保留，走 DIRECT 路径
  ├── 收纳整理   │
  ├── 日常维修  ─┘
  │
  └── 食材保鲜  ★  ── V1 重点深挖，走 DIAGNOSE 路径

V2：食材保鲜 → 菜谱推荐
V3：多领域递进推理 + 子领域 Agent 拆分
```

### 2.2 核心场景

**简单场景（DIRECT）：**
> "鸡蛋放冰箱能放多久？" → 信息充足 → 直接检索 → 回答

**递进场景（DIAGNOSE）：**
> "冰箱里的豆腐闻起来有点酸，表面还有点粘，还能吃吗？"
> → 信息模糊 → 生成 3 种假设 → 逐条查证 → 确认/排除 → 给出判断依据

---

## 三、核心数据模型

V0 的 `PlanDetailVO` 保留给 DIRECT 路径。V1 DIAGNOSE 路径新增以下数据结构。

### 3.1 ReasoningVO（推理状态快照）

替代 V0 `thought: String` 的一句话思考，升级为结构化推理状态：

```java
public class ReasoningVO {
    private ReasoningStage stage;
    // DIRECT → 信息充足 / DIAGNOSE → 生成假设 / VERIFY → 验证中 / CONCLUDE → 收网

    private String question;                  // 核心问题（如"豆腐是否已变质"）
    private List<Hypothesis> hypotheses;      // 假设列表
    private List<String> verifiedFacts;       // 已验证的事实
    private NextAction nextAction;            // 下一步动作
}
```

### 3.2 Hypothesis（单条假设）

```java
public class Hypothesis {
    private String id;                   // 唯一标识
    private String description;          // "已严重变质，不可食用"
    private double confidence;           // 置信度 0.0 ~ 1.0
    private HypothesisStatus status;     // PENDING | CONFIRMED | RULED_OUT
    private String verificationBasis;    // 确认或排除的依据
}
```

### 3.3 NextAction（下一步动作）

```java
public class NextAction {
    private ActionType type;                 // TOOL_CALL | FINISH | CLARIFY
    private String targetHypothesisId;       // 本轮验证哪条假设
    private String toolName;                 // 用哪个工具
    private String query;                    // 搜索/查询内容
}
```

### 3.4 Token 用量对比

| | V0 字符串累加 | V1 结构化快照 |
|------|-------------|-------------|
| 第 1 轮 | ~300 | ~350（结构多些开销） |
| 第 2 轮 | ~600 | ~350（只传当前状态） |
| 第 3 轮 | ~900 | ~350 |
| 趋势 | 线性膨胀 ↑ | 持平 → |

首轮略多，轮次越多越省。且结构化数据可查询、可追溯、可调试。

---

## 四、Graph 控制流

### 4.1 核心概念

Spring AI Alibaba Graph 用三个抽象替代 V0 的命令式 while 循环：

**State（OverAllState）** —— 共享工作台面

```
V0:  preWorkResult 字符串 + loopCount 变量 + PlanDetailVO 字段

V1:  OverAllState = Map<String, Object>
       ├── userInput     → 用户输入
       ├── messages      → 对话历史（AppendStrategy 自动追加）
       ├── stage         → DIRECT / DIAGNOSE（条件边用它路由！）
       ├── reasoning     → ReasoningVO 序列化
       ├── workResult    → Worker 执行结果
       ├── loopCount     → 当前轮次
       └── finalAnswer   → 最终输出
```

每个 key 有合并策略：`ReplaceStrategy`（覆盖）、`AppendStrategy`（追加到列表）。

**Node** —— 执行单元。一个函数：拿 State → 做事 → 返回 State 更新。Node 不需要知道图的拓扑。

**Edge** —— 控制流。固定边（A→B）+ 条件边（根据 state 值动态路由，替代 switch-case）。

### 4.2 V1 图拓扑：DIRECT / DIAGNOSE 双路径

```
                              ┌──────────┐
                              │  START   │
                              └────┬─────┘
                                   │
                                   ▼
                         ┌─────────────────┐
                         │    evaluate     │  首轮评估问题清晰度
                         │  (v4-flash)     │  输出 stage: DIRECT | DIAGNOSE
                         └────────┬────────┘
                                  │
                   条件边：state["stage"]
                         ┌────────┴────────┐
                         ▼                 ▼
                  ┌──────────────┐  ┌──────────────┐
                  │  DIRECT 分支  │  │ DIAGNOSE 分支 │
                  │ (沿用 V0)     │  │   (新增)      │
                  │ MAX_LOOP = 5  │  │ MAX_LOOP = 10 │
                  └──────┬───────┘  └──────┬───────┘
                         │                 │
          ┌──────────────┼─┐        ┌──────┴──────┐
          ▼              ▼ │        ▼             ▼
    ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
    │ worker   │  │ finish   │  │ generate │  │ clarify  │
    │ (搜索)   │  │ (回答)   │  │Hypotheses│  │ (追问)   │
    └────┬─────┘  └──────────┘  └────┬─────┘  └──────────┘
         │                           │
         ▼                           ▼
   条件边：回到              ┌──────────────┐
   planner 或 finish         │   verify     │  调 Tool 验证当前假设
                             └──────┬───────┘
                                    │
                          ┌─────────┼─────────┐
                          ▼                   ▼
                   ┌──────────────┐  ┌──────────────┐
                   │   update     │  │   conclude   │
                   │  Reasoning   │  │   汇总输出    │
                   │ (更新假设状态) │  └──────────────┘
                   └──────┬───────┘
                          │
                   条件边：还有假设待验证？
                          │
                    ┌─────┴─────┐
                    ▼           ▼
              回到 verify    全部已处理
                              → conclude → END
```

### 4.3 节点职责

| Node | 调用 | 读 State | 写 State |
|------|------|---------|---------|
| **evaluate** | `planner.evaluate()` | `userInput` | `stage`（DIRECT/DIAGNOSE） |
| **generate_hypotheses** | `planner.generateHypotheses()` | `userInput`, `messages` | `reasoning`（含 hypotheses） |
| **verify** | `workerService.doWork()` | `reasoning.nextAction` | `workResult`, `loopCount+1` |
| **update_reasoning** | `planner.updateReasoning()` | `reasoning`, `workResult` | `reasoning`（更新置信度和状态） |
| **conclude** | 无需 LLM | `reasoning` | `finalAnswer` |

### 4.4 轮次控制

- DIRECT 路径：`MAX_DIRECT_LOOP = 5`（与 V0 一致，简单搜索 2-3 轮足够）
- DIAGNOSE 路径：`MAX_DIAGNOSE_LOOP = 10`（3-4 条假设各验证一轮 + 收尾，5-6 轮自然结束，10 兜底）
- 超限时强制路由到 conclude，返回已有判断 + "部分可能未充分验证"说明

---

## 五、Planner 与 Prompt 分层

### 5.1 一拆三

V0 的 `PlannerService.plan()` 被拆成三个独立的 LLM 调用：

```
PlannerService
├── evaluate(userInput) → EvaluationResult
│   用 v4-flash，只需输出 DIRECT/DIAGNOSE
│
├── generateHypotheses(userInput, chatHistory) → ReasoningVO
│   用 v4-pro，生成假设列表和初始置信度
│
└── updateReasoning(reasoning, newFact) → ReasoningVO
    用 v4-pro，根据新事实更新假设状态
```

### 5.2 Prompt 三段式结构

每个 Prompt 由三部分拼接：

```
┌─────────────────────────────┐
│ 1. 角色设定（静态常量）       │  ← 你是谁、领域知识
│ 2. 输出 JSON Schema（手写）  │  ← V1 手写，V2 视复杂度引入 PromptBuilder
│ 3. 动作规则（运行时拼接）     │  ← 可用工具列表、当前推理状态
└─────────────────────────────┘
```

### 5.3 evaluate Prompt 核心

> 判断用户问题是否可以直接回答。
> DIRECT：食材+症状明确 → 直接检索。DIAGNOSE：描述模糊需多步判断。
> 仅返回 `{"stage": "DIRECT" | "DIAGNOSE"}`

### 5.4 generateHypotheses Prompt 核心

> 食材保鲜专家。列出所有可能判断结果，从最严重到最轻微排序。
> 每条假设：描述、初始置信度、需验证的关键点。假设之间互斥。
> 优先使用 dashScopeRetrieve 查知识库，不足时用 tavilySearch 补充。

### 5.5 updateReasoning Prompt 核心

> 根据新事实逐条检查假设：支持→升置信度/CONFIRMED，否定→RULED_OUT，不相关→保持 PENDING。
> 将关键事实去重追加到 verifiedFacts。
> 还有 PENDING → 继续验证。只剩一条 CONFIRMED 或全部排除 → CONCLUDE。

### 5.6 PromptBuilder 决策

V1 不用 PromptBuilder。三个 Prompt 各只有 1-2 个核心输出字段，手写可维护。V2/V3 字段增多、Tool schema 维护成本上升时再引入。

---

## 六、Tool 层设计

### 6.1 工具池（技术维度拆分）

```
Tool 池（List<IAgentTool> 自动收集）
├── tavilySearch          ← V0，互联网搜索
├── formatLifeTip         ← V0，格式化输出
├── dashScopeRetrieve     ← V1 新增，百炼云食材保鲜知识库
└── (recipeSearch)        ← V2 预留
```

### 6.2 为什么按技术而非场景拆分

Tool 只管"数据从哪来"，场景认知由 Planner Prompt 负责。Prompt 中引导双通道策略：

> 食材安全类问题优先使用 dashScopeRetrieve 查知识库。知识库未覆盖时，用 tavilySearch 补充。

### 6.3 dashScopeRetrieve

```java
@Tool(
    name = "dashScopeRetrieve",
    description = "查询食材保鲜知识库。覆盖食品安全标准、保质期、"
                + "变质判断标准、保存方法等确定性知识。"
)
public String dashScopeRetrieve(
    @ToolParam(description = "查询关键词，如'豆腐 变质判断标准'") String query
) {
    return dashScopeClient.retrieve(query);
}
```

---

## 七、用户体验

### 7.1 推理过程可见

V1 核心体验升级：Agent 排查过程透明推送前端。

```
用户: "豆腐有点酸，表面有点粘，还能吃吗"

Agent 展示:
🤔 描述了酸味和粘液两个特征，需要从几个方向排查

📋 整理了 3 种可能：
  ⬜ ① 已严重变质（可能性较高）
  ⬜ ② 轻微变质，可烹饪补救
  ⬜ ③ 正常豆腥味+水分渗出

🔍 查证：豆腐变质的判断标准...
✅ 确认：粘液+酸味是细菌滋生的明确信号
❌ 排除③ —— 有粘液不符豆腥味特征

🔍 确认：轻微变质的表现...
✅ 确认：轻微变质只有微酸，不产生粘液
❌ 排除② —— 粘液不符轻微变质标准
✅ 确认①

📝 综合判断：不建议食用。豆腐同时有酸味+粘液，符合严重变质标准。
⚠️ 补充：豆腐开封后建议2天内吃完，需冷藏。
```

### 7.2 SSE 消息类型

```java
{"type": "REASONING",       "content": "整理了 3 种可能..."}
{"type": "HYPOTHESIS_LIST", "content": "[...]"}
{"type": "VERIFYING",       "content": "正在查证：豆腐变质的判断标准"}
{"type": "CONFIRMED",       "content": "粘液+酸味是细菌滋生的明确信号"}
{"type": "RULED_OUT",       "content": "排除③ —— 有粘液不符豆腥味特征"}
{"type": "CONCLUSION",      "content": "不建议食用"}
{"type": "DISCLAIMER",      "content": "食品安全建议仅供参考"}
```

---

## 八、构建顺序

| 步骤 | 事项 | 涉及模块 | 说明 |
|------|------|---------|------|
| 1 | 数据模型升级 | tips-common | 新增 ReasoningVO、Hypothesis、NextAction、ReasoningStage |
| 2 | 双模型 Bean | tips-aiagent | plannerChatClient(v4-pro) + workerChatClient(v4-flash) |
| 3 | ChatMemory 对接 | tips-aiagent | ConcurrentHashMap → Spring AI MessageWindowChatMemory |
| 4 | Graph 控制流 | tips-aiagent | evaluate → DIRECT/DIAGNOSE 双路径 |
| 5 | Tool 层 + 百炼云 RAG | tips-tools | 新增 dashScopeRetrieve Tool |
| 6 | Prompt 分层 | tips-aiagent | evaluate / generateHypotheses / updateReasoning 三套 Prompt |
| 7 | SSE 消息类型升级 | tips-starter | Controller + 前端按消息 type 分类渲染 |

---

## 九、成功标准

- [ ] 简单食材问题走 DIRECT 路径，不启动假设推理（Token 可控）
- [ ] 模糊食材问题走 DIAGNOSE 路径：生成假设 → 逐条验证 → 汇总结论
- [ ] 推理过程通过 SSE 实时推送，用户可见每一步
- [ ] dashScopeRetrieve + tavilySearch 双通道可用，知识库优先
- [ ] 新增假设状态或推理阶段，不修改 AgentEngine 源码
- [ ] V0 的非食材场景（衣物、清洁等）正常运行，不受影响
- [ ] DIRECT 轮次上限 5，DIAGNOSE 轮次上限 10
- [ ] Graph 可导出为可视化图（Mermaid/PlantUML）

---

## 十、未纳入（后续版本）

| 事项 | 版本 | 原因 |
|------|------|------|
| 菜谱推荐场景 | V2 | 食材保鲜链路验证后再扩展 |
| PromptBuilder 反射接入 | V2 | V1 Prompt 字段少，手写够用 |
| Docker Compose 部署 | V2 | 聚焦推理链路 |
| MySQL/Redis 持久化 | V2 | 需持久化 Reasoning 对象历史 |
| 用户偏好学习 | V3 | 需用户数据积累 |
| 子领域 Agent 拆分 | V3 | Tool 50+ 时按领域拆 |

---

## 十一、Graph 进阶特性（知识储备）

以下 Spring AI Alibaba Graph 已支持，V1 暂不使用。

### 并行分支

```java
graph.addEdge("planner", List.of("tavily", "rag", "local"));
graph.addEdge(List.of("tavily", "rag", "local"), "aggregator");
```

### 子图嵌套（Agent as Node）

```java
ReactAgent kitchenAgent = ReactAgent.builder()
    .name("kitchen_expert").model(chatModel).build();
parentGraph.addNode("kitchen", kitchenAgent.asNode(true, false));
```

### 可视化导出 + 断点续跑

```java
graph.toMermaid();   // 导出图结构
graph.toPlantUML();
app.resume(threadId); // 从中断点恢复
```

---

## 📎 参考资料

- [Spring AI Alibaba 官方文档 - Workflow](http://java2ai.com/en/docs/frameworks/agent-framework/advanced/workflow/)
- [Spring AI Alibaba Graph GitHub](https://github.com/alibaba/spring-ai-alibaba)
- [Achieve Manus in A Dozen Lines of Code](https://www.alibabacloud.com/blog/602455)
