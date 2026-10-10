-- Marketplace-owned drafts and originals; no catalog or owned-inventory relationship.
CREATE TABLE marketplace_draft (
    id BIGINT NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    product_code VARCHAR(200) NOT NULL DEFAULT '',
    product_name VARCHAR(500) NOT NULL DEFAULT '',
    document_json LONGTEXT NOT NULL,
    import_market VARCHAR(16) NULL,
    import_account VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    import_product_id VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_marketplace_import (import_market, import_account, import_product_id),
    KEY ix_marketplace_draft_updated (updated_at, id),
    CONSTRAINT ck_marketplace_draft_revision CHECK (revision >= 0)
);

CREATE TABLE marketplace_asset (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    relative_path VARCHAR(255) NOT NULL,
    media_type VARCHAR(50) NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    bytes BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    pending_delete_at DATETIME(6) NULL,
    KEY ix_marketplace_asset_cleanup (created_at, pending_delete_at),
    CONSTRAINT ck_marketplace_asset_size CHECK (width > 0 AND height > 0 AND bytes > 0)
);

CREATE TABLE marketplace_draft_asset (
    draft_id BIGINT NOT NULL,
    asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (draft_id, asset_id),
    KEY ix_marketplace_draft_asset_asset (asset_id),
    CONSTRAINT fk_marketplace_draft_asset_draft FOREIGN KEY (draft_id) REFERENCES marketplace_draft(id) ON DELETE CASCADE,
    CONSTRAINT fk_marketplace_draft_asset_asset FOREIGN KEY (asset_id) REFERENCES marketplace_asset(id)
);
