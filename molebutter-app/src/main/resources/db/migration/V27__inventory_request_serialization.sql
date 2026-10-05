-- Request-key locks replace global serialization for concurrent inventory retries.
-- Keep rows while the corresponding ledger/idempotency history exists; do not expire these locks independently.
CREATE TABLE inventory_request_lock (
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY
) ENGINE=InnoDB;
