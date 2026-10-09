CREATE TABLE marketplace_submission_preview (
    id BIGINT NOT NULL PRIMARY KEY,
    draft_id BIGINT NOT NULL,
    draft_revision BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    requested BOOLEAN NOT NULL,
    document_json LONGTEXT NOT NULL,
    prepared_json LONGTEXT NULL,
    preview_json LONGTEXT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_marketplace_preview_draft FOREIGN KEY(draft_id) REFERENCES marketplace_draft(id),
    KEY ix_marketplace_preview_draft(draft_id,created_at)
);

CREATE TABLE marketplace_execution (
    id BIGINT NOT NULL PRIMARY KEY,
    preview_id BIGINT NOT NULL,
    draft_id BIGINT NOT NULL,
    draft_revision BIGINT NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uq_marketplace_execution_preview(preview_id),
    UNIQUE KEY uq_marketplace_execution_key(idempotency_key),
    KEY ix_marketplace_execution_draft(draft_id,created_at),
    CONSTRAINT fk_marketplace_execution_preview FOREIGN KEY(preview_id) REFERENCES marketplace_submission_preview(id),
    CONSTRAINT fk_marketplace_execution_draft FOREIGN KEY(draft_id) REFERENCES marketplace_draft(id)
);

CREATE TABLE marketplace_execution_target (
    execution_id BIGINT NOT NULL,
    market VARCHAR(16) NOT NULL,
    account_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mode VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    external_product_id VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY(execution_id,market),
    CONSTRAINT fk_marketplace_target_execution FOREIGN KEY(execution_id) REFERENCES marketplace_execution(id)
);

CREATE TABLE marketplace_execution_step (
    execution_id BIGINT NOT NULL,
    step_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no INT NOT NULL,
    step_type VARCHAR(16) NOT NULL,
    option_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(16) NOT NULL,
    action VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    result_json LONGTEXT NULL,
    result_code VARCHAR(80) NULL,
    result_message VARCHAR(500) NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY(execution_id,step_id),
    KEY ix_marketplace_step_queue(status,execution_id,sequence_no),
    CONSTRAINT fk_marketplace_step_execution FOREIGN KEY(execution_id) REFERENCES marketplace_execution(id)
);

CREATE TABLE marketplace_execution_attempt (
    id BIGINT NOT NULL PRIMARY KEY,
    execution_id BIGINT NOT NULL,
    step_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    result_code VARCHAR(80) NULL,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_marketplace_attempt_step FOREIGN KEY(execution_id,step_id) REFERENCES marketplace_execution_step(execution_id,step_id)
);

CREATE TABLE marketplace_listing_mapping (
    draft_id BIGINT NOT NULL,
    market VARCHAR(16) NOT NULL,
    account_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    external_product_id VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mapping_json LONGTEXT NOT NULL,
    applied_revision BIGINT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY(draft_id,market,account_key),
    UNIQUE KEY uq_marketplace_listing(market,account_key,external_product_id),
    CONSTRAINT fk_marketplace_listing_draft FOREIGN KEY(draft_id) REFERENCES marketplace_draft(id)
);

CREATE TABLE marketplace_submission_account (
    account_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    lease_owner VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until TIMESTAMP(6) NULL
);

-- Pins belong to the immutable preview shared by its execution, including ambiguous outcomes.
CREATE TABLE marketplace_execution_asset (
    submission_id BIGINT NOT NULL,
    asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(submission_id,asset_id),
    CONSTRAINT fk_marketplace_execution_asset_preview FOREIGN KEY(submission_id) REFERENCES marketplace_submission_preview(id),
    CONSTRAINT fk_marketplace_execution_asset_image FOREIGN KEY(asset_id) REFERENCES marketplace_asset(id)
);

CREATE TABLE marketplace_asset_publication (
    asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    token CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uq_marketplace_asset_publication_token(token),
    CONSTRAINT fk_marketplace_publication_asset FOREIGN KEY(asset_id) REFERENCES marketplace_asset(id) ON DELETE CASCADE
);
