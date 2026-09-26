-- 会话上下文持久化。列与既有库（Java 分支建的 agent_context）保持一致：
-- Spring SQL init 对已存在的表不会补列，改列要单独 ALTER。
CREATE TABLE IF NOT EXISTS agent_context (
    run_id       VARCHAR(64)  NOT NULL,
    goal         TEXT,
    workspace    VARCHAR(1024),
    session_note TEXT,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 图检查点的 GRAPH_THREAD / GRAPH_CHECKPOINT 两张表由 MysqlSaver 自己建（见 SaverConfig），不在这里重复。
