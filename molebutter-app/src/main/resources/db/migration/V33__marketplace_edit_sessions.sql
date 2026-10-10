CREATE TABLE marketplace_edit_session (
    id BIGINT NOT NULL PRIMARY KEY,
    draft_id BIGINT NOT NULL,
    draft_revision BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    account_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mapping_json LONGTEXT NULL,
    session_json LONGTEXT NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    INDEX idx_marketplace_edit_draft (draft_id),
    INDEX idx_marketplace_edit_expiry (expires_at),
    CONSTRAINT fk_marketplace_edit_draft FOREIGN KEY (draft_id) REFERENCES marketplace_draft(id)
);

ALTER TABLE marketplace_submission_preview ADD COLUMN edit_session_id BIGINT NULL;
ALTER TABLE marketplace_execution_attempt ADD COLUMN request_json LONGTEXT NULL;
