# 🏠 Agentic-LifeTips

> 基于 Spring AI + DeepSeek 的食材保鲜递进推理 AI Agent。V1 升级为 Graph 双路径架构，支持假设驱动的多轮验证推理。

[![Java](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.14-brightgreen)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.3-blue)](https://spring.io/projects/spring-ai)
[![License](https://img.shields.io/badge/license-Apache%202.0-lightgrey)](LICENSE)

---

## ✨ 特性

### V1（当前版本）

- **Graph 双路径路由**：DIRECT（简单搜索，复用 V0） + DIAGNOSE（假设驱动的递进推理）
- **假设验证引擎**：食材状态 → 生成多条假设 → 逐条查证 → 汇总结论
- **百炼云 RAG 知识库**：DashScope 食材保鲜知识库 + Tavily 网络搜索双通道
- **推理过程可见**：SSE 消息按 REASONING / HYPOTHESIS_LIST / CONFIRMED / RULED_OUT / CONCLUSION 分类推送
- **双模型策略**：v4-flash 做轻量意图识别和快速执行，v4-pro 做深度推理生成假设
- **结构化推理状态**：ReasoningVO 替代字符串累加，解决多轮 Token 膨胀

### V0（保留）

- ReAct 推理循环（Reason → Act → Observe）
- 多工具协作（搜索 + 格式化输出）
- SSE 流式输出
- 意图识别路由（闲聊 / 知识问题分流）

---

## 🛠 技术栈

| 层面 | 技术选型 |
|------|---------|
| 语言 & 框架 | Java 21 + Spring Boot 3.5 |
| AI 编排 | Spring AI 1.1.3 + Spring AI Alibaba 1.1.2 |
| Graph 控制流 | Spring AI Alibaba Graph (StateGraph) |
| LLM 模型 | DeepSeek v4-pro（推理）/ v4-flash（执行） |
| 知识库 RAG | 阿里云百炼 DashScope 文档检索 |
| 搜索 API | [Tavily Search](https://tavily.com) |
| 流式输出 | Spring WebFlux + SSE |
| 构建工具 | Maven 多模块 |

---

## 🏗 架构（V1）

```
                         ┌─────────────────────────┐
                         │     TipsController       │
                         │  /api/chat    (V1 默认)   │
                         │  /api/v0/chat (V0 保留)   │
                         └──────────┬──────────────┘
                                    │
                             ┌──────▼──────┐
                             │ IntentRouter │
                             └──────┬──────┘
                                    │
                         ┌──────────▼──────────┐
                         │    GraphEngine       │
                         │  StateGraph 双路径    │
                         │  MAX_DIRECT_LOOP=5   │
                         │  MAX_DIAGNOSE_LOOP=10│
                         └─────────┬───────────┘
                                   │
                    ┌──────────────┴──────────────┐
                    ▼                              ▼
            ┌──────────────┐              ┌──────────────┐
            │  DIRECT 路径   │              │ DIAGNOSE 路径 │
            │ (V0 兼容)      │              │ (V1 新增)     │
            │ planner→worker │              │ evaluate →    │
            │ →finish        │              │ gen_hypotheses│
            └──────────────┘              │ →verify→      │
                                          │ update_reason │
                                          │ →conclude     │
                                          └──────────────┘
```

### DIAGNOSE 推理流程

```
用户输入"豆腐有点酸，表面粘，还能吃吗"
  → evaluate（v4-flash，判断走 DIAGNOSE）
  → generate_hypotheses（v4-pro，列出 3 种可能）
  → verify（调 dashScopeRetrieve 查知识库）
  → update_reasoning（v4-pro，更新假设置信度）
  → verify（继续验证下一条假设）
  → update_reasoning
  → ...（所有假设 CONFIRMED 或 RULED_OUT）
  → conclude（汇总最终结论 + 免责声明）
  → SSE 流式推送到前端（按消息类型分类渲染）
```

---

## 🚀 快速开始

### 环境要求

- JDK 21+
- Maven 3.9+
- DeepSeek API Key
- Tavily API Key（[免费注册](https://tavily.com)）
- 阿里云百炼 DashScope API Key（知识库 RAG 功能需要）

### 1. 克隆项目

```bash
git clone https://github.com/CoolerMufasa/Agentic-LifeTips.git
cd Agentic-LifeTips
```

### 2. 配置 API Key

在 `tips-starter/src/main/resources/application.yml` 中配置：

```yaml
spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com
      planner:
        model: deepseek-v4-pro    # 深度推理
      worker:
        model: deepseek-v4-flash  # 快速执行

    dashscope:
      api-key: ${DASHSCOPE_API_KEY}  # 百炼云知识库

tavily:
  api-key: ${TAVILY_API_KEY}         # 网络搜索

dashscope:
  knowledge-base:
    index-name: life-tips-knowledge   # 知识库索引名
```

或通过环境变量：

```bash
export DEEPSEEK_API_KEY=sk-xxx
export TAVILY_API_KEY=tvly-xxx
export DASHSCOPE_API_KEY=sk-ws-xxx
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
├── tips-tools/         # 工具层：Tavily 搜索、DashScope RAG、格式化
├── tips-aiagent/       # 引擎核心：GraphEngine、Planner、Worker、Memory
├── tips-starter/       # 启动入口：Controller、前端页面、配置
└── docs/               # 设计文档、开发进度、练习笔记
```

模块依赖：`tips-starter → tips-aiagent → tips-tools → tips-common`

---

## 📋 版本路线图

| 版本 | 目标 | 状态 |
|------|------|------|
| v0 | 跑通核心链路（ReAct + 多 Tool） | ✅ `v0.1.0` |
| v1 | Graph 双路径 + RAG 知识库 + 递进推理 | ✅ `v1.0.0` |
| v2 | 前端体验优化 + Docker Compose 部署 + 性能优化 + 持久化 | ⏳ 计划中 |
| v3 | 子领域 Agent 拆分 + 用户偏好学习 | ⏳ 计划中 |

### V1 核心升级

| V0 局限 | V1 解决方案 |
|---------|-----------|
| 上下文线性膨胀，Token 爆炸 | 结构化 ReasoningVO 替代字符串累加 |
| 控制流僵硬（while+switch-case） | StateGraph 声明式双路径路由 |
| 搜索结果 SEO 噪音 | 百炼云知识库作为锚数据 + Tavily 补充 |
| Planner 只能表达"下一步" | ReasoningVO 支持假设列表 + 置信度 + 逐条验证 |
| 推理过程黑盒 | SSE 消息按类型分层推送，前端分类渲染 |
| 会话仅内存 | 接入 Spring AI ChatMemory，为 V2 持久化留口子 |

---

## 🔖 Git Tags

```bash
git tag
# v0.1.0  — V0 收官
# v1.0.0  — V1 收官
```

---

## 📄 License

[Apache 2.0](LICENSE)
