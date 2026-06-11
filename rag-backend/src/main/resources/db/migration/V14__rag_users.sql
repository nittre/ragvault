CREATE TABLE rag_users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) NOT NULL UNIQUE,
    name        VARCHAR(255),
    role        VARCHAR(20)  NOT NULL CHECK (role IN ('SUPER_ADMIN','ADMIN','USER')),
    active      BOOLEAN      NOT NULL DEFAULT true,
    created_by  VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_rag_users_email ON rag_users(email);
