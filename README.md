# KamaClaude — 本地 AI Agent 运行时

从零实现的类 Claude Code 本地 Agent 系统，具备完整的 Agent Loop、工具调用、事件流、权限审批、上下文治理和多客户端架构。

⚠️ 本项目为知识星球课程项目，分 8 个阶段（S0–S7）逐步实现。详情见 [notes.kamacoder.com](https://notes.kamacoder.com)

## 架构

```
用户目标
  → CLI / TUI
  → JSON-RPC 2.0 over NDJSON (TCP)
  → kama-core daemon
    → AgentRunner → AgentLoop → LLM Provider
    → ToolRegistry → PermissionManager
    → EventBus → Session Store
  → TUI 实时渲染 / events.jsonl 持久化 / trace 回放
```

**双进程模型**：`kama-core` 为持久化守护进程，`kama`（CLI）和 `kama-tui`（TUI）为客户端，通过 TCP 通信。

## 项目结构

```
KamaClaude
├── src/kama_claude/
│   ├── cli/           # kama CLI 入口
│   │   └── commands/  # 子命令：ping, run, chat, trace, core, version
│   ├── core/          # 守护进程核心
│   │   ├── app.py     # CoreApp 入口，配置→日志→Server→Handler 注册
│   │   ├── loop.py    # AgentLoop (ReAct)
│   │   ├── config.py  # 四层优先级配置
│   │   ├── context.py # 线程/笔记/上下文管理
│   │   ├── bus/       # 类型化 IPC 协议模型
│   │   │   ├── commands.py  # Command 联合类型
│   │   │   ├── events.py    # Event 联合类型
│   │   │   └── envelope.py  # JSON-RPC 2.0 信封
│   │   ├── transport/ # TCP 传输层
│   │   ├── agents/    # 内置 Agent 定义（planner, executor, reviewer）
│   │   ├── llm/       # LLM  Provider 抽象
│   │   ├── mcp/       # MCP 客户端/服务端
│   │   ├── compact/   # 上下文压缩（水位检测、截断、压缩）
│   │   └── events/    # EventBus、事件写入
│   └── tui/           # 终端 UI (Textual)
├── scripts/           # 辅助脚本
├── docs/              # 文档与架构图
├── tests/             # 单元 + 集成测试
├── .kama/             # 本地 kama 上下文
├── pyproject.toml     # PEP 621 配置
└── CLAUDE.md          # 项目开发指引
```

## 技术栈

| 组件 | 技术 |
| --- | --- |
| 语言 | Python 3.12 |
| 通信协议 | JSON-RPC 2.0 + NDJSON over TCP |
| LLM SDK | Anthropic Python SDK |
| TUI 框架 | Textual |
| 数据模型 | Pydantic v2 |
| 工具链 | Ruff, Mypy (strict), pytest |
| 构建 | Hatchling |

## 核心能力

- **ReAct Agent Loop** — 模型思考 → 工具调用 → 结果回填 → 多步执行
- **工具安全** — ToolRegistry 注册、参数校验、权限审批、失败分类与重试
- **事件流** — EventBus 将 Agent 执行过程外化为事件，TUI 实时渲染，持久化到 events.jsonl
- **会话记忆** — Session / thread / notes 三层记忆体系
- **上下文治理** — 水位检测、tool_result 截断、自动 compact + 手动 compact
- **扩展机制** — Skills（工作流）、Subagents（子 Agent 派生）、MCP（外部工具）

## 快速启动

```bash
# 环境
cp .env.example .env
# 填入 ANTHROPIC_API_KEY

# 安装
uv sync

# 启动 daemon
uv run kama-core

# 另一个终端：测试连接
uv run kama ping

# 启动 TUI
uv run kama-tui

# 运行任务
uv run kama run "你的任务目标"
```

## 开发

```bash
# 代码检查
uv run ruff check src tests scripts
uv run mypy src

# 测试
uv run pytest tests/unit -v
uv run pytest tests/integration -v

# 修改协议模型后重新生成文档
uv run python scripts/gen_protocol_doc.py
```

## 阶段路线

| 阶段 | 主题 | 核心问题 |
| --- | --- | --- |
| S0 | 骨架与协议契约 | CLI 与 daemon 通过 IPC 完成 ping/pong |
| S1 | Agent 最小闭环 | 从 goal → LLM → 工具 → 事件文件完整跑通 |
| S2 | 事件流外化 | AgentRunner 搬进 daemon，CLI/TUI 订阅事件流 |
| S3 | 自主规划与 TUI | Agent 任务拆解，TUI 展示执行过程 |
| Trace | 系统级时间线 | IPC / EventBus / LLM 三层数据流可追踪 |
| S4 | 会话与记忆 | 多轮 run 进入 session，thread + notes 接住上下文 |
| S5 | 工具安全 | 参数校验、权限审批、失败分类与重试 |
| S6 | 上下文治理 | 水位检测、截断、compact |
| S7 | 扩展边界 | Skills、Subagents、MCP |