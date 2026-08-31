package com.agentcode.audit;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * 自动将上下文中的真实 AI Provider ChatModel 包装为 {@link AuditedChatModel}。
 *
 * 不包装测试中的 mock ChatModel，避免影响测试中对 ChatModel 具体类型的断言。
 * 可通过 agentcode.audit.enabled=false 关闭审计。
 */
@Component
public class ChatModelAuditBeanPostProcessor implements BeanPostProcessor {

    private final boolean enabled;
    private final ObjectProvider<AuditLogRepository> auditLogRepositoryProvider;

    public ChatModelAuditBeanPostProcessor(@Value("${agentcode.audit.enabled:true}") boolean enabled,
                                           ObjectProvider<AuditLogRepository> auditLogRepositoryProvider) {
        this.enabled = enabled;
        this.auditLogRepositoryProvider = auditLogRepositoryProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (enabled
                && bean instanceof ChatModel
                && !(bean instanceof AuditedChatModel)
                && shouldAudit(bean)) {
            return new AuditedChatModel((ChatModel) bean, enabled, auditLogRepositoryProvider.getIfAvailable());
        }
        return bean;
    }

    /**
     * 仅包装真实 AI Provider 的 ChatModel。
     */
    private boolean shouldAudit(Object bean) {
        String className = bean.getClass().getName();
        return className.startsWith("org.springframework.ai.openai")
                || className.startsWith("com.alibaba.cloud.ai.dashscope")
                || className.startsWith("com.alibaba.cloud.ai.anthropic")
                || className.startsWith("org.springframework.ai.ollama");
    }
}
