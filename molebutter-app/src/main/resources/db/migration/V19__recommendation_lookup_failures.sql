CREATE TABLE recommendation_lookup_diagnostic (
 id BIGINT PRIMARY KEY,
 run_id BIGINT NOT NULL,
 product_id BIGINT NOT NULL,
 listing_key VARCHAR(300) NOT NULL,
 mall VARCHAR(30) NOT NULL,
 kind VARCHAR(20) NOT NULL,
 cause_code VARCHAR(40) NOT NULL,
 http_status INT NULL,
 restricted BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(6) NOT NULL,
 payload JSON NOT NULL,
 UNIQUE KEY uk_recommendation_diagnostic (run_id,product_id,listing_key),
 KEY ix_recommendation_restriction (run_id,mall,restricted),
 FOREIGN KEY (run_id) REFERENCES product_refresh_run(id),
 FOREIGN KEY (product_id) REFERENCES catalog_product(id)
);
