package com.agentcode.entity;

import com.agentcode.vo.ContextVo;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

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

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;


    public ContextVo toVo(){
        ContextVo vo = new ContextVo();
        vo.setRunId(runId);
        vo.setGoal(goal);
        vo.setWorkspace(workspace);
        vo.setCreateAt(createdAt);
        vo.setUpdateAt(updatedAt);
        return vo;
    }

}
