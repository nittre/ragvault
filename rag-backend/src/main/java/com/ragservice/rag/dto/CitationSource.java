package com.ragservice.rag.dto;

/**
 * RAG 검색 출처 DTO.
 */
public record CitationSource(String title, String source, double score) {}
