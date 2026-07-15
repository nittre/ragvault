-- document_chunks에 datasource_id 격리 추가.
--
-- 배경: findSimilarChunks()가 datasource_id 없이 전체 document_chunks를 대상으로
-- 검색했고, UPSERT 유니크 키(uq_chunk_source)도 데이터소스 구분이 없었다. 한 고객사가
-- 데이터소스를 여러 개 등록하고 그중 두 개 이상이 동일한 이름의 테이블을 RAG 대상으로
-- 쓰면, 검색 결과가 섞이거나 청크가 서로 덮어써질 수 있었다. 애플리케이션 코드는 이미
-- datasource_id를 채우고 걸러내도록 수정됨 — 이 마이그레이션은 그 스키마 기반을 만든다.

ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS datasource_id INTEGER;

-- 백필 1: DsSyncService가 만든 source_table은 "ds_{datasourceId}_{tableName}" 형식으로
-- datasourceId를 이미 이름에 포함하고 있어 모호함 없이 바로 파싱 가능하다.
UPDATE document_chunks
SET datasource_id = split_part(source_table, '_', 2)::INTEGER
WHERE datasource_id IS NULL
  AND source_table ~ '^ds_[0-9]+_';

-- 백필 2: 그 외(주로 BinlogSyncService/ChunkingService 경로)는 rag_table_config에서
-- source_table명이 정확히 하나의 datasource_id에만 매칭되는 경우에만 채운다. 동일
-- 테이블명이 이미 여러 데이터소스에 걸쳐 등록되어 있다면(=이미 충돌/오염 가능성이 있는
-- 데이터) 자동으로 배정하지 않고 NULL로 남긴다 — 운영진이 직접 확인 후 재임베딩(전체
-- 리싱크)하는 걸 권장한다. "internal_knowledge_doc"/"knowledge_doc" 같은 전역 지식문서
-- 테이블은 원래 특정 데이터소스에 속하지 않으므로 매칭 없이 NULL로 남는 게 맞다.
UPDATE document_chunks dc
SET datasource_id = sub.datasource_id
FROM (
    SELECT source_table, MIN(datasource_id) AS datasource_id
    FROM rag_table_config
    GROUP BY source_table
    HAVING COUNT(DISTINCT datasource_id) = 1
) sub
WHERE dc.source_table = sub.source_table
  AND dc.datasource_id IS NULL;

ALTER TABLE document_chunks DROP CONSTRAINT IF EXISTS uq_chunk_source;

ALTER TABLE document_chunks
    ADD CONSTRAINT uq_chunk_source
    UNIQUE (datasource_id, source_table, source_id, chunk_index, embedding_model);

CREATE INDEX IF NOT EXISTS idx_document_chunks_datasource ON document_chunks (datasource_id);
