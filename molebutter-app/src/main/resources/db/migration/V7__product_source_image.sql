-- 동일 상품과 옵션임을 확인하여 연결한 매입처 사진만 상품 대표 이미지로 사용한다.
ALTER TABLE product_source_link ADD COLUMN image_url VARCHAR(2000) NULL;
