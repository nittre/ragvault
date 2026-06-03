package com.ragservice.rag.dto;

import java.util.List;

/**
 * DDL 이벤트 영향 분석 결과 DTO.
 *
 * riskLevel 별 권장 액션:
 * - HIGH  : 즉시 수동 확인 및 재동기화 필요
 * - MEDIUM: 7일 내 재동기화 권장
 * - LOW   : 자동 처리 가능 — 영향 없음
 */
public record DdlAnalysisResult(
        Long ddlEventId,
        String ddlStatement,
        String tableName,
        String riskLevel,
        List<String> affectedRagTables,
        List<String> affectedColumns,
        String impactSummary,
        String recommendedAction,
        boolean requiresResync
) {}
