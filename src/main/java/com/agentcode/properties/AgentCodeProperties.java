package com.agentcode.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent 运行参数；对应 application.yml 中的 {@code agentcode.*}。 */
@ConfigurationProperties(prefix = "agentcode")
public class AgentCodeProperties {

    private final Agent agent = new Agent();
    private final Model model = new Model();
    private final Session session = new Session();

    public Agent getAgent() {
        return agent;
    }

    public Model getModel() {
        return model;
    }

    public Session getSession() {
        return session;
    }

    /** Agent 身份、系统提示词与回合上限。 */
    public static class Agent {
        private String name = "AgentCode";
        private String agentId = "agentcode";
        private String systemPrompt =
                "You are a coding agent. Use the available tools to complete the user's goal. "
                        + "When the goal is fully achieved, answer without calling more tools.";
        private int maxIterations = 20;
        private boolean memoryEnabled = false;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAgentId() {
            return agentId;
        }

        public void setAgentId(String agentId) {
            this.agentId = agentId;
        }

        public String getSystemPrompt() {
            return systemPrompt;
        }

        public void setSystemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
        }

        public int getMaxIterations() {
            return maxIterations;
        }

        public void setMaxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
        }

        public boolean isMemoryEnabled() {
            return memoryEnabled;
        }

        public void setMemoryEnabled(boolean memoryEnabled) {
            this.memoryEnabled = memoryEnabled;
        }
    }

    /** 模型 provider 与生成参数。 */
    public static class Model {
        private String provider = "openai";
        private String apiKey = "";
        private String baseUrl = "https://api.openai.com/v1";
        private String name = "gpt-4o-mini";
        private double temperature = 0.7;
        private int maxTokens = 8192;
        private String dashscopeBaseUrl = "https://dashscope.aliyuncs.com/api/v1";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public String getDashscopeBaseUrl() {
            return dashscopeBaseUrl;
        }

        public void setDashscopeBaseUrl(String dashscopeBaseUrl) {
            this.dashscopeBaseUrl = dashscopeBaseUrl;
        }
    }

    /** 会话元数据、状态存储与事件轮询参数。 */
    public static class Session {
        private String runtimeDir = System.getProperty("user.home") + "/.agentcode";
        private long pollIntervalMillis = 150;

        public String getRuntimeDir() {
            return runtimeDir;
        }

        public void setRuntimeDir(String runtimeDir) {
            this.runtimeDir = runtimeDir;
        }

        public long getPollIntervalMillis() {
            return pollIntervalMillis;
        }

        public void setPollIntervalMillis(long pollIntervalMillis) {
            this.pollIntervalMillis = pollIntervalMillis;
        }
    }
}
