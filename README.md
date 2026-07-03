# 🏠 Agentic-LifeTips

> 基于 Spring AI + DeepSeek 的生活小百科 AI Agent。让 LLM 自主搜索、整理并返回结构化的生活技巧答案。

[![Java](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.14-brightgreen)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.3-blue)](https://spring.io/projects/spring-ai)
[![License](https://img.shields.io/badge/license-Apache%202.0-lightgrey)](LICENSE)

---

## ✨ 特性

- **ReAct 推理循环**：Reason（思考）→ Act（行动）→ Observe（观察）闭环，Agent 自主决策是否需要继续搜索
- **多工具协作**：网络搜索 + 格式化输出，LLM 自动选择合适工具并组合使用
- **SSE 流式输出**：实时推送 Agent 每一步的思考过程，前端即时展示
- **意图识别路由**：闲聊与知识问题自动分流，避免 Token 浪费
- **多轮对话记忆**：上下文保持 + 对话历史管理
- **结构化答案**：材料 → 步骤 → 注意事项三段式输出

---

## 🛠 技术栈

| 层面 | 技术选型 |
|------|---------|
| 语言 & 框架 | Java 21 + Spring Boot 3.5 |
| AI 编排 | Spring AI 1.1.3 + Spring AI Alibaba 1.1.2 |
| LLM 模型 | DeepSeek v4（OpenAI 兼容接口） |
| 搜索 API | [Tavily Search](https://tavily.com) |
| 流式输出 | Spring WebFlux + SSE |
| 构建工具 | Maven 多模块 |

---

## 🏗 架构

```
┌─────────────────────────────────────────────────┐
│                   TipsController                │
│                   (SSE 端点)                     │
└──────────┬──────────────────────────────────────┘
           │
    ┌──────▼──────┐
    │ IntentRouter │  ← "你好" → 闲聊 / "红酒渍怎么洗" → Agent
    └──────┬──────┘
           │
    ┌──────▼──────────┐
    │   AgentEngine    │  ← while 循环驱动 ReAct
    │   (MAX_LOOP=5)   │
    └──┬───────────┬───┘
       │           │
  ┌────▼───┐  ┌───▼──────┐
  │Planner │  │  Worker  │
  │Service │  │  Service │
  │(思考)  │  │ (执行)   │
  └────────┘  └──┬───────┘
                 │
          ┌──────▼──────┐
          │  Tool 注册表  │
          │  tavilySearch │
          │  formatLifeTip│
          └──────────────┘
```

### ReAct 循环流程

```
用户输入 → IntentRouter(分流)
              ↓
         AgentEngine(while 循环)
              ↓
     ┌─── Planner 分析 → 返回 PlanDetailVO
     │        ↓
     │   action=TOOL_CALL → Worker 调 Tool 搜索
     │        ↓
     │   结果回流 preWorkResult
     │        ↓
     └── 下一轮 Planner 判断 → FINISH/CLARIFY/继续
              ↓
         SSE 流式推送 → 前端渲染
```

---

## 🚀 快速开始

### 环境要求

- JDK 21+
- Maven 3.9+
- DeepSeek API Key
- Tavily API Key（[免费注册](https://tavily.com)）

### 1. 克隆项目

```bash
git clone https://github.com/你的用户名/Agentic-LifeTips.git
cd Agentic-LifeTips
```

### 2. 配置 API Key

在 `tips-starter/src/main/resources/application.yml` 中配置：

```yaml
spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY}   # 你的 DeepSeek API Key
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-v4-pro

tavily:
  api-key: ${TAVILY_API_KEY}         # 你的 Tavily API Key
```

或通过环境变量：

```bash
export DEEPSEEK_API_KEY=sk-xxx
export TAVILY_API_KEY=tvly-xxx
```

### 3. 构建 & 启动

```bash
mvn install -DskipTests
mvn spring-boot:run -pl tips-starter
```

或直接在 IDEA 中运行 `TipsApplication.java`

### 4. 打开浏览器

访问 `http://localhost:8080` 开始对话。

---

## 📁 项目结构

```
Agentic-LifeTips/
├── tips-common/        # 数据契约：VO、枚举、注解、异常
├── tips-tools/         # 工具层：Tool 定义、API 客户端
├── tips-aiagent/       # 引擎核心：Planner、Worker、Engine、Memory
├── tips-starter/       # 启动入口：Controller、前端页面、配置
└── docs/               # 设计文档
```

模块依赖：`tips-starter → tips-aiagent → tips-tools → tips-common`

---

## 📋 版本路线

| 版本 | 核心能力 | 状态 |
|------|---------|------|
| v0 | 单模型 ReAct + 多 Tool 协作 + SSE 流式输出 | ✅ 已完成 |
| v1 | Node/Edge 结构化路由 + RAG 知识库检索 + 记忆压缩 | 规划中 |
| v2 | 前端体验优化 + Docker Compose 部署 + 数据持久化 | 规划中 |
| v3 | 子领域 Agent 拆分 + 用户偏好学习 + 知识库规模扩充 | 规划中 |

---

## 📄 License

[Apache 2.0](LICENSE)
