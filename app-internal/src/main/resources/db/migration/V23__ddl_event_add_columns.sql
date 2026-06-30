ALTER TABLE ddl_events ADD COLUMN datasource_id INTEGER;
ALTER TABLE ddl_events ADD COLUMN whitelist_applied_sql_at TIMESTAMPTZ;
ALTER TABLE ddl_events ADD COLUMN whitelist_applied_rag_at TIMESTAMPTZ;
CREATE INDEX idx_ddl_events_ds_created ON ddl_events (datasource_id, created_at);
