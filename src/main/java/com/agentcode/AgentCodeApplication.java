package com.agentcode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AgentCodeApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentCodeApplication.class, args);
    }
}