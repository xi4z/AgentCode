# AgentCode Java

基于 **AgentScope Java 2.1.0-BETA1** 的编码 Agent 运行时。项目保留了 WebSocket 前端、shell 审批策略与工作区约定，把会话、事件、权限、工具执行和状态持久化交给 AgentScope。

## 运行环境

- JDK 17 及以上
- Spring Boot 4.0.9
- Maven 3.6.3 及以上
- AgentScope Java 2.1.0-BETA1（固定版本）

2.0.4 是当前稳定版，但没有 `AgentSession`、`SessionLog`、持久 inbox 与游标重放；这些能力在 2.1.0-BETA1 中提供。项目固定使用 2.1.0-BETA1，升级前需要重新核对 API。

## 结构

- `agent/`
  - `PermissionPolicy`：只读工具放行，写入与 shell 默认询问。
  - `ShellPolicy`：安全命令、危险命令、越界路径判定。
  - `ShellTool`：工作区 shell 工具，参与权限引擎。
  - `WorkspacePrompt`：项目说明文件注入系统提示词。
  - `WorkspaceRuntime`：一个工作区一个 `HarnessAgent`。
  - `WorkspaceRuntimeRegistry`：按工作区缓存与释放运行时。
- `config/`
  - `JacksonConfig`：Jackson 2 的 `ObjectMapper`。
  - `ModelConfig`：OpenAI 兼容端点与 DashScope。
- `dto/AgentStream`：推送给前端的帧。
- `session/`
  - `AgentChatService`：提交任务、审批恢复、事件轮询。
  - `PendingPermission`：待审批工具调用。
  - `SessionEventProjector`：持久事件转换前端帧。
  - `SessionMetadata`、`SessionMetadataStore`：`runId` 与工作区绑定，跨进程存活。
  - `SessionNotFoundException`。
- `websocket/`：`ChatProtocol`、`ChatWebSocketHandler`、`HomeRouter`、`WebSocketConfig`。
- `common/ShellParseHelper`：命令拆分与通配符。
- `properties/AgentCodeProperties`：配置绑定。
- `AgentCodeApplication`：启动入口。

## 会话模型

一次 `start_session` 创建 `runId`，并把它绑定到请求里的工作区。服务端用 `HarnessAgent.session(RuntimeContext(userId=local, sessionId=runId))` 取得 `AgentSession`：

- `submit(requestId, text)` 持久接收任务，`requestId` 用作幂等键。
- `respond(requestId, SessionAnswer)` 恢复挂起的审批或外部执行。
- `interrupt()` / `steer(guidance)` 停止或引导当前运行。
- `SessionLog.readAfter(seq, limit)` 读取已提交事件；前端断开不会停止执行。
- `session.start()` 在进程重启后继续派发已接收的任务。

工作区、会话日志和状态存储的生命周期由 `WorkspaceRuntime` 持有。会话日志默认写入工作区的 `.agentscope-runtime/`，会话元数据写入 `${agentcode.session.runtime-dir}/sessions`，Agent 状态写入 `${agentcode.session.runtime-dir}/state`。

## 权限

权限上下文由 `PermissionPolicy` 构造：

- `read_file`、`grep_files`、`glob_files`、`list_files`、`memory_search`、`memory_get`、`session_search`、`todo_write` 直接放行。
- `write_file`、`edit_file` 命中默认 ASK。
- `shell` 先由 `ShellPolicy.autoApprovable` 判断；安全命令直接放行，其余落到默认 ASK。

审批决定：

- `APPROVED`：只放行本次调用。
- `APPROVE_ALL`：把当前命令写入该会话的 `PermissionContextState` 允许规则，后续相同命令不再询问。规则通过 `ReActAgent.replacePermissionContext` 持久化到 AgentState。
- `EDITED`：用前端提交的新参数替换原调用参数后执行。
- `REJECTED`：写入拒绝结果，模型继续下一轮推理。

