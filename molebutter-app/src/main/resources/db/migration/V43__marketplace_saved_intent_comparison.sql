-- Saved intention is independent of mutable observations and immutable execution snapshots.
CREATE TABLE marketplace_target_saved (
    draft_id BIGINT NOT NULL,
    market VARCHAR(16) NOT NULL,
    account_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_version BIGINT NOT NULL,
    mapping_json LONGTEXT NULL,
    document_json LONGTEXT NOT NULL,
    known_fields_json LONGTEXT NOT NULL,
    resolution_json LONGTEXT NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY(draft_id,market,account_key,listing_version),
    CONSTRAINT fk_target_saved_draft FOREIGN KEY(draft_id) REFERENCES marketplace_draft(id) ON DELETE CASCADE
);
CREATE TABLE marketplace_comparison_resolution (
    created_by BIGINT NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    draft_id BIGINT NOT NULL,
    market VARCHAR(16) NOT NULL,
    listing_version BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY(created_by,request_id),
    CONSTRAINT fk_comparison_resolution_draft FOREIGN KEY(draft_id) REFERENCES marketplace_draft(id) ON DELETE CASCADE
);
