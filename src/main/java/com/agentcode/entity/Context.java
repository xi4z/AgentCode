package com.agentcode.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * Agent 上下文持久化实体。
 */
@Data
@TableName("agent_context")
public class Context {

    /**
     * 会话/运行 ID，由业务层生成 UUID，因此使用 INPUT 而不是数据库自增。
     */
    @TableId(type = IdType.INPUT)
    private String runId;

    private String goal;

    private String workspace;

    private String sessionNote;
}
