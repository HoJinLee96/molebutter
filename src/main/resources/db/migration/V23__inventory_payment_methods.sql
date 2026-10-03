CREATE TABLE inventory_payment_method (
 id BIGINT PRIMARY KEY,
 revision BIGINT NOT NULL DEFAULT 0,
 name VARCHAR(100) NOT NULL,
 deleted_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL
);
INSERT INTO inventory_payment_method(id,name,created_at,updated_at)
 VALUES (1,'카드',NOW(6),NOW(6)),(2,'계좌이체',NOW(6),NOW(6)),(3,'현금',NOW(6),NOW(6));
ALTER TABLE inventory_purchase
 ADD COLUMN payment_method_id BIGINT NULL,
 ADD COLUMN payment_amount BIGINT NULL,
 ADD CONSTRAINT fk_inventory_payment_method FOREIGN KEY(payment_method_id) REFERENCES inventory_payment_method(id),
 ADD CONSTRAINT ck_inventory_payment_amount CHECK(payment_amount IS NULL OR payment_amount BETWEEN 0 AND 1000000000000000);
