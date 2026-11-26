CREATE SCHEMA IF NOT EXISTS sc_ops;
CREATE TABLE IF NOT EXISTS sc_ops.report_ticket (
    seq BIGINT AUTO_INCREMENT PRIMARY KEY,
    id CHAR(36) NOT NULL UNIQUE,
    reporter_id CHAR(36) NOT NULL,
    reported_id CHAR(36) NOT NULL,
    created_ms BIGINT NOT NULL,
    closed_ms BIGINT,
    key_id CHAR(64) NOT NULL,
    wrapped_key VARBINARY(512) NOT NULL,
    iv VARBINARY(12) NOT NULL,
    ciphertext BLOB NOT NULL,
    INDEX report_retention (closed_ms)
);
CREATE TABLE IF NOT EXISTS sc_ops.audit_event (
    seq BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator_name VARCHAR(255) NOT NULL,
    action_name VARCHAR(64) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    occurred_ms BIGINT NOT NULL,
    INDEX audit_retention (occurred_ms)
);
