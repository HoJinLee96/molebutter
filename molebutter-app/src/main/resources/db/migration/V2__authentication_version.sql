-- 비밀번호 변경·계정 정지·역할 변경 시 기존 JWT를 즉시 무효화한다.
ALTER TABLE `user` ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
