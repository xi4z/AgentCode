package com.agentcode.store;

import com.agentcode.agent.context.AgentContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * AgentContext 落 MySQL（表 agent_context，建表脚本见 resources/schema.sql）。
 *
 * <p>只存"重建会话需要的东西"：goal / workspace / 会话笔记。run 的开工时刻（agentStartMs）
 * 是一次 run 的临时账，重启后那一轮会重新计时，不落库。
 */
@Component
@RequiredArgsConstructor
public class JdbcAgentContextStore {

    private final JdbcTemplate jdbc;

    /** 单条 upsert：省掉 select→insert/update 的竞态与一次往返 */
    public void save(String runId, AgentContext context) {
        jdbc.update("""
                INSERT INTO agent_context (run_id, goal, workspace, session_note)
                VALUES (?, ?, ?, ?) AS new
                ON DUPLICATE KEY UPDATE goal = new.goal,
                                        workspace = new.workspace,
                                        session_note = new.session_note
                """,
                runId,
                context.getGoal(),
                context.getWorkspace(),
                context.getSessionNotes() == null ? null : context.getSessionNotes().toString());
    }

    public Optional<AgentContext> find(String runId) {
        return jdbc.query("""
                        SELECT goal, workspace, session_note
                        FROM agent_context
                        WHERE run_id = ?
                        """,
                (rs, rowNum) -> AgentContext.builder()
                        .runId(runId)
                        .goal(rs.getString("goal"))
                        .workspace(rs.getString("workspace"))
                        .sessionNotes(new StringBuilder(
                                rs.getString("session_note") == null ? "" : rs.getString("session_note")))
                        .build(),
                runId).stream().findFirst();
    }

    public void remove(String runId) {
        jdbc.update("DELETE FROM agent_context WHERE run_id = ?", runId);
    }
}
