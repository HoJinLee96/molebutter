CREATE TABLE marketplace_order_account_lock (
    account_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    active_job_id BIGINT NULL,
    lease_owner VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL
);
ALTER TABLE marketplace_order_job
    ADD COLUMN current_stage VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
    ADD COLUMN error_stage VARCHAR(30) NULL,
    ADD COLUMN error_code VARCHAR(40) NULL,
    ADD COLUMN error_message VARCHAR(500) NULL,
    ADD COLUMN progress_reconstructed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE marketplace_order_checkpoint
    ADD COLUMN pages BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN error_stage VARCHAR(30) NULL,
    ADD COLUMN error_code VARCHAR(40) NULL;
CREATE TABLE marketplace_order_failed_detail (
    id BIGINT NOT NULL PRIMARY KEY,
    job_id BIGINT NOT NULL,
    order_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source VARCHAR(40) NOT NULL,
    date_from DATE NOT NULL,
    date_to DATE NOT NULL,
    status VARCHAR(24) NOT NULL,
    error_code VARCHAR(40) NULL,
    message VARCHAR(500) NULL,
    UNIQUE KEY uq_marketplace_order_failed_detail (job_id, order_id),
    CONSTRAINT fk_marketplace_order_failed_detail_job FOREIGN KEY (job_id) REFERENCES marketplace_order_job(id) ON DELETE CASCADE
);
CREATE TABLE marketplace_order_job_claim (
    job_id BIGINT NOT NULL,
    claim_id BIGINT NOT NULL,
    PRIMARY KEY(job_id, claim_id),
    CONSTRAINT fk_marketplace_order_job_claim_job FOREIGN KEY (job_id) REFERENCES marketplace_order_job(id) ON DELETE CASCADE,
    CONSTRAINT fk_marketplace_order_job_claim_claim FOREIGN KEY (claim_id) REFERENCES marketplace_order_claim(id) ON DELETE CASCADE
);

ALTER TABLE marketplace_order_claim ADD COLUMN latest_verified BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE marketplace_order_job_order ADD COLUMN observed_item_count BIGINT NOT NULL DEFAULT 0;

-- Earlier jobs did not retain item or receipt counts. Reconstruct them from the
-- currently retained snapshots and label these counts as reconstructed.
UPDATE marketplace_order_job
SET progress_reconstructed=TRUE,
    current_stage=CASE WHEN status IN ('QUEUED','RUNNING') THEN status ELSE 'DONE' END;
-- Preserve any existing worker lease when the account mutex is introduced.
INSERT INTO marketplace_order_account_lock(account_key,active_job_id,lease_owner,lease_until)
SELECT j.account_key,j.id,j.lease_owner,j.lease_until
FROM marketplace_order_job j
JOIN (SELECT account_key,MAX(id) AS id FROM marketplace_order_job
      WHERE status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(6)
      GROUP BY account_key) active ON active.id=j.id;
UPDATE marketplace_order_job_order jo
SET observed_item_count=(SELECT COUNT(*) FROM marketplace_order_item i WHERE i.parent_id=jo.parent_id)
WHERE snapshot_observed=TRUE;
INSERT IGNORE INTO marketplace_order_job_claim(job_id,claim_id)
SELECT jo.job_id,c.id
FROM marketplace_order_job_order jo
JOIN marketplace_order o ON o.id=jo.parent_id
JOIN marketplace_order_claim c ON c.market=o.market AND c.account_key=o.account_key AND c.order_id=o.order_id;
UPDATE marketplace_order_claim SET latest_verified=FALSE WHERE updated_at IS NULL;
