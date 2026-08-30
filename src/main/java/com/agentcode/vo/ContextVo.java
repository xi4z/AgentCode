package com.agentcode.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ContextVo {
    String runId;
    String goal;
    String workspace;

    LocalDateTime createAt;
    LocalDateTime updateAt;
}
