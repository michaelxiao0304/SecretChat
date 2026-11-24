CREATE SCHEMA IF NOT EXISTS sc_directory;
CREATE SCHEMA IF NOT EXISTS sc_ledger;

CREATE TABLE IF NOT EXISTS sc_directory.accounts (
    account_id CHAR(36) PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS sc_directory.devices (
    device_id CHAR(36) PRIMARY KEY,
    account_id CHAR(36) NOT NULL UNIQUE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    identity_key BLOB NOT NULL,
    signed_prekey BLOB NOT NULL,
    protocol_prekeys BLOB,
    FOREIGN KEY (account_id) REFERENCES sc_directory.accounts(account_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS sc_directory.one_time_prekeys (
    device_id CHAR(36) NOT NULL,
    key_id BIGINT NOT NULL,
    material BLOB,
    PRIMARY KEY (device_id,key_id),
    FOREIGN KEY (device_id) REFERENCES sc_directory.devices(device_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS sc_directory.invite_settings (
    settings_id INT PRIMARY KEY,
    invite_limit INT NOT NULL,
    bootstrap_used BOOLEAN NOT NULL
);
INSERT INTO sc_directory.invite_settings(settings_id,invite_limit,bootstrap_used) VALUES (1,5,FALSE)
    ON DUPLICATE KEY UPDATE settings_id=1;
CREATE TABLE IF NOT EXISTS sc_directory.invites (
    invite_id CHAR(36) PRIMARY KEY,
    code_hash CHAR(64) NOT NULL UNIQUE,
    owner_id CHAR(36),
    redeemed_by CHAR(36),
    FOREIGN KEY (owner_id) REFERENCES sc_directory.accounts(account_id) ON DELETE CASCADE,
    FOREIGN KEY (redeemed_by) REFERENCES sc_directory.accounts(account_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS sc_directory.contacts (
    first_id CHAR(36) NOT NULL,
    second_id CHAR(36) NOT NULL,
    PRIMARY KEY (first_id,second_id),
    FOREIGN KEY (first_id) REFERENCES sc_directory.accounts(account_id) ON DELETE CASCADE,
    FOREIGN KEY (second_id) REFERENCES sc_directory.accounts(account_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS sc_directory.blocks (
    account_id CHAR(36) NOT NULL,
    peer_id CHAR(36) NOT NULL,
    PRIMARY KEY (account_id,peer_id),
    FOREIGN KEY (account_id) REFERENCES sc_directory.accounts(account_id) ON DELETE CASCADE,
    FOREIGN KEY (peer_id) REFERENCES sc_directory.accounts(account_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS sc_directory.email_challenges (
    challenge_id CHAR(36) PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    purpose VARCHAR(8) NOT NULL,
    account_id CHAR(36),
    code_mac VARBINARY(32) NOT NULL,
    expires_ms BIGINT NOT NULL,
    attempts INT NOT NULL,
    UNIQUE (email),
    INDEX challenge_expiry (expires_ms)
);
CREATE TABLE IF NOT EXISTS sc_directory.challenge_throttle (
    bucket_key CHAR(64) PRIMARY KEY,
    expires_ms BIGINT NOT NULL,
    uses_count INT NOT NULL,
    INDEX throttle_expiry (expires_ms)
);
CREATE TABLE IF NOT EXISTS sc_ledger.revocations (
    account_id CHAR(36) NOT NULL,
    device_id CHAR(36) PRIMARY KEY,
    action VARCHAR(16) NOT NULL,
    occurred_ms BIGINT NOT NULL
);
