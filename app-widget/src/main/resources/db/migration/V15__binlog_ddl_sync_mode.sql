-- ============================================================================
-- 자동 동기화(binlog 실시간 동기화) 지원 — 챗 서비스(app-internal)와 동일 기능 이식.
--
-- app-internal의 V3__m2_sync_schema.sql / V17__datasource_config.sql /
-- V22__sync_mode_config.sql / V23__ddl_event_add_columns.sql 을 참고해
-- 위젯 컨벤션(TIMESTAMPTZ)에 맞춰 한 마이그레이션으로 통합.
--
-- binlog_position.id는 챗 서비스와 달리 처음부터 SERIAL로 만든다 — 챗 서비스는
-- "CHECK (id=1)" 싱글톤으로 시작했다가 나중에 멀티 데이터소스로 확장하며
-- ID 생성 버그가 있었음(V40__binlog_position_multi_datasource_id_fix.sql 참고).
-- 위젯은 처음부터 데이터소스별 독립 행을 전제로 하므로 그 문제가 없다.
-- ============================================================================

-- ── binlog 위치 추적 (데이터소스별 GTID/파일:오프셋) ─────────────────────────
CREATE TABLE IF NOT EXISTS binlog_position (
    id            SERIAL PRIMARY KEY,
    datasource_id INTEGER     REFERENCES datasource_config(id) ON DELETE CASCADE,
    gtid_set      TEXT        NOT NULL DEFAULT '',
    last_event_at TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_binlog_pos_datasource
    ON binlog_position (datasource_id) WHERE datasource_id IS NOT NULL;

-- ── binlog 이벤트 실패 추적 + 재처리 ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS binlog_events (
    id            BIGSERIAL PRIMARY KEY,
    event_type    VARCHAR(50),
    table_name    VARCHAR(100),
    source_id     VARCHAR(200),
    row_count     INTEGER,
    processed     BOOLEAN     NOT NULL DEFAULT FALSE,
    attempt       INTEGER     NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_binlog_events_unprocessed
    ON binlog_events (processed, created_at) WHERE NOT processed;

-- ── DDL 이벤트 (위험도 분류 + 화이트리스트 자동반영 하이브리드 처리) ─────────
CREATE TABLE IF NOT EXISTS ddl_events (
    id                        BIGSERIAL PRIMARY KEY,
    sql_query                 TEXT        NOT NULL,
    table_name                VARCHAR(100),
    event_type                VARCHAR(50),
    risk_level                VARCHAR(20),
    auto_apply_at             TIMESTAMPTZ,
    processed_at              TIMESTAMPTZ,
    processed_by              VARCHAR(200),
    action_taken              VARCHAR(50),
    notes                     TEXT,
    datasource_id             INTEGER,
    whitelist_applied_sql_at  TIMESTAMPTZ,
    whitelist_applied_rag_at  TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ddl_pending ON ddl_events (processed_at) WHERE processed_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_ddl_events_ds_created ON ddl_events (datasource_id, created_at);

-- ── 데이터소스별 자동 동기화(sync mode) 설정 ─────────────────────────────────
CREATE TABLE IF NOT EXISTS sync_mode_config (
    id                SERIAL PRIMARY KEY,
    datasource_id     INTEGER     NOT NULL,
    table_type        VARCHAR(10) NOT NULL CHECK (table_type IN ('sql', 'rag')),
    auto_sync_enabled BOOLEAN     NOT NULL DEFAULT FALSE,
    disabled_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (datasource_id, table_type)
);

-- ── 동기화 작업 단위 (BinlogSyncService 스케줄/수동 트리거 추적) ─────────────
CREATE TABLE IF NOT EXISTS sync_jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trigger_type    VARCHAR(20) NOT NULL,
    triggered_by    VARCHAR(200),
    started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ,
    status          VARCHAR(20) NOT NULL DEFAULT 'running',
    records_total   INTEGER     NOT NULL DEFAULT 0,
    records_success INTEGER     NOT NULL DEFAULT 0,
    records_failed  INTEGER     NOT NULL DEFAULT 0,
    error_message   TEXT
);
