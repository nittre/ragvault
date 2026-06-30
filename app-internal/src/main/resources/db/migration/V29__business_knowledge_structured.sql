-- 백과사전 구조화: 자유 텍스트 → 레코드 단위 (title / knowledge_role / content / pinned).
-- knowledge_role: 'rule'(규칙·정의) | 'measure'(도메인 도출 쿼리, SQL/자연어 하이브리드).
-- pinned: true면 메모리 캐시 + 매 SQL 생성마다 항상 주입, false면 질문 임베딩 동적 검색.
-- 기존 자유 텍스트 행 리셋 허용(Phase 0, 데이터 미미).

DELETE FROM business_knowledge;

ALTER TABLE business_knowledge RENAME COLUMN is_fixed  TO pinned;   -- 의미 명확화
ALTER TABLE business_knowledge RENAME COLUMN rule_text TO content;  -- 본문 단일 컬럼 (rule=규칙텍스트, measure=쿼리)

ALTER TABLE business_knowledge
    ADD COLUMN knowledge_role VARCHAR(20) NOT NULL DEFAULT 'rule';  -- 'rule' | 'measure'
