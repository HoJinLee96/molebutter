ALTER TABLE product_refresh_run
 ADD search_retry_count INT NOT NULL DEFAULT 0,
 ADD search_failure_stage VARCHAR(40) NULL,
 ADD search_failure_code VARCHAR(40) NULL,
 ADD search_failure_signature VARCHAR(180) NULL;
ALTER TABLE product_settings
 ADD search_cooldown_until DATETIME(6) NULL,
 ADD search_manual_resume_required BOOLEAN NOT NULL DEFAULT FALSE,
 ADD search_gate_attempt_id BIGINT NULL,
 ADD search_gate_run_id BIGINT NULL,
 ADD search_gate_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE product_search_attempt (
 id BIGINT PRIMARY KEY,
 run_id BIGINT NOT NULL,
 product_id BIGINT NOT NULL,
 lookup_revision BIGINT NOT NULL,
 attempt_no INT NOT NULL,
 owner_token VARCHAR(100) NOT NULL,
 status VARCHAR(24) NOT NULL,
 stage VARCHAR(40) NULL,
 reason_code VARCHAR(40) NULL,
 http_status INT NULL,
 safe_diagnostics JSON NULL,
 started_at DATETIME(6) NOT NULL,
 finished_at DATETIME(6) NULL,
 retry_at DATETIME(6) NULL,
 active_product BIGINT GENERATED ALWAYS AS (CASE WHEN status='RUNNING' THEN product_id ELSE NULL END) STORED,
 UNIQUE KEY uk_search_attempt_number (run_id,product_id,attempt_no),
 UNIQUE KEY uk_search_attempt_active (run_id,active_product),
 KEY ix_search_attempt_run (run_id,id),
 KEY ix_search_attempt_status (status,id),
 FOREIGN KEY (run_id) REFERENCES product_refresh_run(id),
 FOREIGN KEY (product_id) REFERENCES catalog_product(id)
);
-- Preserve a scheduled legacy login retry, without waking existing manual stops.
UPDATE product_refresh_run SET search_retry_count=login_retry_count,
 search_failure_code=block_reason,search_failure_stage='SEARCH'
 WHERE login_retry_count>0;
UPDATE product_settings SET search_cooldown_until=(SELECT MAX(next_retry_at) FROM product_refresh_run
 WHERE status IN ('RETRY_WAIT','PAUSED','BLOCKED')),
 search_gate_run_id=(SELECT id FROM product_refresh_run WHERE status IN ('RETRY_WAIT','PAUSED','BLOCKED')
 AND (next_retry_at IS NOT NULL OR login_retry_count>=5) ORDER BY id DESC LIMIT 1),
 search_manual_resume_required=EXISTS(SELECT 1 FROM product_refresh_run WHERE status IN ('PAUSED','BLOCKED') AND login_retry_count>=5)
 WHERE id=1;
