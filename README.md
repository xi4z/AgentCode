# AgentCode Java

基于 Spring Boot + Spring AI Alibaba 的本地 Agent 运行时：ReAct 循环、工具调用与审批、
流式事件（WebSocket）、会话记忆与上下文注入。

## 配置

`application.yml` 的 `agentcode.*` 段（均可用环境变量覆盖，见文件内注释）：

| 键 | 说明 |
| --- | --- |
| `agent.max-steps` | 单次 run 的模型调用上限（`ModelCallLimitHook`） |
| `agent.system-prompt` | 基础系统提示词 |
| `agent.global-context-file` | 用户级上下文文件，支持 `~` 前缀，缺失时忽略 |
| `agent.project-context-file` | 项目上下文文件，优先于 `.kama/context.md`、`AGENT.md`、`CLAUDE.md`、`SOUL.md` |
| `agent.approval-tools` | 调用前需要人工审批的工具，默认 `shell,write_file,edit_file` |
| `agent.approval.allow-patterns` | shell 通配符白名单（整条子命令匹配），如 `git status*` |
| `agent.approval.deny-patterns` | shell 通配符黑名单，优先级高于白名单与会话级放行 |
| `agent.approval.safe-commands` / `dangerous-commands` / `outside-cwd-patterns` | 显式配置则整体替换内置名单 |
| `agent.session.idle-timeout` | 空闲会话回收阈值（连同 AgentContext 一起清理） |
| `agent.session.evict-interval` | 回收/超时巡检间隔 |
| `agent.session.approval-wait-timeout` | 等待人工审批超时，超时后放弃本轮审批并把会话置回空闲 |
| `audit.enabled` | 是否包装 ChatModel 输出 AI 调用审计日志 |

### 命令审批判定顺序

`deny-patterns` / 危险命令 → 命中即人工；
越界（绝对路径、`~`、`..`、`$HOME`、显式 `cd`）→ 强制人工，白名单不可绕过；
`allow-patterns` → 自动放行；
`safe-commands` → 自动放行；
其余默认人工。

用户在审批时选择 `APPROVE_ALL` 只缓存**精确命令**，且不会覆盖黑名单与越界检查；
需要长期放行一类命令请写进 `agent.approval.allow-patterns`。

## 目录结构

```text
Java/
├── pom.xml
├── scripts/terminal-chat.mjs        # 终端多轮对话客户端（Node 22+）
└── src/
    ├── main/
    │   ├── java/com/agentcode/
    │   │   ├── AgentCodeApplication.java
    │   │   ├── audit/                 # ChatModel 审计包装（AUDIT_AI_CALL / AUDIT_AI_STREAM）
    │   │   ├── common/                # ShellParseHelper、SessionConfigKeys、ContextInjector
    │   │   ├── config/                # ChatClient、CheckpointSaver 装配
    │   │   ├── context/               # AgentContext：系统提示词 + 全局/项目上下文 + 会话笔记
    │   │   ├── dto/                   # AgentStream、AgentApprovalManager、ApprovalPolicy、审批/中断 DTO
    │   │   ├── exception/             # 会话与审批相关异常
    │   │   ├── factory/               # AgentSessionFactory、SessionBuildOptions（装配 Hook/Tools）
    │   │   ├── properties/            # AgentCodeProperties（agentcode.* 配置绑定）
    │   │   ├── registry/              # AgentSessionRegistry：活跃会话表 + 空闲回收
    │   │   ├── service/               # ReactAgentService 与 AgentSessionMaintenance（定时清理）
    │   │   ├── session/               # AgentSession：run/stop/interrupt/审批恢复状态机
    │   │   ├── store/                 # InMemoryAgentContextStore（待替换为持久化实现）
    │   │   ├── tools/                 # SessionNoteTools 等本地工具
    │   │   ├── utils/                 # SpringContextUtil
    │   │   └── websocket/             # /ws/chat 协议与处理器、静态页面路由
    │   └── resources/
    │       ├── application.yml
    │       └── static/index.html      # 浏览器 Web UI
    └── test/java/com/agentcode/       # 对应模块测试
```

## 技术栈

- Java 17
- Spring Boot 3.5.x
- Spring AI Alibaba 1.1.2.0
- Spring AI 1.1.2
- Maven

## 常用命令

```bash
mvn clean compile
mvn test
mvn spring-boot:run
```

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
- 工具审批（批准 / 本会话全部批准 / 拒绝 / 修改参数）
- stop / interrupt

#### 审批协议要点

一轮中断可能同时挂起多个工具（例如两个 `shell` 调用），服务端会缓存决定，
**等本轮待审批项全部答复后才恢复执行**：

```jsonc
// 服务端 -> 客户端：逐个列出待审批工具
{"type": "permission_requested", "runId": "...", "toolCallId": "call_a", "toolName": "shell", "arguments": "{\"command\":\"cat /etc/hosts\"}"}

// 客户端 -> 服务端：单个答复（兼容旧客户端）
{"type": "permission_respond", "runId": "...", "toolCallId": "call_a", "decision": "APPROVED"}

// 客户端 -> 服务端：批量答复（推荐，一次提交本轮全部决定）
{"type": "permission_respond", "runId": "...", "handles": [
  {"toolCallId": "call_a", "decision": "APPROVED"},
  {"toolCallId": "call_b", "decision": "REJECTED", "feedback": "不要删文件"}
]}

// 服务端 -> 客户端：决定已记录，但本轮仍有未答复项（此时不会发 done）
{"type": "permission_pending", "runId": "...", "content": "[\"call_b\"]"}
```

`decision` 可选 `APPROVED` / `APPROVE_ALL`（同一会话内该命令不再询问）/ `REJECTED` / `EDITED`（配 `arguments` 回传新参数）。
等待审批期间 `stop` 会放弃本轮全部待审批工具调用并把会话置回空闲。

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

