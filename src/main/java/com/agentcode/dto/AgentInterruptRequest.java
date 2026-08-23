package com.agentcode.dto;


/**
 * 由 Agent 引起的中断的请求类
 */
public record AgentInterruptRequest(
    String toolId, // 工具Id
    String toolName, // 工具名
    String description, // 工具的描述
    String content, // 具体的内容
    int toolNum
){

}
