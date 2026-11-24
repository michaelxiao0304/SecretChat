CREATE TABLE IF NOT EXISTS sc_ops.provider_failure (
    provider VARCHAR(8) NOT NULL,
    failed_ms BIGINT NOT NULL,
    INDEX provider_time (provider, failed_ms),
    INDEX failure_expiry (failed_ms)
);
