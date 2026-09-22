package com.agentcode.session;

import java.util.HashMap;
import java.util.Map;

public enum SessionEnum {
    HANDLED_INTERRUPTED("__HANDLED_INTERRUPTED__", "已处理的审批"),

    AGENT_CONTEXT("__AGENT_CONTEXT__", "已处理的审批"),

    PENDING_INTERRUPTED("__PENDING_INTERRUPTED__", "待处理的审批"),

    TOTAL_COUNT("__TOTAL_COUNT__", "本轮会话对Model的总调用次数"),

    SINGLE_TIME("__SINGLE_TIME__", "一次调用的时间"),



    TOTAL_USAGE("__TOTAL_USAGE__", "本轮会话的Token使用量"), // TODO 以后会改成存一个usage类

    TOTAL_DURATION("__TOTAL_DURATION__", "本轮会话的总耗时");



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
