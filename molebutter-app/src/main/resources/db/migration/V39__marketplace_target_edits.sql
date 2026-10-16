ALTER TABLE marketplace_edit_session
    MODIFY COLUMN account_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN target_bindings_json LONGTEXT NULL;

-- Business draft state, separate from expiring sessions and immutable execution snapshots.
CREATE TABLE marketplace_draft_target_edit (
    draft_id BIGINT NOT NULL,
    market VARCHAR(16) NOT NULL,
    account_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    draft_revision BIGINT NOT NULL,
    snapshot_version INT NOT NULL DEFAULT 1,
    mapping_json LONGTEXT NOT NULL,
    intent_json LONGTEXT NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (draft_id,market,account_key),
    CONSTRAINT fk_marketplace_target_edit_draft FOREIGN KEY (draft_id) REFERENCES marketplace_draft(id) ON DELETE CASCADE
);
