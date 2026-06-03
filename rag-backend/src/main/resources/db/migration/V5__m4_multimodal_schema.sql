-- M4: 첨부파일 처리 결과 저장 (TTL 24h)
CREATE TABLE file_processing (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_email      VARCHAR(200) NOT NULL,
    s3_key          VARCHAR(500) NOT NULL,
    original_name   VARCHAR(500) NOT NULL,
    mime_type       VARCHAR(100),
    size_bytes      BIGINT NOT NULL,
    extracted_text  TEXT NOT NULL,
    token_count     INT NOT NULL DEFAULT 0,
    tokenizer_model VARCHAR(100) NOT NULL DEFAULT 'qwen2.5:14b-instruct-q4_K_M',
    image_count     INT DEFAULT 0,
    ocr_image_count INT DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'done',
    error_message   TEXT,
    created_at      TIMESTAMP DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL
);

CREATE INDEX idx_file_processing_user    ON file_processing (user_email, created_at DESC);
CREATE INDEX idx_file_processing_expires ON file_processing (expires_at);
