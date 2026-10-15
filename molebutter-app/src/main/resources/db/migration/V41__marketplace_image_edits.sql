CREATE TABLE marketplace_image_edit (
 asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 original_asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_url VARCHAR(4000) NOT NULL,
 source_sha256 CHAR(64) NOT NULL,
 x INT NOT NULL, y INT NOT NULL, width INT NOT NULL, height INT NOT NULL,
 processing_version INT NOT NULL,
 CONSTRAINT fk_image_edit_asset FOREIGN KEY(asset_id) REFERENCES marketplace_asset(id) ON DELETE CASCADE,
 CONSTRAINT fk_image_edit_original FOREIGN KEY(original_asset_id) REFERENCES marketplace_asset(id),
 INDEX idx_image_edit_original(original_asset_id)
);
