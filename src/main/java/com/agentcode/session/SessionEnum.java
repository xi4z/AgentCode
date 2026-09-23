package com.agentcode.session;

import java.util.HashMap;
import java.util.Map;

public enum SessionEnum {
    HANDLED_INTERRUPTED("__HANDLED_INTERRUPTED__", "已处理的审批"),

    AGENT_CONTEXT("__AGENT_CONTEXT__", "已处理的审批"),

    PENDING_INTERRUPTED("__PENDING_INTERRUPTED__", "待处理的审批"),

    /*
     * 以下五个都是 {@code RunnableConfig.context()} 上的键，跟着一次 run 走。
     * 由 AgentPerformanceHook.beforeAgent 清零，审批恢复时会话层负责把累计量种进新 config，
     * 使"总量"跨 run 存活（见 AgentSession.handleToolApproval）。run 的起始时刻不放这里，
     * 放 AgentContext#agentStartMs。
     */

    /** 单次模型调用的开始时刻 */
    SINGLE_TIME("__SINGLE_TIME__", "单次模型调用的开始时刻"),

    /** 本轮 run 的开始时刻 */
    AGENT_START("__AGENT_START__", "本轮 run 的开工时刻"),

    /**
     * 本轮 run 对 Model 的总调用次数
     */
    TOTAL_COUNT("__TOTAL_COUNT__", "本轮 run 对 Model 的总调用次数"),

    /**
     * 本轮 run 的 Token 使用量
     */
    TOTAL_USAGE("__TOTAL_USAGE__", "本轮 run 的 Token 使用量"), // TODO 以后会改成存一个usage类

    TOTAL_DURATION("__TOTAL_DURATION__", "本轮 run 的模型调用总耗时");



    private final String code;
    private final String desc;

    SessionEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    private static final Map<String, SessionEnum> CODE_MAP = new HashMap<>();

    static {
        for (SessionEnum status : values()) {
            SessionEnum old = CODE_MAP.put(status.code, status);
            if (old != null) {
                throw new IllegalStateException("重复的状态码: " + status.code);
            }
        }
    }

    public static SessionEnum of(int code) {
        SessionEnum status = CODE_MAP.get(code);
        if (status == null) {
            throw new IllegalArgumentException("未知订单状态: " + code);
        }
        return status;
    }
}
