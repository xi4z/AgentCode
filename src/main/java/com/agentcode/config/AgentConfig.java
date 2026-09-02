package com.agentcode.config;

import org.springframework.context.annotation.Configuration;

/**
 * Agent 相关装配。
 *
 * <p>原先的 chatClient bean 已删除：项目内无任何引用，属于死代码；
 * Agent 由 AgentSessionFactory 按会话装配，不依赖全局 ChatClient。
 * 后续如需按需配置 ChatClient，再在此处补充。
 */
@Configuration
public class AgentConfig {
}
