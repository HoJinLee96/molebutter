ALTER TABLE marketplace_order ADD COLUMN detail_reason VARCHAR(40) NULL;
-- Existing generic rejections remain unclassified until a new bounded read.
