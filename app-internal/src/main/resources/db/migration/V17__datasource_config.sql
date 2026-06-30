-- 데이터소스 등록 테이블
CREATE TABLE datasource_config (
    id            SERIAL PRIMARY KEY,
    name          VARCHAR(100) NOT NULL UNIQUE,
    description   TEXT,
    db_type       VARCHAR(20) NOT NULL DEFAULT 'mysql'
                    CHECK (db_type IN ('mysql', 'mariadb')),
    host          VARCHAR(255) NOT NULL,
    port          INT NOT NULL DEFAULT 3306,
    db_name       VARCHAR(100) NOT NULL,
    username      VARCHAR(100) NOT NULL,
    password_enc  TEXT NOT NULL,
    is_active     BOOLEAN DEFAULT true,
    created_at    TIMESTAMP DEFAULT NOW(),
    updated_at    TIMESTAMP DEFAULT NOW()
);

ALTER TABLE sql_table_config
    ADD COLUMN datasource_id INT REFERENCES datasource_config(id) ON DELETE SET NULL;

ALTER TABLE rag_table_config
    ADD COLUMN datasource_id INT REFERENCES datasource_config(id) ON DELETE SET NULL;

ALTER TABLE binlog_position
    ADD COLUMN datasource_id INT REFERENCES datasource_config(id) ON DELETE CASCADE;

CREATE UNIQUE INDEX idx_binlog_pos_datasource
    ON binlog_position (datasource_id)
    WHERE datasource_id IS NOT NULL;
