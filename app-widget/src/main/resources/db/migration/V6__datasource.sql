-- datasource_config: 외부 MySQL/MariaDB 연결 정보
CREATE TABLE IF NOT EXISTS datasource_config (
    id           SERIAL PRIMARY KEY,
    name         VARCHAR(100) NOT NULL UNIQUE,
    description  TEXT,
    db_type      VARCHAR(20)  NOT NULL DEFAULT 'mysql',
    host         VARCHAR(255) NOT NULL,
    port         INTEGER      NOT NULL DEFAULT 3306,
    db_name      VARCHAR(100) NOT NULL,
    username     VARCHAR(100) NOT NULL,
    password_enc TEXT         NOT NULL,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ds_rag_table: 동기화 대상 테이블
CREATE TABLE IF NOT EXISTS ds_rag_table (
    id             SERIAL PRIMARY KEY,
    datasource_id  INTEGER      NOT NULL REFERENCES datasource_config(id) ON DELETE CASCADE,
    table_name     VARCHAR(100) NOT NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    last_synced_at TIMESTAMPTZ,
    UNIQUE(datasource_id, table_name)
);

-- ds_sync_job: 동기화 작업 이력
CREATE TABLE IF NOT EXISTS ds_sync_job (
    id            SERIAL PRIMARY KEY,
    datasource_id INTEGER     NOT NULL,
    table_name    VARCHAR(100) NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'pending',
    row_count     INTEGER,
    error_msg     TEXT,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at   TIMESTAMPTZ
);
