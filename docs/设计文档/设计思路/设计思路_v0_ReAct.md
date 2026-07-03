# 🗺️ v0：单模型 ReAct 引擎——生活小百科 Agent 最小可行版本

---

## 🎯 阶段目标

基于 Spring AI Alibaba 生态，用 **单模型（DeepSeek）+ While 循环 + 直接挂载 Tool** 的轻量方案，跑通"用户提问生活小技巧 → Agent 搜索资料 → 整理返回"的完整 ReAct（Reason-Act-Observe）闭环。

本阶段的核心诉求是**快速验证链路可行性**，不追求架构完美——复杂的放在后续版本迭代。

---

## 🏗️ 设计思路

### 1. ReAct 工作流定义

- **思考（Reasoning）**：分析用户问题类型（衣物护理/厨房清洁/收纳技巧/……），拆解为可执行的搜索步骤，决定下一步动作。
- **行动（Acting）**：根据规划结果，真实调用搜索工具获取网络资料，或向前端输出最终答案，或向用户追问补全信息。
- **观察（Observation）**：将工具返回的搜索结果作为事实注入下一轮决策上下文。
- **迭代（Loop）**：基于观察结果继续"思考-行动-观察"闭环，直到任务终结或达到轮次上限。

### 2. 单模型驱动

DeepSeek 同时承担规划与执行两个角色，降低 v0 的架构复杂度：

- **Planner 身份**：使用 `temperature=0.0` 的 ChatClient，基于 SystemPrompt 中的角色设定和输出格式约束，输出结构化 JSON（`PlanDetailVO`）。
- **Worker 身份**：同一 ChatModel 挂载 `ToolCallback`，让模型自主决定调用哪个工具、填入什么参数。

架构上保留 Planner Service 和 Worker Service 两个独立类——后续版本如需切换双模型，只需替换 Worker 的底层 ChatModel，调用方无感知。

### 3. 意图路由

不是所有用户输入都需要进入 Agent 引擎。通过轻量意图识别将请求分流：

- **CHAT**（闲聊）→ 直接调用 LLM 回复，不走 ReAct 循环
- **PLAN**（知识问题）→ 进入 Agent 引擎，启动搜索-整理-返回闭环

### 4. v0 的技术取舍

| 决策 | 做法 | 原因 |
|------|------|------|
| 不引入 RAG 知识库 | Tool 直接挂载在 ChatClient 上 | v0 只有 2-3 个 Tool，不需要检索 |
| 不做数据库持久化 | 聊天记忆用内存 `InMemoryChatMemory` | demo 阶段够用，后续再接入 |
| 不搞 Docker Compose | 裸跑 Spring Boot 应用 | 减少环境搭建时间 |
| While 循环而非 Graph | 手写 `while (loopCount < max)` | 先跑通，v1 再结构化 |

---

## 📊 核心流程设计

```
┌─────────────────────────────────────────────────────────┐
│                    用户通过浏览器输入问题                    │
│              "白衬衫上的红酒渍怎么洗？"                      │
└────────────────────────┬────────────────────────────────┘
                         ▼
              TipsController (WebFlux SSE)
                         │
                         ▼
                   IntentRouter
               ┌─────────┴─────────┐
               │                   │
           CHAT（闲聊）        PLAN（知识问题）
               │                   │
               ▼                   ▼
         直接 LLM 回复       AgentEngine (while 循环)
                                  │
              ┌───────────────────┼───────────────────┐
              ▼                   ▼                   ▼
         Planner.plan()    Edge 判断 action    轮次超限兜底
         (DeepSeek 分析)    ├─ TOOL_CALL            │
              │             │   → Worker.doWork()   │
              │             │   → 调 Tool 搜索      │
              │             │   → 结果回流循环      │
              │             ├─ FINISH               │
              │             │   → 汇总返回          │
              │             └─ CLARIFY              │
              │                 → 追问用户          │
              └─────────────────────────────────────┘
                         │
                         ▼
                  SSE 流式推送到前端
```

### 轮次控制

