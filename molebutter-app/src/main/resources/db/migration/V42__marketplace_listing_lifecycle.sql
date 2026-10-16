CREATE TABLE marketplace_listing_state (
    draft_id BIGINT NOT NULL,
    market VARCHAR(16) NOT NULL,
    account_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_version BIGINT NOT NULL DEFAULT 1,
    state VARCHAR(32) NOT NULL,
    reason VARCHAR(64) NULL,
    checked_at TIMESTAMP(6) NULL,
    observed_json LONGTEXT NULL,
    PRIMARY KEY(draft_id,market,account_key),
    CONSTRAINT fk_listing_state_draft FOREIGN KEY(draft_id) REFERENCES marketplace_draft(id)
);
CREATE TABLE marketplace_listing_history (
    id BIGINT NOT NULL PRIMARY KEY,
    draft_id BIGINT NOT NULL,
    market VARCHAR(16) NOT NULL,
    account_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_version BIGINT NOT NULL,
    mapping_json LONGTEXT NOT NULL,
    document_json LONGTEXT NOT NULL,
    intent_json LONGTEXT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uq_listing_recreate_request(created_by,request_id),
    UNIQUE KEY uq_listing_history_generation(draft_id,market,account_key,listing_version),
    CONSTRAINT fk_listing_history_draft FOREIGN KEY(draft_id) REFERENCES marketplace_draft(id)
);
CREATE TABLE marketplace_listing_history_asset (
    history_id BIGINT NOT NULL,
    asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(history_id,asset_id),
    CONSTRAINT fk_listing_history_asset_history FOREIGN KEY(history_id) REFERENCES marketplace_listing_history(id),
    CONSTRAINT fk_listing_history_asset_asset FOREIGN KEY(asset_id) REFERENCES marketplace_asset(id)
);
