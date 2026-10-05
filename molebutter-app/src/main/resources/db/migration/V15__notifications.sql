CREATE TABLE notification_event (
    id BIGINT PRIMARY KEY,
    event_key VARCHAR(191) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    severity VARCHAR(12) NOT NULL,
    title VARCHAR(160) NOT NULL,
    message VARCHAR(500) NOT NULL,
    target_type VARCHAR(40) NOT NULL,
    target_id BIGINT NULL,
    occurred_at DATETIME(6) NOT NULL,
    UNIQUE KEY ux_notification_event_key(event_key)
);
CREATE TABLE user_notification (
    user_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    dismissed_at DATETIME(6) NULL,
    PRIMARY KEY(user_id,event_id),
    FOREIGN KEY(user_id) REFERENCES `user`(id),
    FOREIGN KEY(event_id) REFERENCES notification_event(id),
    KEY ix_notification_inbox(user_id,dismissed_at,event_id)
);
ALTER TABLE product_refresh_run ADD notification_sequence BIGINT NOT NULL DEFAULT 0;
