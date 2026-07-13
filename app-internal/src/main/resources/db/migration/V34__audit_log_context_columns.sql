-- audit_log 에 위젯 서비스(conversation_logs)와 동일한 문서매칭/차단 지표 컬럼 추가.
-- 어드민 "사용자 통계" 지표 체계 통일 (contextHitRate30d / blockedRate30d / sourceCount).
ALTER TABLE audit_log
    ADD COLUMN IF NOT EXISTS has_context   boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS is_blocked    boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS source_count  integer NOT NULL DEFAULT 0;
