-- V10: datasource_config 에 SSH 터널링 컬럼 추가 (DataSourceConfig 엔티티 동기화)
ALTER TABLE datasource_config
    ADD COLUMN IF NOT EXISTS ssh_enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS ssh_host            VARCHAR(255),
    ADD COLUMN IF NOT EXISTS ssh_port            INTEGER          DEFAULT 22,
    ADD COLUMN IF NOT EXISTS ssh_user            VARCHAR(100),
    ADD COLUMN IF NOT EXISTS ssh_private_key_enc TEXT,
    ADD COLUMN IF NOT EXISTS ssh_passphrase_enc  TEXT;
