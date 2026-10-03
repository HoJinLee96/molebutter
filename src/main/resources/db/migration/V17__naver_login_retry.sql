ALTER TABLE product_refresh_run
    ADD block_reason VARCHAR(40) NULL,
    ADD login_retry_count INT NOT NULL DEFAULT 0,
    ADD next_retry_at DATETIME(6) NULL;
ALTER TABLE product_settings ADD next_search_at DATETIME(6) NULL;
