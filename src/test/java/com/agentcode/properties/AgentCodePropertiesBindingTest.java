package com.agentcode.properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归测试：application.yml 中 agentcode.agent.* 必须能真正绑定到 AgentCodeProperties。
 *
 * 曾经 prefix 是 "agentcode" 而字段是扁平的 systemPrompt/maxStep，
 * 与 yml 的 agentcode.agent.system-prompt / max-steps 对不上，
 * 导致系统提示词与步数配置静默失效。
 */
class AgentCodePropertiesBindingTest {

    @Test
    void shouldBindAgentSectionFromApplicationYml() throws Exception {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        MutablePropertySources propertySources = new MutablePropertySources();
        for (PropertySource<?> source : loaded) {
            propertySources.addLast(source);
        }

        Binder binder = new Binder(
                ConfigurationPropertySources.from(propertySources),
                new PropertySourcesPlaceholdersResolver(propertySources));

        AgentCodeProperties properties = binder
                .bind("agentcode", AgentCodeProperties.class)
                .orElseGet(AgentCodeProperties::new);

        assertThat(properties.getAgent()).isNotNull();
        assertThat(properties.getAgent().getSystemPrompt())
                .as("system-prompt 必须绑定成功，否则系统提示词会被静默丢弃")
                .isNotBlank()
                .contains("helpful AI assistant");
        assertThat(properties.getAgent().getMaxSteps())
                .as("max-steps 必须绑定成功，否则模型步数限制无法配置")
                .isEqualTo(20);
        assertThat(properties.getAgent().getGlobalContextFile()).isEqualTo("~/.agent/context.md");
        assertThat(properties.getAgent().getProjectContextFile()).isEqualTo(".agent/context.md");
        assertThat(properties.getAudit().isEnabled()).isTrue();
    }
}
