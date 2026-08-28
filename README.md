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

