package com.agentcode.factory;

import com.agentcode.context.AgentContext;
import com.agentcode.properties.AgentCodeProperties;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.todolist.TodoListInterceptor;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool2;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AgentInterceptorBuilder {

    private final ChatModel chatModel;
    private final AgentCodeProperties properties;

    public Builder builder(AgentContext agentContext) {
        return new Builder(chatModel, properties, agentContext);
    }

    public static class Builder {

        private final ChatModel chatModel;
        private final AgentCodeProperties properties;
        private final String workspace;
        private final List<Interceptor> interceptors = new ArrayList<>();
        private ShellTool2 shellTool2;

        private Builder(ChatModel chatModel, AgentCodeProperties properties, AgentContext agentContext) {
            this.chatModel = chatModel;
            this.properties = properties;
            this.workspace = agentContext.getWorkspace();
        }

        public Builder withTodoList() {
            interceptors.add(
                    TodoListInterceptor.builder().build()
            );
            return this;
        }



        public List<Interceptor> build() {
            return interceptors;
        }
    }

    @AllArgsConstructor
    @Data
    public static class Result{
        private List<Hook> hooks;
        private ShellTool2 shellTool2;
    }
}