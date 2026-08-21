package com.agentcode.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentcode")
@Data
public class AgentCodeProperties {


    String systemPrompt;
    int maxStep;


}