```
maxLoopTimes = 5  →  Planner → Worker → Planner → Worker → ... → FINISH
                                                    ↓
                                        超过 5 轮仍未 FINISH：
                                        返回 "抱歉，暂未找到满意答案，
                                        建议尝试以下搜索关键词：xxx"
```

---

## 🧱 模块结构

```
Agentic-LifeTips/
├── tips-starter/       # 启动入口：Spring Boot 主程序 + Controller + 前端页面
├── tips-aiagent/       # Agent 引擎核心：Planner + Worker + Engine + Memory + Router
├── tips-tools/         # 工具层：Tavily 搜索 + 格式化输出 + 后续 RAG 检索
├── tips-common/        # 公共模块：VO 定义、枚举、异常
└── docs/               # 设计文档
```

### 模块依赖关系

```
tips-starter  → tips-aiagent + tips-tools + tips-common
tips-aiagent  → tips-tools + tips-common
tips-tools    → tips-common
```

### 为什么拆模块

- `tips-tools` 可独立复用：纯 Tool 定义 + API 客户端，不依赖 Agent 引擎
- `tips-aiagent` 不耦合 Controller：纯引擎逻辑，后续可被不同入口（Controller / 命令行 / 测试）调用
- `tips-starter` 只负责启动和接口暴露：薄薄一层，逻辑不在入口模块堆积

---

## 🛠️ 自下而上的构建顺序

### 第一步：基础设施初始化 —— ChatClientConfig

配置 DeepSeek ChatClient Bean：

```java
@Configuration
public class ChatClientConfig {

    @Bean("deepseekChatClient")
    public ChatClient deepseekChatClient(DeepSeekChatModel deepSeekChatModel) {
        return ChatClient.builder(deepSeekChatModel)
                .defaultOptions(DeepSeekChatOptions.builder()
                        .temperature(0.0)   // Agent 场景需要确定性输出
                        .build())
                .build();
    }
}
```

**产出物**：项目启动后可拿到 `deepseekChatClient` Bean，能调用 LLM。

---

### 第二步：数据契约定义 —— PlanDetailVO + PromptBuilder

核心数据结构，承载 Planner 每次调用的输出：

```java
public class PlanDetailVO {

    @PromptField(value = "TOOL_CALL / FINISH / CLARIFY",
                 example = "TOOL_CALL")
    private String action;

    @PromptField(value = "当前步骤的思考过程", example = "用户问的是红酒渍清洗方法...")
    private String thought;

    @PromptField(value = "需要调用的工具名称，action=TOOL_CALL 时必填",
                 example = "tavilySearch")
    private String toolName;

    @PromptField(value = "传给工具的搜索关键词或参数", example = "白衬衫 红酒渍 清洗方法")
    private String planDetail;

    @PromptField(value = "最终答案，action=FINISH 时必填",
                 example = "清洗红酒渍推荐使用白醋+小苏打...")
    private String conclusion;
}
```

`@PromptField` 注解 + `PromptBuilder` 反射读取注解 → 自动生成 SystemPrompt 中的 JSON 格式说明，确保改 VO 字段时 Prompt 自动同步。

**产出物**：LLM 输出可解析为结构化 Java 对象。

---

### 第三步：规划引擎 —— PlannerService

封装 DeepSeek 调用，将用户问题和历史上下文发送给模型，拿回 `PlanDetailVO`：

```java
@Service
public class PlannerService {

    private final ChatClient deepseekChatClient;

    public PlanDetailVO plan(String userInput, String historyContext) {
        return deepseekChatClient.prompt()
                .system(PLANNER_SYSTEM_PROMPT)
                .user(buildUserMessage(userInput, historyContext))
                .call()
                .entity(PlanDetailVO.class);
    }
}
```

**SystemPrompt 核心要点**：
- 角色设定："你是一位生活小百科专家，擅长家居清洁、衣物护理、厨房技巧等领域"
- 输出约束：必须返回合法 JSON，格式参照 `PlanDetailVO`
- 动作规则：需要搜索 → TOOL_CALL / 能直接回答 → FINISH / 信息不足 → CLARIFY

