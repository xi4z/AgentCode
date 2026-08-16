package com.agentcode.common.model;


public enum StopReason {
    END_TURN, // 回合结束
    TOOL_USE, // 工具使用: 还没结束
    MAX_TOKENS // 最大 Token了
}
