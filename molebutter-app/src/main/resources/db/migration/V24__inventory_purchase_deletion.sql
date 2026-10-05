ALTER TABLE inventory_purchase
 ADD COLUMN deleted_at DATETIME(6) NULL,
 ADD COLUMN deleted_by BIGINT NULL,
 ADD COLUMN delete_request_id VARCHAR(36) NULL,
 ADD COLUMN delete_request_hash VARCHAR(64) NULL,
 ADD CONSTRAINT fk_inventory_purchase_deleted_by FOREIGN KEY(deleted_by) REFERENCES `user`(id),
 ADD UNIQUE KEY ux_inventory_purchase_delete_request(delete_request_id),
 ADD INDEX ix_inventory_purchase_active_date(deleted_at,purchased_on,id);
