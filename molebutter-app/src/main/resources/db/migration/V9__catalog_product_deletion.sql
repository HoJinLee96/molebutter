-- 조회·엑셀·통합 이력은 보존하고, 공통 상품만 관리 대상에서 삭제한다.
ALTER TABLE catalog_product
    ADD COLUMN deleted_at DATETIME(6) NULL,
    ADD COLUMN deleted_by BIGINT NULL,
    ADD CONSTRAINT fk_catalog_deleted_by FOREIGN KEY (deleted_by) REFERENCES `user`(id),
    ADD INDEX ix_catalog_active (deleted_at, merged_into, id);
