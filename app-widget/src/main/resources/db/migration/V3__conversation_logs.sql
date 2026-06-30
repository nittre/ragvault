CREATE TABLE conversation_logs (
    id            BIGSERIAL PRIMARY KEY,
    session_id    VARCHAR(100)  NOT NULL,
    site_key      VARCHAR(100),
    user_message  TEXT          NOT NULL,
    bot_response  TEXT          NOT NULL,
    is_blocked    BOOLEAN       NOT NULL DEFAULT FALSE,
    has_context   BOOLEAN       NOT NULL DEFAULT TRUE,
    source_count  INT           NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_conv_created_at ON conversation_logs (created_at DESC);
CREATE INDEX idx_conv_site_key   ON conversation_logs (site_key);