**产出物**：DeepSeek 能根据用户问题拆解任务、给出下一步动作。

---

### 第四步：工具层 —— IAgentTool + TavilyApiClient + @Tool 方法

**4.1 标记接口**

```java
// 空接口，只用于 Spring 类型扫描
public interface IAgentTool {
}
```

**4.2 外部 API 客户端**

```java
@Service
public class TavilyApiClient {

    public String searchAsText(String query, int maxResults) {
        // HTTP POST → Tavily Search API
        // 将 JSON 结果格式化为【摘要】+【搜索结果列表】的自然语言文本
        // 返回给 LLM 便于理解和汇总
    }
}
```

**4.3 Tool 定义**

```java
@Component
public class SearchTools implements IAgentTool {

    @Autowired
    private TavilyApiClient tavilyApiClient;

    @Tool(name = "tavilySearch",
          description = "在互联网上搜索生活小技巧、家居清洁方法、" +
                        "衣物护理知识等内容。输入搜索关键词，返回相关结果摘要。")
    public String tavilySearch(
        @ToolParam(description = "搜索关键词") String query
    ) {
        return tavilyApiClient.searchAsText(query, 5);
    }
}
```

**4.4 Tool 自动注册**

WorkerService 构造时通过 `List<IAgentTool>` 自动收集所有 Tool Bean，构建 `globalToolRegistry`（Map<toolName, ToolCallback>）。

**产出物**：新增 Tool 只需三步——建类 implements IAgentTool → @Component → @Tool 方法，无需修改配置。

---

### 第五步：执行器 —— WorkerService

```java
@Service
public class WorkerService {

    private final ChatClient deepseekChatClient;
    private final Map<String, ToolCallback> toolRegistry;

    public WorkDetailVO doWork(PlanDetailVO plan) {
        // 1. 从 toolRegistry 取出 ToolCallback
        ToolCallback callback = toolRegistry.get(plan.getToolName());

        // 2. 带 ToolCallback 调用 DeepSeek
        return deepseekChatClient.prompt()
                .system(WORKER_SYSTEM_PROMPT)
                .user(plan.getPlanDetail())
                .toolCallbacks(callback)
                .call()
                .entity(WorkDetailVO.class);
    }
}
```

**关键点**：Spring AI 框架自动处理 toolCall 的触发和结果注入——DeepSeek 返回 `toolCall` 请求 → 框架执行 Tool 方法 → 结果自动注入回对话上下文 → 模型基于结果生成 `WorkDetailVO`。

**产出物**：Planner 规划的搜索任务可被真实执行。

---

### 第六步：控制引擎 —— AgentEngine

整个 v0 系统的控制流中枢：

```java
@Service
public class AgentEngine {

    private final PlannerService planner;
    private final WorkerService worker;
    private static final int MAX_LOOP = 5;

    public Flux<String> execute(String userInput, String chatId) {
        return Flux.create(sink -> {
            int loopCount = 0;
            String preWorkResult = "";
            String currentUserInput = userInput;

            while (loopCount < MAX_LOOP) {
                // 1. Planner 规划
                PlanDetailVO plan = planner.plan(currentUserInput, preWorkResult);
                sink.next(formatThought(plan.getThought()));  // SSE 推送思考过程

                // 2. 根据 action 决定下一步
                switch (plan.getAction()) {
                    case "TOOL_CALL" -> {
                        WorkDetailVO result = worker.doWork(plan);
                        preWorkResult += formatWorkResult(result);
                        sink.next(formatProgress(result.getConclusion()));
                    }
                    case "FINISH" -> {
                        sink.next(plan.getConclusion());
                        sink.complete();
                        return;
                    }
                    case "CLARIFY" -> {
                        sink.next("[追问] " + plan.getConclusion());
                        sink.complete();
                        return;
                    }
                }
                loopCount++;
            }
            // 超限兜底
            sink.next("抱歉，暂时没能找到满意的答案。建议尝试搜索：" + extractKeywords(userInput));
            sink.complete();
        });
    }
}
```

