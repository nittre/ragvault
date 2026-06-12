-- ADR-0011: Open WebUI 세션 → 자체 JWT 인증 전환
-- rag_users 에 비밀번호 해시 및 최초 로그인 변경 플래그 추가.
-- 기존 사용자의 password_hash 는 앱 기동 시 RagUserBootstrapRunner 가
-- BOOTSTRAP_INITIAL_PASSWORD 를 BCrypt 해시하여 일괄 설정한다.
ALTER TABLE rag_users
    ADD COLUMN IF NOT EXISTS password_hash            VARCHAR(255),
    ADD COLUMN IF NOT EXISTS password_change_required BOOLEAN NOT NULL DEFAULT false;
