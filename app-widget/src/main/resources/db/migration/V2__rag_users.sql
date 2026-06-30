-- V2: widget-backend admin 사용자 테이블
-- rag_users: JWT 인증 기반 운영자 계정 관리 (ADR-0011 동일 구조)

CREATE TABLE IF NOT EXISTS rag_users (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email                    VARCHAR(255) NOT NULL UNIQUE,
    name                     VARCHAR(255),
    role                     VARCHAR(20)  NOT NULL CHECK (role IN ('SUPER_ADMIN','ADMIN','USER')),
    active                   BOOLEAN      NOT NULL DEFAULT true,
    password_hash            VARCHAR(255),
    password_change_required BOOLEAN      NOT NULL DEFAULT false,
    created_by               VARCHAR(255),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rag_users_email ON rag_users(email);
