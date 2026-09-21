package com.agentcode.session;

import java.util.HashMap;
import java.util.Map;

public enum SessionEnum {
    HANDLES_INTERRUPTED("__HANDLES_INTERRUPTED__", "已处理的审批"),
    AGENT_CONTEXT("__AGENT_CONTEXT__", "已处理的审批"),
    PENDING_INTERRUPTED("__PENDING_INTERRUPTED__", "待处理的审批");

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
