package com.agentcode.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 调用审计流水，持久化到 MySQL。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String type;

    /**
     * 关联的会话 runId（取自 MDC "runId"，可能为 null）。
     */
    private String runId;

    private String model;

    private Long durationMs;

    private Integer promptMessages;

    private Integer chunks;

    private Integer responseLength;

    private Integer toolCalls;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private String error;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}