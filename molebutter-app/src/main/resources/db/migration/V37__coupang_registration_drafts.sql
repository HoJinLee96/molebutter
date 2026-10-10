ALTER TABLE marketplace_draft
    ADD COLUMN editor_kind VARCHAR(32) NOT NULL DEFAULT 'COMMON',
    ADD COLUMN registration_account VARCHAR(64) NULL;
