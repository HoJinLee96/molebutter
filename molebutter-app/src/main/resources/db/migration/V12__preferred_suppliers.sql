CREATE TABLE supplier_store (
    id BIGINT PRIMARY KEY,
    mall VARCHAR(30) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    name VARCHAR(120) NOT NULL,
    identity_key VARCHAR(255) NOT NULL,
    aliases JSON NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_supplier_store_identity (mall, identity_key)
);
CREATE TABLE supplier_preference (
    id BIGINT PRIMARY KEY,
    mall VARCHAR(30) NOT NULL,
    store_id BIGINT NULL,
    scope_key VARCHAR(40) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_supplier_preference (mall, scope_key),
    FOREIGN KEY (store_id) REFERENCES supplier_store(id)
);
ALTER TABLE product_settings ADD preference_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE product_refresh_run ADD preference_snapshot JSON NULL;
ALTER TABLE product_supplier
    ADD merged_into BIGINT NULL,
    ADD auto_store_id BIGINT NULL,
    ADD manual_store_id BIGINT NULL,
    ADD assignment_revision BIGINT NOT NULL DEFAULT 0,
    ADD observation JSON NULL,
    ADD observation_revision BIGINT NULL,
    ADD observed_run_id BIGINT NULL,
    ADD last_price BIGINT NULL,
    ADD last_delivery_fee BIGINT NULL,
    ADD price_checked_at DATETIME(6) NULL,
    ADD FOREIGN KEY (auto_store_id) REFERENCES supplier_store(id),
    ADD FOREIGN KEY (manual_store_id) REFERENCES supplier_store(id);
CREATE TABLE product_supplier_selection (
    product_id BIGINT PRIMARY KEY,
    supplier_id BIGINT NOT NULL,
    selected_by BIGINT NOT NULL,
    selected_at DATETIME(6) NOT NULL,
    FOREIGN KEY (product_id) REFERENCES catalog_product(id),
    FOREIGN KEY (supplier_id) REFERENCES product_supplier(id)
);
CREATE TABLE product_supplier_change (
    id BIGINT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    change_type VARCHAR(30) NOT NULL,
    before_snapshot JSON NULL,
    after_snapshot JSON NULL,
    created_at DATETIME(6) NOT NULL,
    KEY ix_supplier_change_product (product_id, id),
    FOREIGN KEY (product_id) REFERENCES catalog_product(id)
);
-- 원래 판매글 ID와 최신 결과를 연결한다. 선정과 선호 목록은 비워 둔다.
UPDATE product_supplier s JOIN catalog_product p ON p.id=s.product_id
JOIN JSON_TABLE(p.latest_result, '$.suppliers[*]' COLUMNS (
    payload JSON PATH '$', mall VARCHAR(30) PATH '$.offer.mall',
    mall_id VARCHAR(100) PATH '$.offer.mallProductId', naver_id VARCHAR(100) PATH '$.offer.naverProductId'
)) j ON j.mall=s.mall AND j.mall_id=s.mall_product_id AND j.naver_id=s.naver_product_id
SET s.observation=j.payload,s.observation_revision=p.lookup_revision,
    s.last_price=JSON_VALUE(j.payload,'$.offer.price' RETURNING SIGNED),
    s.last_delivery_fee=JSON_VALUE(j.payload,'$.offer.deliveryFee' RETURNING SIGNED),
    s.price_checked_at=p.latest_at;
