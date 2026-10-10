ALTER TABLE marketplace_execution
    ADD COLUMN revised_at DATETIME(6) NULL,
    ADD COLUMN revised_by BIGINT NULL;
