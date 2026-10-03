-- Inventory identifies merchandise through catalog_product, including soft-deleted products.
-- Keep purchase lots and all quantity/financial/movement data; retire the duplicated snapshots.
ALTER TABLE inventory_item
    DROP COLUMN purchased_code,
    DROP COLUMN purchased_name;
