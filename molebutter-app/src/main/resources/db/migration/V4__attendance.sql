-- V3 was retired before attendance. Do not reuse its version.
CREATE TABLE attendance (
    id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    work_date DATE NOT NULL,
    clock_in DATETIME(6) NOT NULL,
    clock_out DATETIME(6) NULL,
    status VARCHAR(20) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    created_by VARCHAR(255) NULL,
    updated_by VARCHAR(255) NULL,
    CONSTRAINT fk_attendance_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    UNIQUE KEY ux_attendance_user_date (user_id, work_date),
    KEY ix_attendance_active (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE attendance_break (
    attendance_id BIGINT NOT NULL,
    break_order INT NOT NULL,
    started_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    PRIMARY KEY (attendance_id, break_order),
    CONSTRAINT fk_break_attendance FOREIGN KEY (attendance_id) REFERENCES attendance (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE attendance_correction (
    id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    work_date DATE NOT NULL,
    attendance_id BIGINT NULL,
    base_revision BIGINT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    original_snapshot LONGTEXT NULL,
    proposed_snapshot LONGTEXT NOT NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME(6) NULL,
    review_comment VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    created_by VARCHAR(255) NULL,
    updated_by VARCHAR(255) NULL,
    CONSTRAINT fk_correction_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT fk_correction_attendance FOREIGN KEY (attendance_id) REFERENCES attendance (id),
    CONSTRAINT fk_correction_reviewer FOREIGN KEY (reviewed_by) REFERENCES `user` (id),
    KEY ix_correction_user_date (user_id, work_date, status),
    KEY ix_correction_review (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
