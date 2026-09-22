package com.agentcode.agent;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AgentTrace {

    public static void toolExecution(String runId, String toolName, String toolArgs){
        log.info("RunId {} | ToolExecution | name: {} ; args: {}", runId, toolName, toolArgs);
    }

    public static void toolSuccess(String runId, String toolName, String toolArgs, String toolResult, String time){
        log.info("RunId {} | ToolSuccess | name: {} ; args: {}; res: {}; time: {}", runId, toolName, toolArgs, toolResult, time);
    }

    public static void toolFailure(String runId, String toolName, String toolArgs, String exception, String time){
        log.error("RunId {} | ToolSuccess | name: {} ; args: {}; exception: {}; time: {}", runId, toolName, toolArgs, exception, time);
    }

    public static void modelCallStart(String runId){}

    public static void modelCallEnd(String runId, int count, long duration){}


}
