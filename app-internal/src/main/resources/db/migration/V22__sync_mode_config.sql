CREATE TABLE sync_mode_config (
    id          SERIAL PRIMARY KEY,
    datasource_id INTEGER NOT NULL,
    table_type  VARCHAR(10) NOT NULL CHECK (table_type IN ('sql', 'rag')),
    auto_sync_enabled BOOLEAN NOT NULL DEFAULT false,
    disabled_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (datasource_id, table_type)
);
