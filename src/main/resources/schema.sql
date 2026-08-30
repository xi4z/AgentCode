CREATE TABLE IF NOT EXISTS agent_context (
    run_id       VARCHAR(64)  NOT NULL,
    goal         TEXT,
    workspace    VARCHAR(1024),
    session_note TEXT,
    PRIMARY KEY (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
