package com.ragvault.core.service;

/**
 * 사용자 질의 의도 분류 결과 — 8경로.
 *
 * 정적 규칙 (Stage 1):
 *   IMAGE     — images 있음 + 텍스트가 순수 이미지 분석 요청
 *   IMAGE_RAG — images 있음 + 텍스트가 RAG/SQL/HYBRID/WEB 검색도 요구 (2-Phase 파이프라인)
 *   FILE      — fileIds 비어있지 않음
 *   URL_FETCH — 메시지에 http(s):// URL 포함
 *
 * LLM 분류 (위 해당 없을 때):
 *   RAG        — 문서/계약서/매뉴얼 텍스트 답
 *   SQL        — 수치 계산/집계
 *   HYBRID     — 수치 + 설명 동시 필요
 *   WEB_SEARCH — 내부 데이터에 없는 최신 정보·일반 지식 (rag.web-search.enabled=true일 때)
 *   REJECT     — 프롬프트 인젝션·시스템 조작·명백한 악용 (두뇌 가드레일, 보수적). SQL 생성 전 차단.
 *
 * requirements/10-multimodal-files-url.md 섹션 2
 */
public enum QueryIntent {
    RAG, SQL, HYBRID, URL_FETCH, FILE, IMAGE, IMAGE_RAG, WEB_SEARCH, REJECT
}
