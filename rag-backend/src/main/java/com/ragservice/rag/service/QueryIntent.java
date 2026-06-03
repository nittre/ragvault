package com.ragservice.rag.service;

/**
 * 사용자 질의 의도 분류 결과 — 6경로.
 *
 * 정적 규칙 (LLM 호출 없음, 우선순위 순):
 *   IMAGE     — images 필드 비어있지 않음
 *   FILE      — fileIds 비어있지 않음
 *   URL_FETCH — 메시지에 http(s):// URL 포함
 *
 * LLM 분류 (위 3가지 해당 없을 때):
 *   RAG    — 문서/계약서/매뉴얼 텍스트 답
 *   SQL    — 수치 계산/집계
 *   HYBRID — 수치 + 설명 동시 필요
 *
 * requirements/10-multimodal-files-url.md 섹션 2
 */
public enum QueryIntent {
    RAG, SQL, HYBRID, URL_FETCH, FILE, IMAGE
}
