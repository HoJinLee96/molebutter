-- Preserve existing HTTP audit rows while allowing system/background execution without a fabricated user.
ALTER TABLE operation_audit_log
    MODIFY user_role VARCHAR(20) NULL,
    MODIFY user_id BIGINT NULL,
    MODIFY email VARCHAR(255) NULL,
    ADD execution_source VARCHAR(12) NOT NULL DEFAULT 'HTTP',
    ADD operation_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT chk_operation_actor CHECK
      (execution_source='BACKGROUND' OR (execution_source='HTTP' AND user_role IS NOT NULL AND user_id IS NOT NULL AND email IS NOT NULL));
