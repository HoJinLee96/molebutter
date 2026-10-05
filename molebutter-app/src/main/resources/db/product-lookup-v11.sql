ALTER TABLE product_brand ADD COLUMN code_brand VARCHAR(30) NOT NULL DEFAULT '';
UPDATE product_brand SET code_brand=CASE UPPER(REPLACE(name,' ','')) WHEN '닥스' THEN 'DAKS' WHEN 'DAKS' THEN 'DAKS' WHEN '헤지스' THEN 'HAZZYS' WHEN 'HAZZYS' THEN 'HAZZYS' WHEN '질스튜어트' THEN 'JILLSTUART' WHEN 'JILLSTUART' THEN 'JILLSTUART' ELSE '' END;
ALTER TABLE catalog_product
 ADD COLUMN code_type VARCHAR(30) NOT NULL DEFAULT 'GENERAL',
 ADD COLUMN comparison_code VARCHAR(100) NOT NULL DEFAULT '',
 ADD COLUMN search_mode VARCHAR(10) NOT NULL DEFAULT 'MANUAL',
 ADD COLUMN lookup_revision BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN registration_names JSON NULL,
 ADD COLUMN latest_status VARCHAR(30) NOT NULL DEFAULT 'NOT_CHECKED',
 ADD COLUMN latest_result JSON NULL,
 ADD COLUMN last_good_result JSON NULL,
 ADD COLUMN latest_at DATETIME(6) NULL,
 ADD COLUMN image_url VARCHAR(2000) NULL,
 ADD INDEX ix_catalog_comparison(comparison_code,id),
 ADD INDEX ix_catalog_lookup_status(latest_status,id);
CREATE TABLE product_supplier (
 id BIGINT PRIMARY KEY, product_id BIGINT NOT NULL, mall VARCHAR(30) NOT NULL,
 mall_product_id VARCHAR(100) NOT NULL, naver_product_id VARCHAR(100) NOT NULL,
 url VARCHAR(2000) NOT NULL, image_url VARCHAR(2000) NULL, last_seen_at DATETIME(6) NULL,
 FOREIGN KEY(product_id) REFERENCES catalog_product(id),
 UNIQUE KEY ux_supplier_product(product_id,mall,mall_product_id,naver_product_id)
);
CREATE TABLE product_lookup_history (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, product_id BIGINT NOT NULL, run_id BIGINT NULL,
 legacy BOOLEAN NOT NULL DEFAULT FALSE, created_at DATETIME(6) NOT NULL, payload JSON NOT NULL,
 FOREIGN KEY(product_id) REFERENCES catalog_product(id), INDEX ix_lookup_history(product_id,id)
);
CREATE TABLE product_refresh_entry (
 run_id BIGINT NOT NULL, product_id BIGINT NOT NULL, lookup_revision BIGINT NOT NULL,
 query VARCHAR(255) NOT NULL, product_code VARCHAR(100) NOT NULL, code_type VARCHAR(30) NOT NULL,
 brand_key VARCHAR(30) NOT NULL DEFAULT '', status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
 result JSON NULL, checked_at DATETIME(6) NULL,
 PRIMARY KEY(run_id,product_id), FOREIGN KEY(run_id) REFERENCES product_refresh_run(id),
 FOREIGN KEY(product_id) REFERENCES catalog_product(id), INDEX ix_entry_pending(run_id,status,product_id)
);
INSERT INTO product_supplier(id,product_id,mall,mall_product_id,naver_product_id,url,image_url)
 SELECT MIN(l.id),o.product_id,l.mall,l.mall_product_id,l.naver_product_id,MIN(l.url),MIN(l.image_url)
 FROM purchase_source_link l JOIN purchase_option o ON o.id=l.purchase_option_id
 GROUP BY o.product_id,l.mall,l.mall_product_id,l.naver_product_id;
INSERT INTO product_lookup_history(product_id,legacy,created_at,payload)
 SELECT o.product_id,TRUE,COALESCE(o.latest_at,p.updated_at),JSON_OBJECT('optionId',CAST(o.id AS CHAR),'optionLabel',o.label,'latestStatus',o.latest_status,'latestResult',o.latest_result,'lastGoodResult',o.last_good_result,
 'links',(SELECT JSON_ARRAYAGG(JSON_OBJECT('id',CAST(l.id AS CHAR),'mall',l.mall,'mallProductId',l.mall_product_id,'naverProductId',l.naver_product_id,'optionId',l.option_id,'optionLabel',l.option_label,'url',l.url,'imageUrl',l.image_url)) FROM purchase_source_link l WHERE l.purchase_option_id=o.id))
 FROM purchase_option o JOIN catalog_product p ON p.id=o.product_id;
INSERT INTO product_lookup_history(product_id,run_id,legacy,created_at,payload)
 SELECT o.product_id,i.run_id,TRUE,COALESCE(i.checked_at,r.created_at),JSON_OBJECT('optionId',CAST(o.id AS CHAR),'query',i.query,'status',i.status,'result',i.result,'links',i.links)
 FROM purchase_refresh_item i JOIN purchase_option o ON o.id=i.purchase_option_id JOIN product_refresh_run r ON r.id=i.run_id;
-- CLEANUP
UPDATE catalog_product p LEFT JOIN product_brand b ON b.id=p.brand_id SET p.code_type=IF(COALESCE(b.code_brand,'')<>'','LF_ACCESSORY','GENERAL');
UPDATE catalog_product SET comparison_code=CONCAT(SUBSTRING(UPPER(TRIM(product_code)),1,4),SUBSTRING(UPPER(TRIM(product_code)),7,3)) WHERE code_type='LF_ACCESSORY' AND UPPER(TRIM(product_code)) REGEXP '^[A-Z]{4}[0-9][EF][0-9]{3}[A-Z][A-Z0-9]$';
UPDATE catalog_product p SET image_url=(SELECT s.image_url FROM product_supplier s WHERE s.product_id=p.id AND s.image_url IS NOT NULL ORDER BY s.id LIMIT 1);
DROP TABLE channel_price_preview;
DROP TABLE channel_listing;
DROP TABLE channel_pricing_settings;
DROP TABLE sales_channel;
DROP TABLE purchase_refresh_item;
DROP TABLE purchase_source_link;
DROP TABLE purchase_option;
DROP TABLE product_price_preview;
DROP TABLE product_refresh_item;
DROP TABLE product_source_link;
DROP TABLE product_item;
DROP TABLE product_import;
ALTER TABLE product_settings DROP COLUMN shipping, DROP COLUMN fee, DROP COLUMN margin;
