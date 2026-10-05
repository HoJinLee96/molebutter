CREATE TABLE product_brand (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY ux_brand_name(name)
);

-- 기존 표시명은 전환 기록으로 보존하며, 이후 표시명은 브랜드 등록부를 사용한다.
INSERT INTO product_brand(id, name, created_at, updated_at)
SELECT MIN(id), MIN(brand), NOW(6), NOW(6) FROM catalog_product WHERE brand <> '' GROUP BY brand;
ALTER TABLE catalog_product ADD COLUMN brand_id BIGINT NULL,
    ADD CONSTRAINT fk_catalog_brand FOREIGN KEY (brand_id) REFERENCES product_brand(id);
UPDATE catalog_product p JOIN product_brand b ON b.name = p.brand SET p.brand_id = b.id;

CREATE TABLE sales_channel (
    code VARCHAR(30) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY ux_sales_channel_name(name)
);
INSERT INTO sales_channel(code, name, created_at, updated_at) VALUES ('COUPANG', '쿠팡', NOW(6), NOW(6));
ALTER TABLE channel_listing ADD CONSTRAINT fk_listing_channel FOREIGN KEY (channel) REFERENCES sales_channel(code);
ALTER TABLE channel_pricing_settings ADD CONSTRAINT fk_pricing_channel FOREIGN KEY (channel) REFERENCES sales_channel(code);
ALTER TABLE channel_price_preview ADD CONSTRAINT fk_preview_channel FOREIGN KEY (channel) REFERENCES sales_channel(code);
