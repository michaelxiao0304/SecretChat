CREATE TABLE IF NOT EXISTS queued_envelope (
    id VARCHAR(36) PRIMARY KEY,
    device_id VARCHAR(36) NOT NULL,
    enqueued_ms BIGINT NOT NULL,
    expires_ms BIGINT NOT NULL,
    ciphertext BLOB NOT NULL,
    INDEX queue_expiry (expires_ms, enqueued_ms)
);
