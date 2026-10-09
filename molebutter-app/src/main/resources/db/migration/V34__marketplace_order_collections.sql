CREATE TABLE marketplace_order (
    id BIGINT NOT NULL PRIMARY KEY,
    market VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    collected_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_marketplace_order (market, account_key, order_id)
);
CREATE TABLE marketplace_order_item (
    id BIGINT NOT NULL PRIMARY KEY,
    parent_id BIGINT NOT NULL,
    shipment_box_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    vendor_item_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(60) NOT NULL,
    product_name VARCHAR(500) NOT NULL,
    ordered_at DATETIME(6) NULL,
    paid_at DATETIME(6) NULL,
    snapshot_json LONGTEXT NOT NULL,
    UNIQUE KEY uq_marketplace_order_item (parent_id, shipment_box_id, sequence_no, vendor_item_id),
    INDEX idx_marketplace_order_item_status (status),
    CONSTRAINT fk_marketplace_order_item FOREIGN KEY (parent_id) REFERENCES marketplace_order(id) ON DELETE CASCADE
);
CREATE TABLE marketplace_order_claim (
    id BIGINT NOT NULL PRIMARY KEY,
    market VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claim_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claim_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shipment_box_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    vendor_item_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at VARCHAR(50) NULL,
    updated_at DATETIME(6) NULL,
    snapshot_json LONGTEXT NOT NULL,
    UNIQUE KEY uq_marketplace_order_claim (market, account_key, claim_type, claim_id, shipment_box_id, vendor_item_id),
    INDEX idx_marketplace_order_claim_order (market, account_key, order_id)
);
CREATE TABLE marketplace_order_job (
    id BIGINT NOT NULL PRIMARY KEY,
    created_by BIGINT NOT NULL,
    account_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lease_owner VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    status VARCHAR(24) NOT NULL,
    request_json LONGTEXT NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    UNIQUE KEY uq_marketplace_order_job_request (created_by, request_id),
    INDEX idx_marketplace_order_job_status (status)
);
CREATE TABLE marketplace_order_checkpoint (
    id BIGINT NOT NULL PRIMARY KEY,
    job_id BIGINT NOT NULL,
    stream VARCHAR(200) NOT NULL,
    date_from DATE NOT NULL,
    date_to DATE NOT NULL,
    cursor_value TEXT NULL,
    expected_seen BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(24) NOT NULL,
    message VARCHAR(500) NULL,
    CONSTRAINT fk_marketplace_order_checkpoint FOREIGN KEY (job_id) REFERENCES marketplace_order_job(id) ON DELETE CASCADE
);
CREATE TABLE marketplace_order_job_order (
    job_id BIGINT NOT NULL,
    parent_id BIGINT NOT NULL,
    snapshot_observed BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (job_id, parent_id),
    CONSTRAINT fk_marketplace_order_job_order_job FOREIGN KEY (job_id) REFERENCES marketplace_order_job(id) ON DELETE CASCADE,
    CONSTRAINT fk_marketplace_order_job_order_parent FOREIGN KEY (parent_id) REFERENCES marketplace_order(id) ON DELETE CASCADE
);

CREATE TABLE marketplace_order_cursor (
    checkpoint_id BIGINT NOT NULL,
    cursor_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (checkpoint_id,cursor_hash),
    CONSTRAINT fk_marketplace_order_cursor FOREIGN KEY (checkpoint_id) REFERENCES marketplace_order_checkpoint(id) ON DELETE CASCADE
);
