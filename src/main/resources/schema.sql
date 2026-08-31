CREATE TABLE IF NOT EXISTS agent_context (
    run_id       VARCHAR(64)  NOT NULL,
    goal         TEXT,
    workspace    VARCHAR(1024),
    session_note TEXT,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS audit_log (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    type              VARCHAR(32)  NOT NULL,
    model             VARCHAR(128),
    duration_ms       BIGINT,
    prompt_messages   INT,
    chunks            INT,
    response_length   INT,
    tool_calls        INT,
    prompt_tokens     INT,
    completion_tokens INT,
    total_tokens      INT,
    error             TEXT,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_audit_created_at (created_at),
    KEY idx_audit_type_model (type, model)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
