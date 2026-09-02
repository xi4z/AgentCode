package com.agentcode.mapper;

import com.agentcode.entity.Context;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ContextMapper extends BaseMapper<Context> {

    /**
     * 原子 upsert：run_id 冲突时更新，替代 save 里的 check-then-act
     * （select→insert/update 非原子，并发下会产生重复插入或丢失更新）。
     *
     * <p>created_at / updated_at 由表默认值与 ON UPDATE 维护，无需显式赋值。
     */
    @Insert("INSERT INTO agent_context (run_id, goal, workspace, session_note) " +
            "VALUES (#{runId}, #{goal}, #{workspace}, #{sessionNote}) " +
            "ON DUPLICATE KEY UPDATE " +
            "goal = VALUES(goal), " +
            "workspace = VALUES(workspace), " +
            "session_note = VALUES(session_note)")
    int upsert(Context context);
}
