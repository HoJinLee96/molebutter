CREATE TABLE product_import (
 id BIGINT PRIMARY KEY, file_name VARCHAR(255) NOT NULL, workbook LONGBLOB NOT NULL,
 uploaded_by BIGINT NOT NULL, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY (uploaded_by) REFERENCES `user`(id)
);
CREATE TABLE product_item (
 id BIGINT PRIMARY KEY, vendor_item_id VARCHAR(40) NOT NULL UNIQUE,
 product_code VARCHAR(100) NOT NULL DEFAULT '', name VARCHAR(1000) NOT NULL,
 option_name VARCHAR(1000) NOT NULL, color VARCHAR(100) NOT NULL DEFAULT '', size VARCHAR(100) NOT NULL DEFAULT '',
 search_query VARCHAR(255) NOT NULL DEFAULT '', search_manual BOOLEAN NOT NULL DEFAULT FALSE,
 current_price BIGINT NULL, approval_status VARCHAR(100) NOT NULL, sale_status VARCHAR(100) NOT NULL,
 import_id BIGINT NOT NULL, import_row INT NOT NULL, revision BIGINT NOT NULL DEFAULT 0,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, vat_deductible BOOLEAN NOT NULL DEFAULT TRUE,
 shipping_override BIGINT NULL, fee_override DECIMAL(12,8) NULL, margin_override DECIMAL(12,8) NULL,
 latest_status VARCHAR(30) NOT NULL DEFAULT 'NOT_CHECKED', latest_result JSON NULL, last_good_result JSON NULL,
 latest_at DATETIME(6) NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY (import_id) REFERENCES product_import(id), INDEX ix_product_code(product_code), INDEX ix_product_status(latest_status,id)
);
CREATE TABLE product_source_link (
 id BIGINT PRIMARY KEY, product_id BIGINT NOT NULL, mall VARCHAR(30) NOT NULL,
 mall_product_id VARCHAR(100) NOT NULL, naver_product_id VARCHAR(100) NOT NULL,
 option_id VARCHAR(200) NOT NULL, option_label VARCHAR(500) NOT NULL, url VARCHAR(2000) NOT NULL,
 FOREIGN KEY (product_id) REFERENCES product_item(id),
 UNIQUE KEY ux_product_source(product_id,mall,mall_product_id,option_id)
);
CREATE TABLE product_settings (
 id INT PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 0, shipping BIGINT NOT NULL DEFAULT 4000,
 fee DECIMAL(12,8) NOT NULL DEFAULT 0.105, margin DECIMAL(12,8) NOT NULL DEFAULT 0.06,
 schedule_enabled BOOLEAN NOT NULL DEFAULT TRUE, schedule_time VARCHAR(5) NOT NULL DEFAULT '18:00',
 last_schedule_date DATE NULL, worker_owner VARCHAR(100) NULL, worker_until DATETIME(6) NULL
);
INSERT INTO product_settings(id) VALUES(1);
CREATE TABLE product_refresh_run (
 id BIGINT PRIMARY KEY, status VARCHAR(30) NOT NULL, trigger_type VARCHAR(20) NOT NULL,
 created_by BIGINT NULL, created_at DATETIME(6) NOT NULL, finished_at DATETIME(6) NULL, message VARCHAR(1000) NULL
);
CREATE TABLE product_refresh_item (
 run_id BIGINT NOT NULL, product_id BIGINT NOT NULL, product_revision BIGINT NOT NULL,
 query VARCHAR(255) NOT NULL, links JSON NOT NULL, status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
 result JSON NULL, checked_at DATETIME(6) NULL,
 PRIMARY KEY(run_id,product_id), FOREIGN KEY(run_id) REFERENCES product_refresh_run(id),
 FOREIGN KEY(product_id) REFERENCES product_item(id), INDEX ix_refresh_pending(run_id,status,product_id)
);
CREATE TABLE product_refresh_search (
 run_id BIGINT NOT NULL, query_hash VARCHAR(64) NOT NULL, result JSON NOT NULL, checked_at DATETIME(6) NOT NULL,
 PRIMARY KEY(run_id,query_hash), FOREIGN KEY(run_id) REFERENCES product_refresh_run(id)
);
CREATE TABLE product_price_preview (
 id BIGINT PRIMARY KEY, import_id BIGINT NOT NULL, settings_revision BIGINT NOT NULL,
 created_by BIGINT NOT NULL, created_at DATETIME(6) NOT NULL, snapshot JSON NOT NULL,
 FOREIGN KEY(import_id) REFERENCES product_import(id), FOREIGN KEY(created_by) REFERENCES `user`(id)
);
