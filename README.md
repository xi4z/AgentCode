# AgentCode Java

AgentCode 的 Java 实现骨架，基于 Spring Boot + Spring AI Alibaba。

> 当前仅包含项目骨架，不包含业务代码。

## 目录结构

```text
Java/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/agentcode/   # Java 业务代码
│   │   └── resources/
│   │       └── application.yml   # Spring 配置
│   └── test/
│       └── java/com/agentcode/   # 测试代码
```

## 技术栈

- Java 17
- Spring Boot 3.4
- Spring AI Alibaba
- Maven

## 常用命令

```bash
mvn clean compile
mvn test
mvn spring-boot:run
```
