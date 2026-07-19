package com.ragservice.rag.service;

import org.springframework.stereotype.Component;

/**
 * 챗 라우팅 intent → audit_log.action 매핑.
 *
 * audit_log.action 은 최상위 라우팅 intent를 그대로 반영한다 (AdminUsageStatsController.routing).
 * HYBRID/WEB_SEARCH를 CHAT으로 뭉개면 사용량 통계에서 이 경로들이 사라지므로 각자 라벨을 부여한다.
 * IMAGE/IMAGE_RAG/URL_FETCH/REJECT는 빈도가 낮아 OTHER로 묶는다.
 *
 * 주의: AdminUsageStatsController 가 여기서 반환하는 문자열("SQL_QUERY"/"FILE_UPLOAD"/"HYBRID"/
 * "WEB_SEARCH")로 audit_log.action 을 직접 쿼리한다. 값을 바꾸면 사용량 통계가 조용히 0을 반환한다.
 */
@Component
public class ChatAuditActionResolver {

    public String resolve(String intent) {
        return switch (intent) {
            case "SQL" -> "SQL_QUERY";
            case "FILE" -> "FILE_UPLOAD";
            case "HYBRID" -> "HYBRID";
            case "WEB_SEARCH" -> "WEB_SEARCH";
            case "RAG" -> "RAG";
            default -> "OTHER";
        };
    }
}
