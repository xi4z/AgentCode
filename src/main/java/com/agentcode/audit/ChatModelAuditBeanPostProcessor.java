package com.agentcode.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 自动将上下文中的真实 AI Provider ChatModel 包装为 {@link AuditedChatModel}。
 *
 * <p>类型判断基于 AOP 目标类（{@link AopProxyUtils#ultimateTargetClass}），与代理解耦：
 * 即使 ChatModel 被 AOP/CGLIB 包装，仍能按原始实现类识别并继续包装审计层。
 *
 * <p>不包装测试中的 mock ChatModel，避免影响测试中对 ChatModel 具体类型的断言。
 * 可通过 agentcode.audit.enabled=false 关闭审计。
 */
@Slf4j
@Component
public class ChatModelAuditBeanPostProcessor implements BeanPostProcessor {

    /**
     * 已知真实 AI Provider 的 ChatModel 实现类（简单类名集合）。
     * 按具体类型白名单判断，而不是包名前缀，避免包调整/代理场景下静默失效。
     */
    private static final Set<String> AUDITED_CHAT_MODEL_SIMPLE_NAMES = Set.of(
            "OpenAiChatModel",             // spring-ai openai
            "DashScopeChatModel",          // spring-ai-alibaba dashscope
            "AnthropicChatModel",          // spring-ai-alibaba anthropic
            "OllamaChatModel"              // spring-ai ollama
    );

    private final boolean enabled;
    private final ObjectProvider<AuditLogRepository> auditLogRepositoryProvider;

    public ChatModelAuditBeanPostProcessor(@Value("${agentcode.audit.enabled:true}") boolean enabled,
                                           ObjectProvider<AuditLogRepository> auditLogRepositoryProvider) {
        this.enabled = enabled;
        this.auditLogRepositoryProvider = auditLogRepositoryProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof ChatModel) || bean instanceof AuditedChatModel) {
            return bean;
        }
        if (!enabled) {
            return bean;
        }
        if (shouldAudit(bean)) {
            // 已被代理的 ChatModel 同样包装：审计层包在代理之外，记录的是最终调用
            log.info("AUDIT_CHAT_MODEL_WRAPPED bean={} type={}", beanName, bean.getClass().getName());
            return new AuditedChatModel((ChatModel) bean, enabled, auditLogRepositoryProvider.getIfAvailable());
        }
        log.warn("AUDIT_CHAT_MODEL_SKIPPED bean={} type={} reason=非已知 AI Provider 实现（mock/自定义实现不包装审计）",
                beanName, bean.getClass().getName());
        return bean;
    }

    /**
     * 仅包装真实 AI Provider 的 ChatModel。
     * 使用 AOP 目标类判断，避免代理类名（如 $$SpringCGLIB$$ / JDK 代理）导致匹配失败。
     */
    private boolean shouldAudit(Object bean) {
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
        String simpleName = targetClass.getSimpleName();
        return AUDITED_CHAT_MODEL_SIMPLE_NAMES.contains(simpleName);
    }
}