**防御式设计**：
- `MAX_LOOP = 5` 防止模型陷入调用死循环
- 通过 `Consumer<String>` 或 Flux 实时推送进度给前端
- 超限时给出搜索建议而非报错

**产出物**：Agent 能自主循环推理，前端实时看到进度。

---

### 第七步：意图路由 —— IntentRouter

```java
@Service
public class IntentRouter {

    private final ChatClient chatClient;

    public IntentType recognize(String userInput) {
        String result = chatClient.prompt()
                .system("""
                    判断用户输入是闲聊(CHAT)还是需要搜索知识的问题(PLAN)。
                    仅回复 CHAT 或 PLAN。
                    """)
                .user(userInput)
                .call()
                .content();
        return IntentType.valueOf(result.trim().toUpperCase());
    }
}
```

**路由规则**：
- CHAT → "你好" / "今天天气" → 直接调用 chatClient 回复
- PLAN → "红酒渍怎么洗" / "冰箱有异味怎么办" → 进入 AgentEngine

**产出物**：闲聊不浪费 ReAct 循环的 Token。

---

### 第八步：前端接口 —— TipsController

```java
@RestController
public class TipsController {

    private final IntentRouter router;
    private final AgentEngine engine;

    @GetMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(
            @RequestParam String message,
            @RequestParam String chatId
    ) {
        if (router.recognize(message) == IntentType.CHAT) {
            return handleChat(message);
        }
        return engine.execute(message, chatId)
                .map(content -> ServerSentEvent.<String>builder()
                        .data(content)
                        .build());
    }
}
```

**产出物**：前端通过 SSE 端点接收 Agent 的流式输出。

---

## 🚨 v0 已识别的问题

这些问题已知但刻意不在 v0 解决——留给后续版本作为迭代动力：

### 1. 上下文线性膨胀

每轮 Planner 的 `thought` 和 Worker 的 `conclusion` 通过字符串拼接写入 `preWorkResult`，3-4 轮后 Prompt 膨胀严重，Token 消耗急剧增加且容易诱发幻觉。

→ **v1 方向**：引入语义记忆压缩，超过阈值时用 LLM 将对话历史压缩为简短摘要。

### 2. 控制流僵硬

`AgentEngine` 内部的 `switch-case` 硬编码了所有可能的状态跳转。新增一种 Action 类型（例如后续引入"对比多个搜索结果"的 COMPARE 动作）就要改 Engine 源码。

→ **v1 方向**：引入 Node/Edge 路由模式，节点和边独立定义，Engine 只负责按路由表分发。

### 3. 搜索结果质量不可控

Tavily 搜索返回的网页内容可能有 SEO 噪音、广告或信息不准确，LLM 汇总时容易被误导。

→ **v1 方向**：引入自建知识库 RAG——整理优质生活技巧文档，通过向量检索直接匹配最相关的内容，作为搜索工具的补充。

### 4. 无会话持久化

内存 `ChatMemory` 在服务重启后丢失所有对话历史。

→ **v2 方向**：引入 MySQL/Redis 持久化聊天记录。

---

## 📋 v0 成功标准

- [ ] 用户可以输入生活小技巧问题，Agent 返回有用的答案
- [ ] 闲聊（"你好"）不触发 ReAct 循环，直接回复
- [ ] 前端能实时看到 Agent 每一步的思考过程（SSE）
- [ ] 同一个 chatId 的多轮对话保持上下文连贯
- [ ] 超过 5 轮仍未结束的任务优雅降级，返回搜索建议而非报错

---

## 🔮 后续版本展望

| 版本 | 核心主题 | 计划引入 |
|------|---------|---------|
| v1 | 结构化 + RAG | Node/Edge 路由、百炼云知识库检索、记忆压缩 |
| v2 | 体验优化 | 流式输出增强、前端美化、Docker Compose 部署 |
| v3 | 扩展与深入 | 子领域 Agent 拆分、用户偏好学习、生活技巧知识库规模扩充 |
