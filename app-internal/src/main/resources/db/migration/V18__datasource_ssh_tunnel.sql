-- SSH 터널 지원 컬럼 추가 (Bastion EC2 + PEM 키 인증)
ALTER TABLE datasource_config
    ADD COLUMN ssh_enabled          BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN ssh_host             VARCHAR(255),
    ADD COLUMN ssh_port             INT                  DEFAULT 22,
    ADD COLUMN ssh_user             VARCHAR(100),
    ADD COLUMN ssh_private_key_enc  TEXT,
    ADD COLUMN ssh_passphrase_enc   TEXT;
