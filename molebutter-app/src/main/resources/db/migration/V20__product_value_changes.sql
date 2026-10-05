-- Review tokens remain monotonic even after a search-criteria reset or merge.
ALTER TABLE catalog_product ADD COLUMN change_version BIGINT NOT NULL DEFAULT 0;
-- Append-only observations/reviews; the summary is a rebuildable current projection.
CREATE TABLE product_value_observation (
 id BIGINT PRIMARY KEY,
 product_id BIGINT NOT NULL,
 origin_product_id BIGINT NOT NULL,
 lookup_revision BIGINT NOT NULL,
 supplier_id BIGINT NOT NULL,
 source_key VARCHAR(100) NOT NULL,
 source_type VARCHAR(24) NOT NULL,
 observed_at DATETIME(6) NOT NULL,
 snapshot JSON NOT NULL,
 changes JSON NOT NULL,
 categories JSON NOT NULL,
 UNIQUE KEY uk_value_observation (origin_product_id,lookup_revision,supplier_id,source_key),
 KEY ix_value_observation_product (product_id,id),
 FOREIGN KEY (product_id) REFERENCES catalog_product(id)
);
CREATE TABLE product_change_review (
 id BIGINT PRIMARY KEY,
 product_id BIGINT NOT NULL,
 origin_product_id BIGINT NOT NULL,
 lookup_revision BIGINT NOT NULL,
 kind VARCHAR(24) NOT NULL,
 actor_id BIGINT NULL,
 actor_name VARCHAR(120) NULL,
 created_at DATETIME(6) NOT NULL,
 basis JSON NOT NULL,
 KEY ix_change_review_product (product_id,id),
 FOREIGN KEY (product_id) REFERENCES catalog_product(id)
);
CREATE TABLE product_change_summary (
 product_id BIGINT PRIMARY KEY,
 lookup_revision BIGINT NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 selected_changed BOOLEAN NOT NULL DEFAULT FALSE,
 any_changed BOOLEAN NOT NULL DEFAULT FALSE,
 state JSON NOT NULL,
 summary JSON NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 KEY ix_change_selected (selected_changed,product_id),
 KEY ix_change_any (any_changed,product_id),
 FOREIGN KEY (product_id) REFERENCES catalog_product(id)
);
