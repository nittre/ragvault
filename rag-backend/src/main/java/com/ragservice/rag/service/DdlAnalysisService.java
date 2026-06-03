package com.ragservice.rag.service;

import com.ragservice.rag.domain.DdlEvent;
import com.ragservice.rag.domain.RagTableConfig;
import com.ragservice.rag.dto.DdlAnalysisResult;
import com.ragservice.rag.repository.RagTableConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DDL 이벤트 영향 분석 서비스.
 *
 * 분석 절차:
 * 1. ddlEvent.tableName 으로 rag_table_config 조회 (active 한정)
 * 2. DDL 파싱: ALTER TABLE ... (ADD|DROP|MODIFY|CHANGE) COLUMN col_name 감지
 * 3. 변경된 컬럼명이 content_columns 에 포함되는지 확인
 * 4. riskLevel·영향 컬럼 유무로 requiresResync 결정
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DdlAnalysisService {

    /**
     * ALTER TABLE 구문에서 ADD/DROP/MODIFY/CHANGE COLUMN 뒤의 컬럼명을 추출한다.
     * 예) ALTER TABLE t DROP COLUMN foo, ADD COLUMN bar INT
     *   → [foo, bar]
     */
    private static final Pattern COLUMN_CHANGE_PATTERN = Pattern.compile(
            "(?:ADD|DROP|MODIFY|CHANGE)\\s+(?:COLUMN\\s+)?`?(\\w+)`?",
            Pattern.CASE_INSENSITIVE
    );

    private final RagTableConfigRepository ragTableConfigRepository;

    /**
     * DDL 이벤트로부터 영향 분석 결과를 반환한다.
     *
     * DdlEvent 필드 매핑:
     * - sqlQuery   ← DDL 원문 (ddlStatement 역할)
     * - tableName  ← 대상 테이블
     * - riskLevel  ← HIGH / MEDIUM / LOW
     */
    public DdlAnalysisResult analyze(DdlEvent event) {
        // 1. rag_table_config 에서 해당 테이블 조회 (isActive=true)
        List<RagTableConfig> ragConfigs = ragTableConfigRepository
                .findBySourceTableAndIsActiveTrue(event.getTableName())
                .map(List::of)
                .orElseGet(List::of);

        List<String> affectedRagTables = ragConfigs.stream()
                .map(RagTableConfig::getSourceTable)
                .distinct()
                .toList();

        // 2. DDL 파싱: 변경된 컬럼 추출
        List<String> changedColumns = extractChangedColumns(event.getSqlQuery());

        // 3. content_columns 교집합
        List<String> affectedColumns = ragConfigs.stream()
                .flatMap(c -> Arrays.stream(c.getContentColumns()))
                .map(String::trim)
                .filter(col -> changedColumns.stream().anyMatch(ch -> ch.equalsIgnoreCase(col)))
                .distinct()
                .toList();

        // 4. requiresResync 결정
        //    - HIGH 이면 무조건
        //    - MEDIUM + RAG 대상 테이블 존재 시
        //    - content_columns 에 영향이 있을 시
        boolean isHigh   = "HIGH".equalsIgnoreCase(event.getRiskLevel());
        boolean isMedium = "MEDIUM".equalsIgnoreCase(event.getRiskLevel());
        boolean ragTargetExists = !affectedRagTables.isEmpty();

        boolean requiresResync = !affectedColumns.isEmpty()
                || isHigh
                || (isMedium && ragTargetExists);

        String impactSummary    = buildImpactSummary(affectedRagTables, affectedColumns, event.getRiskLevel());
        String recommendedAction = buildRecommendedAction(event.getRiskLevel(), requiresResync);

        log.debug("DDL analysis event={} table={} risk={} requiresResync={}",
                event.getId(), event.getTableName(), event.getRiskLevel(), requiresResync);

        return new DdlAnalysisResult(
                event.getId(),
                event.getSqlQuery(),
                event.getTableName(),
                event.getRiskLevel(),
                affectedRagTables,
                affectedColumns,
                impactSummary,
                recommendedAction,
                requiresResync
        );
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /**
     * DDL 문자열에서 변경된 컬럼명 목록을 추출한다.
     * DROP TABLE / CREATE TABLE 등 ALTER 가 아닌 경우 빈 리스트 반환.
     */
    List<String> extractChangedColumns(String ddl) {
        if (ddl == null || ddl.isBlank()) return List.of();

        List<String> columns = new ArrayList<>();
        Matcher matcher = COLUMN_CHANGE_PATTERN.matcher(ddl);
        while (matcher.find()) {
            columns.add(matcher.group(1));
        }
        return List.copyOf(columns);
    }

    private String buildImpactSummary(List<String> affectedRagTables,
                                      List<String> affectedColumns,
                                      String riskLevel) {
        if (affectedRagTables.isEmpty()) {
            return "RAG 대상 테이블이 아닙니다. 영향 없음.";
        }
        if (affectedColumns.isEmpty()) {
            return String.format("RAG 대상 테이블(%s)이나 콘텐츠 컬럼에는 영향 없음 (위험도: %s).",
                    String.join(", ", affectedRagTables), riskLevel);
        }
        return String.format(
                "RAG 대상 테이블 %s 의 콘텐츠 컬럼 [%s] 이 변경되었습니다. 재동기화를 검토하세요.",
                String.join(", ", affectedRagTables),
                String.join(", ", affectedColumns)
        );
    }

    private String buildRecommendedAction(String riskLevel, boolean requiresResync) {
        if (!requiresResync) {
            return "자동 처리 가능 — 영향 없음";
        }
        return switch (riskLevel == null ? "" : riskLevel.toUpperCase()) {
            case "HIGH"   -> "즉시 수동 확인 및 재동기화 필요";
            case "MEDIUM" -> "7일 내 재동기화 권장";
            default       -> "재동기화 여부를 관리자가 확인하세요";
        };
    }
}
