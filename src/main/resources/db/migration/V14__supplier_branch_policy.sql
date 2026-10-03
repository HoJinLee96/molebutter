CREATE TABLE supplier_mall_policy (
    mall VARCHAR(30) PRIMARY KEY,
    branch_required BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO supplier_mall_policy(mall,branch_required) VALUES
    ('LFMALL',FALSE),('HAZZYS',FALSE),('NAVER_SMART_STORE',TRUE),
    ('LOTTE_ON',TRUE),('LOTTE_IMALL',TRUE),('HI_THEHYUNDAI',TRUE),('HMALL',TRUE);