## 配置

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `AGENTCODE_MODEL_PROVIDER` | `openai` | `openai` 或 `dashscope` |
| `AGENTCODE_MODEL_API_KEY` | 空 | 优先于 provider 专用密钥 |
| `OPENAI_API_KEY` / `DASHSCOPE_API_KEY` | 空 | provider 密钥 |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | OpenAI 兼容端点 |
| `DASHSCOPE_BASE_URL` | `https://dashscope.aliyuncs.com/api/v1` | DashScope 端点 |
| `AGENTCODE_MODEL_NAME` | `gpt-4o-mini` | 模型名 |
| `AGENTCODE_MODEL_TEMPERATURE` | `0.7` | 温度 |
| `AGENTCODE_MODEL_MAX_TOKENS` | `8192` | 最大输出 |
| `AGENT_MAX_ITERATIONS` | `20` | 单回合推理上限 |
| `AGENT_MEMORY_ENABLED` | `false` | 是否启用 Harness 长期记忆与后台整理 |
| `AGENTCODE_RUNTIME_DIR` | `~/.agentcode` | 会话元数据与状态根目录 |
| `AGENTCODE_POLL_INTERVAL_MILLIS` | `150` | 事件轮询间隔 |

## 常用命令

```bash
mvn clean test
mvn spring-boot:run
```

启动后访问 `http://localhost:8080/`。页面通过 `/ws/chat` 建立连接，支持创建会话、多轮对话、工具审批、停止、打断，以及断线后的 `attach` 续读。

## WebSocket 协议

客户端消息：

| type | 字段 | 作用 |
|---|---|---|
| `start_session` | `goal`、`workspace`、`requestId` | 创建会话并提交首个任务 |
| `chat` | `runId`、`content`、`requestId` | 追加多轮消息 |
| `attach` | `runId`、`afterSeq` | 从指定事件序号续读，不重新执行 |
| `permission_respond` | `runId`、`toolCallId`、`decision`、`arguments`、`feedback` | 回答审批 |
| `stop` | `runId` | 停止当前运行 |
| `interrupt` | `runId`、`guidance` | 引导并打断 |
| `ping` | `requestId` | 心跳 |

服务端消息：

| type | 关键字段 | 作用 |
|---|---|---|
| `session_started` | `runId` | 会话创建完成 |
| `agent_event` | `status`、`content`、`seq` | 流式事件，`seq` 用于重连 |
| `permission_requested` | `toolCallId`、`toolName`、`arguments`、`description` | 待审批工具调用 |
| `done` | `runId` | 当前回合结束 |
| `stopped` / `interrupted` / `error` / `pong` | 对应字段 | 控制结果 |

`agent_event.status` 取值：`TURN_STARTED`、`THINKING_STREAMING`、`THINKING_FINISHED`、`RESPONSE_STREAMING`、`RESPONSE_FINISHED`、`TOOL_STREAMING`、`TOOL_FINISHED`、`PERMISSION_REQUESTED`、`TURN_FINISHED`、`ERROR`。

## 已覆盖的测试

- `ChatWebSocketMultiTurnIntegrationTest`：同一 `runId` 的多轮上下文。
- `ChatWebSocketToolUseIntegrationTest`：`read_file` 工具调用与事件。
- `ChatWebSocketPermissionApprovalIntegrationTest`：shell 审批后恢复执行。
- `ChatWebSocketPermissionDecisionIntegrationTest`：`EDITED` 与 `REJECTED` 分支。
- `ChatWebSocketApproveAllIntegrationTest`：`APPROVE_ALL` 规则跨回合生效。
- `ChatWebSocketReconnectIntegrationTest`：断开后用 `attach` 重放持久事件。

测试使用确定性的 `ScriptedModel`，不访问真实模型服务。

## 当前边界

- 执行环境为宿主本地工作区；Kubernetes 沙箱尚未接入。AgentScope 的 `KubernetesFilesystemSpec` 可以在后续替换 `LocalFilesystemSpec`。
- 长期记忆默认关闭，避免离线测试与后台整理调用模型。
- 未接入 Trace/OpenTelemetry。
- AgentScope 2.1.0-BETA1 仍是预发布版本，升级时先跑完整测试。

## 参考资料

- [AgentScope Java](https://github.com/agentscope-ai/agentscope-java)
- [AgentScope Java 文档](https://java.agentscope.io/)
