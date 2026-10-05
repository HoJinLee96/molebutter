CREATE TABLE catalog_product (
 id BIGINT PRIMARY KEY, brand VARCHAR(100) NOT NULL DEFAULT '', product_code VARCHAR(100) NOT NULL DEFAULT '',
 search_query VARCHAR(255) NOT NULL DEFAULT '', managed BOOLEAN NOT NULL DEFAULT TRUE,
 revision BIGINT NOT NULL DEFAULT 0, merged_into BIGINT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY (merged_into) REFERENCES catalog_product(id), INDEX ix_catalog_code(product_code), INDEX ix_catalog_brand(brand,id)
);
CREATE TABLE purchase_option (
 id BIGINT PRIMARY KEY, product_id BIGINT NOT NULL, label VARCHAR(1000) NOT NULL, revision BIGINT NOT NULL DEFAULT 0,
 latest_status VARCHAR(30) NOT NULL DEFAULT 'NOT_CHECKED', latest_result JSON NULL, last_good_result JSON NULL, latest_at DATETIME(6) NULL,
 FOREIGN KEY (product_id) REFERENCES catalog_product(id), INDEX ix_purchase_product(product_id,id)
);
CREATE TABLE purchase_source_link (
 id BIGINT PRIMARY KEY, purchase_option_id BIGINT NOT NULL, mall VARCHAR(30) NOT NULL,
 mall_product_id VARCHAR(100) NOT NULL, naver_product_id VARCHAR(100) NOT NULL,
 option_id VARCHAR(200) NOT NULL, option_label VARCHAR(500) NOT NULL, url VARCHAR(2000) NOT NULL, image_url VARCHAR(2000) NULL,
 FOREIGN KEY (purchase_option_id) REFERENCES purchase_option(id),
 UNIQUE KEY ux_purchase_source(purchase_option_id,mall,mall_product_id,option_id)
);
CREATE TABLE channel_listing (
 id BIGINT PRIMARY KEY, channel VARCHAR(30) NOT NULL, external_option_id VARCHAR(40) NOT NULL,
 purchase_option_id BIGINT NULL, revision BIGINT NOT NULL DEFAULT 0,
 product_code VARCHAR(100) NOT NULL, name VARCHAR(1000) NOT NULL, option_name VARCHAR(1000) NOT NULL,
 current_price BIGINT NULL, approval_status VARCHAR(100) NOT NULL, sale_status VARCHAR(100) NOT NULL,
 import_id BIGINT NOT NULL, import_row INT NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY (purchase_option_id) REFERENCES purchase_option(id), FOREIGN KEY (import_id) REFERENCES product_import(id),
 UNIQUE KEY ux_channel_option(channel,external_option_id), INDEX ix_listing_import(import_id,id)
);
CREATE TABLE channel_pricing_settings (
 channel VARCHAR(30) PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 0, shipping BIGINT NOT NULL,
 fee DECIMAL(12,8) NOT NULL, margin DECIMAL(12,8) NOT NULL
);
CREATE TABLE purchase_refresh_item (
 run_id BIGINT NOT NULL, purchase_option_id BIGINT NOT NULL, option_revision BIGINT NOT NULL,
 query VARCHAR(255) NOT NULL, links JSON NOT NULL, status VARCHAR(30) NOT NULL DEFAULT 'PENDING', result JSON NULL, checked_at DATETIME(6) NULL,
 PRIMARY KEY(run_id,purchase_option_id), FOREIGN KEY(run_id) REFERENCES product_refresh_run(id),
 FOREIGN KEY(purchase_option_id) REFERENCES purchase_option(id), INDEX ix_purchase_pending(run_id,status,purchase_option_id)
);
CREATE TABLE channel_price_preview (
 id BIGINT PRIMARY KEY, import_id BIGINT NOT NULL, channel VARCHAR(30) NOT NULL,
 created_by BIGINT NOT NULL, created_at DATETIME(6) NOT NULL, snapshot JSON NOT NULL,
 FOREIGN KEY(import_id) REFERENCES product_import(id), FOREIGN KEY(created_by) REFERENCES `user`(id)
);
CREATE TABLE catalog_merge_history (
 id BIGINT PRIMARY KEY, target_id BIGINT NOT NULL, actor_id BIGINT NOT NULL, created_at DATETIME(6) NOT NULL,
 before_snapshot JSON NOT NULL, after_snapshot JSON NOT NULL,
 FOREIGN KEY(target_id) REFERENCES catalog_product(id), FOREIGN KEY(actor_id) REFERENCES `user`(id)
);
INSERT INTO catalog_product(id,product_code,search_query,managed,created_at,updated_at)
 SELECT id,product_code,search_query,enabled,updated_at,updated_at FROM product_item;
INSERT INTO purchase_option(id,product_id,label,last_good_result)
 SELECT id,id,IF(option_name='', '기존 구매 옵션', option_name),COALESCE(last_good_result,latest_result) FROM product_item;
INSERT INTO purchase_source_link SELECT id,product_id,mall,mall_product_id,naver_product_id,option_id,option_label,url,image_url FROM product_source_link;
INSERT INTO channel_listing(id,channel,external_option_id,purchase_option_id,product_code,name,option_name,current_price,approval_status,sale_status,import_id,import_row,updated_at)
 SELECT id,'COUPANG',vendor_item_id,id,product_code,name,option_name,current_price,approval_status,sale_status,import_id,import_row,updated_at FROM product_item;
INSERT INTO channel_pricing_settings(channel,shipping,fee,margin) SELECT 'COUPANG',shipping,fee,margin FROM product_settings;
INSERT INTO purchase_refresh_item SELECT run_id,product_id,product_revision,query,links,status,result,checked_at FROM product_refresh_item;
