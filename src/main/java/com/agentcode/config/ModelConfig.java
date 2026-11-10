package com.agentcode.config;

import com.agentcode.properties.AgentCodeProperties;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 根据配置创建 AgentScope 的 Model；provider 决定使用哪套协议实现。 */
@Configuration(proxyBeanMethods = false)
public class ModelConfig {

    @Bean
    public Model agentModel(AgentCodeProperties properties) {
        AgentCodeProperties.Model model = properties.getModel();
        // 没有配置密钥时给占位值，保证应用能启动；首次真实调用才会在 provider 侧失败。
        String apiKey = blankToPlaceholder(model.getApiKey());
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(model.getTemperature())
                        .maxTokens(model.getMaxTokens())
                        .build();

        return switch (model.getProvider().toLowerCase()) {
            // OpenAI 兼容端点通过 baseUrl 指向网关或第三方服务。
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
