-- Proven content associations; no source files or execution snapshots are rewritten.
CREATE TABLE marketplace_media_comparison (
    draft_id BIGINT NOT NULL,
    market VARCHAR(16) NOT NULL,
    account_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_version BIGINT NOT NULL,
    external_product_id VARCHAR(30) NOT NULL,
    option_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    field_path VARCHAR(80) NOT NULL,
    evidence_json LONGTEXT NOT NULL,
    execution_id BIGINT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY(draft_id,market,account_key,listing_version,option_id,field_path),
    CONSTRAINT fk_media_comparison_draft FOREIGN KEY(draft_id) REFERENCES marketplace_draft(id) ON DELETE CASCADE,
    CONSTRAINT fk_media_comparison_execution FOREIGN KEY(execution_id) REFERENCES marketplace_execution(id)
);
