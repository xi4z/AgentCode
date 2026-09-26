# AgentCode Java

AgentCode 的 Java 实现骨架，基于 Spring Boot + Spring AI Alibaba。

> 当前仅包含项目骨架，不包含业务代码。

## 目录结构

```text
Java/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/agentcode/
│   │   │   ├── agent/                  # Agent 编排：Graph、Node、Subagent
│   │   │   │   ├── graph/
│   │   │   │   ├── node/
│   │   │   │   └── subagent/
│   │   │   ├── common/                 # 通用 DTO / 结果类型 / 常量
│   │   │   ├── compact/                # 上下文压缩
│   │   │   ├── config/                 # Spring 配置与属性绑定
│   │   │   ├── context/                # AgentContext / ExecutionContext
│   │   │   ├── event/                  # 事件发布与 SSE/WebSocket 推送
│   │   │   ├── mcp/                    # MCP 客户端与工具适配
│   │   │   ├── permission/             # 权限策略与拦截
│   │   │   │   ├── policy/
│   │   │   │   └── interceptor/
│   │   │   ├── session/                # 会话、记忆、持久化
│   │   │   │   ├── memory/
│   │   │   │   ├── model/
│   │   │   │   └── store/
│   │   │   ├── skill/                  # Skills 加载与渲染
│   │   │   ├── task/                   # 任务管理
│   │   │   ├── tool/                   # 工具定义与调用
│   │   │   │   ├── builtin/
│   │   │   │   └── callback/
│   │   │   ├── trace/                  # 可观测性与 Trace
│   │   │   └── transport/              # 对外通信（WebSocket/SSE/JSON-RPC）
│   │   └── resources/
│   │       └── application.yml         # Spring 配置
│   └── test/
│       └── java/com/agentcode/         # 对应模块测试
```

## 运行前提

- MySQL 8（默认 `127.0.0.1:3306/agentcode`，连接信息走 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`，本地见 `.env`）。
  启动时自动执行 `src/main/resources/schema.sql` 建 `agent_context` 表；图检查点表 `GRAPH_THREAD` / `GRAPH_CHECKPOINT` 由 `MysqlSaver` 自建。
  跑测试或起服务前先 `set -a; . ./.env; set +a`。

## 技术栈

- Java 17
- Spring Boot 3.5.x
- Spring AI Alibaba 1.1.2.0
- Spring AI 1.1.2
- Maven

## 常用命令

```bash
set -a; . ./.env; set +a   # 连库要 DB_*，见"运行前提"
mvn clean compile
mvn test
mvn spring-boot:run
```

## 长期记忆与上下文压缩

**跨会话长期记忆分三层，权限不同**：

| 层 | 位置 | 谁能改 |
|---|---|---|
| 全局记忆 | `application.yml` 的 `agentcode.memory.global`（服务核心配置） | 只有人（改配置 + 重启），agent 只读 |
| 工作区约定 | `<workspace>/Agent.md` | 人；agent 只读 |
| agent 记忆 | `<workspace>/.memory/memory.md` | agent 自由增删改（`memory_write` / `memory_forget` / `memory_search`），同一工作区的新会话开局即可见 |

开关（都支持环境变量覆盖）：`agentcode.memory.enabled`（`AGENT_MEMORY_ENABLED`，关掉则不注册记忆工具、不注入记忆块）、
`agentcode.memory.file` / `workspace-file`、`agentcode.summarization.enabled`（`AGENT_SUMMARIZATION_ENABLED`，关掉则不挂历史摘要 Hook）。

```bash
mvn test -Dtest=LongTermMemoryCrossSessionTest     # 跨会话复用 + 三层权限边界
mvn test -Dtest=ContextCompressionTokenBenchTest   # 30 组用例量上下文 token（开/关压缩对比）
```

上下文压缩量化报告：`target/context-compression/report.json`。

## 故障注入 / 崩溃恢复指标

把服务当真进程起、按场景注入故障、再量"能不能跑起来、会话还在不在"：

```bash
set -a; . ./.env; set +a
mvn test -Dtest=CrashRestartMetricsIT                                        # 6 个场景 × 3 轮，约 12 分钟
mvn test -Dtest=CrashRestartMetricsIT -Dcrash.scenarios=sigkill_multi \
    -Dcrash.rounds=5 -Dcrash.sessions=4                                      # 只跑并发场景、放大样本
mvn test -Dtest=CrashRestartMetricsIT -Dcrash.scenarios=mysql_down \
    -Dcrash.dbOutageMs=40000                                                 # 停机窗口拉过 Hikari 取连接超时
```

场景：`sigkill_model`（模型调用中被 SIGKILL）、`sigterm_model`（优雅停机对照）、`sigkill_multi`（多会话并发）、
`sigkill_approval`（审批挂起中）、`sigkill_tool`（工具执行中）、`mysql_down`（MySQL 停机窗口）。
指标落 `target/crash-recovery/metrics.json`，每一轮的现场日志在同目录的 `<场景>/round-N/` 下。

## 前端入口

### 1. 浏览器 Web UI

启动服务后访问：

```text
http://localhost:8080/
```

页面会通过 `/ws/chat` 建立 WebSocket 连接，支持：
- 创建会话
- 流式显示 Agent 事件
- 多轮对话
- 工具审批（批准 / 本会话全部批准 / 拒绝）
- stop / interrupt

### 2. 终端多轮对话客户端

Java 项目根目录提供 Node 终端客户端（Node 22+，使用内置 WebSocket，无需额外依赖）：

```bash
node scripts/terminal-chat.mjs --ws ws://localhost:8080/ws/chat --workspace /tmp --goal "列出当前目录下的文件"
```

也可以不传 `--goal`，进入交互模式：

```bash
node scripts/terminal-chat.mjs --ws ws://localhost:8080/ws/chat
```

交互模式支持：
- 输入任意文字作为多轮消息
- `stop` 停止当前任务
- `interrupt <guidance>` 引导中断
- `exit` 退出





# 参考资料

- [Spring AI Alibaba Docs](https://www.java2ai.com/docs/quick-start/)
- [Spring AI Alibaba GitHub](https://github.com/alibaba/spring-ai-alibaba)

