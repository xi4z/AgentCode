package com.agentcode.session;

import com.agentcode.agent.AgentSession;
import com.agentcode.context.AgentContext;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用于创建带运行期 AgentContext 的 AgentSession 原型 Bean。
 *
 * AgentContext 不是 Spring Bean，无法由 Spring 自动注入，
 * 因此通过工厂在创建时传入；其他依赖（ChatModel、BaseCheckpointSaver 等）
 * 由 Spring 从容器中解析并传给 AgentSession。
 */
@Component
public class AgentSessionFactory {

    private final ObjectProvider<AgentSession> sessionProvider;
    private final ChatModel chatModel;
    private final BaseCheckpointSaver saver;

    public AgentSessionFactory(ObjectProvider<AgentSession> sessionProvider,
                               ChatModel chatModel,
                               BaseCheckpointSaver saver) {
        this.sessionProvider = sessionProvider;
        this.chatModel = chatModel;
        this.saver = saver;
    }

    /**
     * 使用默认审批工具集合（shell、write_file）创建会话。
     */
    public AgentSession create(AgentContext context) {
        return create(context, List.of("shell", "write_file"));
    }

    /**
     * 创建 AgentSession 原型 Bean。
     * AgentContext 是运行期参数，在这里显式传入；
     * chatModel、saver 等 Spring Bean 会由容器注入到构造方法中。
     */
    public AgentSession create(AgentContext context, List<String> approvalTools) {
        return sessionProvider.getObject(
                context,
                chatModel,
                saver,
                approvalTools == null ? List.of() : List.copyOf(approvalTools)
        );
    }
}
