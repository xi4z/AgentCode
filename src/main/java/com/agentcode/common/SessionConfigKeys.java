package com.agentcode.common;

/**
 * RunnableConfig.context() 中使用的键名。
 *
 * <p>这些键在 {@code AgentSessionFactory}、{@code AgentSession}、{@code AgentApprovalManager}
 * 之间共享，必须集中定义，避免字符串拼写不一致导致状态残留
 * （历史上 {@code __PENDING_INTERRUPTED__} 就因 remove 时少写尾部下划线而从未被清理）。
 */
public final class SessionConfigKeys {

    /** 注入到 toolContext / config context 中的当前会话上下文 */
    public static final String AGENT_CONTEXT = "__AGENT_CONTEXT__";

    /** 已经自动放行、等待与人工审批结果合并的 InterruptionMetadata.Builder */
    public static final String HANDLED_INTERRUPTION = "__HANDLED_INTERRUPTION__";

    /** 本轮还需要人工审批的工具反馈：toolCallId -> ToolFeedback */
    public static final String PENDING_INTERRUPTIONS = "__PENDING_INTERRUPTED__";

    /** 用户已提交但尚未凑齐本轮审批的处理结果：toolCallId -> AgentInterruptHandle */
    public static final String PENDING_RESPONSES = "__PENDING_RESPONSES__";

    private SessionConfigKeys() {
    }
}
