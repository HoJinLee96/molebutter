ALTER TABLE product_settings ADD stock_lookup_blocked_job BIGINT NULL;
CREATE TABLE supplier_stock_lookup (
 id BIGINT PRIMARY KEY, product_id BIGINT NOT NULL, supplier_id BIGINT NOT NULL, actor_id BIGINT NOT NULL,
 run_id BIGINT NOT NULL, lookup_revision BIGINT NOT NULL, assignment_revision BIGINT NOT NULL,
 preference_revision BIGINT NOT NULL, selection_snapshot JSON NOT NULL,
 status VARCHAR(24) NOT NULL, revision BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL, started_at DATETIME(6) NULL, finished_at DATETIME(6) NULL,
 message VARCHAR(255) NULL, result JSON NULL,
 active_supplier BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING','RUNNING','BLOCKED') THEN supplier_id ELSE NULL END) STORED,
 UNIQUE KEY ux_stock_lookup_active(active_supplier),
 KEY ix_stock_lookup_queue(status,id), KEY ix_stock_lookup_product(product_id,supplier_id,id),
 FOREIGN KEY(product_id) REFERENCES catalog_product(id), FOREIGN KEY(supplier_id) REFERENCES product_supplier(id),
 FOREIGN KEY(run_id) REFERENCES product_refresh_run(id)
);
