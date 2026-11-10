package com.agentcode.config;

import com.agentcode.properties.AgentCodeProperties;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ModelConfig {

    @Bean
    public Model agentModel(AgentCodeProperties properties) {
        AgentCodeProperties.Model model = properties.getModel();
        String apiKey = blankToPlaceholder(model.getApiKey());
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(model.getTemperature())
                        .maxTokens(model.getMaxTokens())
                        .build();

        return switch (model.getProvider().toLowerCase()) {
            case "openai" ->
                    OpenAIChatModel.builder()
                            .apiKey(apiKey)
                            .modelName(model.getName())
                            .baseUrl(model.getBaseUrl())
                            .stream(true)
                            .generateOptions(options)
                            .build();
            case "dashscope" ->
                    DashScopeChatModel.builder()
                            .apiKey(apiKey)
                            .modelName(model.getName())
                            .baseUrl(model.getDashscopeBaseUrl())
                            .stream(true)
                            .defaultOptions(options)
                            .build();
            default ->
                    throw new IllegalArgumentException(
                            "Unsupported agentcode.model.provider: " + model.getProvider());
        };
    }

    private static String blankToPlaceholder(String value) {
        return value == null || value.isBlank() ? "not-configured" : value;
    }
}
