-- =====================================================================
-- V1: 초기 스키마 — user, user_auth_audit_log, operation_audit_log + ADMIN seed
-- =====================================================================

CREATE TABLE `user` (
    id                    BIGINT       NOT NULL,
    email                 VARCHAR(255) NOT NULL,
    phone_number          VARCHAR(20)  NULL,
    name                  VARCHAR(100) NOT NULL,
    password_hash         VARCHAR(255) NOT NULL,
    user_role             VARCHAR(30)  NOT NULL,
    user_status           VARCHAR(30)  NOT NULL,
    failed_login_attempts INT          NOT NULL DEFAULT 0,
    last_login_at         DATETIME(6)  NULL,
    password_changed_at   DATETIME(6)  NULL,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NULL,
    created_by            VARCHAR(255) NULL,
    updated_by            VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_user_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_auth_audit_log (
    id                   BIGINT       NOT NULL,
    user_role            VARCHAR(30)  NULL,
    user_id              BIGINT       NULL,
    email                VARCHAR(255) NULL,
    user_auth_event_type VARCHAR(40)  NOT NULL,
    ip                   VARCHAR(100) NULL,
    user_agent           VARCHAR(500) NULL,
    success              TINYINT(1)   NOT NULL,
    failure_reason       VARCHAR(500) NULL,
    created_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY ix_user_auth_audit_log_created_at (created_at),
    KEY ix_user_auth_audit_log_user_created (user_id, created_at),
    KEY ix_user_auth_audit_log_event_created (user_auth_event_type, created_at),
    KEY ix_user_auth_audit_log_ip_created (ip, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE operation_audit_log (
    id             BIGINT       NOT NULL,
    user_role      VARCHAR(20)  NOT NULL,
    user_id        BIGINT       NOT NULL,
    email          VARCHAR(255) NOT NULL,
    event_type     VARCHAR(50)  NOT NULL,
    target_type    VARCHAR(100) NULL,
    target_id      VARCHAR(500) NULL,
    http_method    VARCHAR(10)  NULL,
    request_uri    VARCHAR(500) NULL,
    ip             VARCHAR(100) NULL,
    user_agent     VARCHAR(500) NULL,
    success        TINYINT(1)   NOT NULL,
    failure_reason VARCHAR(500) NULL,
    created_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY ix_operation_audit_log_created_at (created_at),
    KEY ix_operation_audit_log_user_created (user_id, created_at),
    KEY ix_operation_audit_log_event_created (event_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- 초기 관리자(대표) 계정. 초기 비밀번호: admin1234!
-- ---------------------------------------------------------------------
INSERT INTO `user`
    (id, email, name, password_hash, user_role, user_status, failed_login_attempts, created_at, created_by)
VALUES
    (1, 'moremall2102@naver.com', '관리자', '$2y$10$Iig12CjfO5QhFnZ/TEss8e9qYyEjz2kGzFaC0L9AFNESU9pTRFXFW',
     'ADMIN', 'ACTIVE', 0, NOW(6), 'SYSTEM');
