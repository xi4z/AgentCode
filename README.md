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
