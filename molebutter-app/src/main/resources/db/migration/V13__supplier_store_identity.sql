ALTER TABLE supplier_store ADD retailer VARCHAR(120) NULL;
CREATE TABLE supplier_store_identity (
    mall VARCHAR(30) NOT NULL,
    namespace VARCHAR(40) NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    store_id BIGINT NOT NULL,
    PRIMARY KEY (mall, namespace, external_id),
    FOREIGN KEY (store_id) REFERENCES supplier_store(id) ON DELETE CASCADE
);
-- 단일 백화점인 더현대Hi만 확정 가능한 운영사를 채운다. 기존 ID와 연결은 유지한다.
UPDATE supplier_store SET retailer='현대백화점' WHERE mall='HI_THEHYUNDAI' AND kind='BRANCH';
