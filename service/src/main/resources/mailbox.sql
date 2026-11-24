CREATE TABLE IF NOT EXISTS mailbox_delivery (
    envelope_seq BIGINT AUTO_INCREMENT PRIMARY KEY,
    envelope_ref VARCHAR(36) NOT NULL UNIQUE,
    sender_account VARCHAR(36) NOT NULL,
    sender_device VARCHAR(36) NOT NULL,
    recipient_account VARCHAR(36) NOT NULL,
    recipient_device VARCHAR(36) NOT NULL,
    message_id VARCHAR(36) NOT NULL,
    expires_ms BIGINT NOT NULL,
    ciphertext_hash BINARY(32) NOT NULL,
    phase VARCHAR(8) NOT NULL,
    push_due_ms BIGINT,
    push_attempts INT NOT NULL DEFAULT 0,
    UNIQUE (recipient_device, sender_device, message_id),
    INDEX mailbox_page (recipient_device, envelope_seq),
    INDEX mailbox_sender (sender_device),
    INDEX mailbox_expiry (phase, expires_ms),
    INDEX mailbox_push_due (push_due_ms)
);
CREATE TABLE IF NOT EXISTS mailbox_push_binding (
    device_id VARCHAR(36) PRIMARY KEY,
    apns_token VARCHAR(512) NOT NULL UNIQUE
);
