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
    -- 关联会话 ID（MDC "runId"），可能为空
    run_id            VARCHAR(64)  NULL,
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
    KEY idx_audit_type_model (type, model),
    KEY idx_audit_run_id (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 存量库迁移说明：
-- Spring SQL init 对已存在的表不会补新列（CREATE TABLE IF NOT EXISTS 直接跳过），
-- 且 MySQL 8 不支持 ALTER TABLE ... ADD COLUMN IF NOT EXISTS，因此老库需要手动执行：
--   ALTER TABLE agentcode.audit_log ADD COLUMN run_id VARCHAR(64) NULL, ADD KEY idx_audit_run_id (run_id);
-- 本地/测试库可直接重建（DROP 后由本脚本重建）。
