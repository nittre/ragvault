package com.ragservice.rag.repository;

import com.ragservice.rag.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /**
     * PostgreSQL 에서 null LocalDateTime 파라미터의 타입을 추론하지 못하는 문제를
     * nativeQuery + ::timestamp 명시적 캐스팅으로 해결.
     */
    /**
     * native query 주의사항:
     * 1. Pageable sort 가 camelCase 로 추가되면 snake_case 컬럼명 불일치 발생.
     *    → ORDER BY 를 쿼리에 직접 명시하고 Pageable 은 sort 없이 사용한다.
     * 2. null LocalDateTime 파라미터 타입 추론 실패.
     *    → IS NULL 체크도 CAST 를 통해 타입을 명시한다.
     */
    @Query(value = "SELECT * FROM audit_log a WHERE " +
           "(CAST(:userEmail AS text) IS NULL OR a.user_email = :userEmail) AND " +
           "(CAST(:action AS text) IS NULL OR a.action = :action) AND " +
           "(CAST(:from AS timestamp) IS NULL OR a.created_at >= CAST(:from AS timestamp)) AND " +
           "(CAST(:to AS timestamp) IS NULL OR a.created_at <= CAST(:to AS timestamp)) " +
           "ORDER BY a.created_at DESC",
           countQuery = "SELECT count(*) FROM audit_log a WHERE " +
           "(CAST(:userEmail AS text) IS NULL OR a.user_email = :userEmail) AND " +
           "(CAST(:action AS text) IS NULL OR a.action = :action) AND " +
           "(CAST(:from AS timestamp) IS NULL OR a.created_at >= CAST(:from AS timestamp)) AND " +
           "(CAST(:to AS timestamp) IS NULL OR a.created_at <= CAST(:to AS timestamp))",
           nativeQuery = true)
    Page<AuditLog> findFiltered(
            @Param("userEmail") String userEmail,
            @Param("action") String action,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    /**
     * audit_log 는 채팅 라우팅(RAG/SQL_QUERY/FILE_UPLOAD/HYBRID/WEB_SEARCH/OTHER, 레거시 CHAT 포함)뿐 아니라
     * LOGIN, LOGOUT, USER_CREATE 등 사용자 관리, KNOWLEDGE_UPLOAD 등 지식문서 관리 같은
     * 관리자 행위 감사로그도 같은 테이블에 저장한다.
     * 사용자 통계(전체/기간별/일별 추이)는 실제 채팅 질의 건수만 세야 하므로 이 action 목록으로 한정한다.
     */
    String CHAT_ACTIONS = "('RAG','SQL_QUERY','FILE_UPLOAD','HYBRID','WEB_SEARCH','OTHER','CHAT')";

    // 사용자 통계 집계 (위젯 서비스 ConversationLogRepository 와 동일한 지표 체계)
    @Query(value = "SELECT COUNT(*) FROM audit_log WHERE action IN " + CHAT_ACTIONS, nativeQuery = true)
    long countChatQueries();

    @Query(value = "SELECT COUNT(*) FROM audit_log WHERE action IN " + CHAT_ACTIONS +
           " AND created_at >= :from", nativeQuery = true)
    long countChatQueriesSince(@Param("from") LocalDateTime from);

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.createdAt >= :from AND a.hasContext = true")
    long countWithContextSince(@Param("from") LocalDateTime from);

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.createdAt >= :from AND a.isBlocked = true")
    long countBlockedSince(@Param("from") LocalDateTime from);

    @Query(value = "SELECT DATE(created_at) as day, COUNT(*) as cnt " +
           "FROM audit_log " +
           "WHERE created_at >= :from AND action IN " + CHAT_ACTIONS + " " +
           "GROUP BY day ORDER BY day",
           nativeQuery = true)
    List<Object[]> dailyCountsSince(@Param("from") LocalDateTime from);

    /**
     * intent='REJECT' 건수. action 컬럼에는 REJECT 가 OTHER 로 뭉쳐 저장되므로
     * 라우팅 상세에서 '차단'을 OTHER 와 별도 항목으로 분리해 보여주기 위해 intent 로 직접 센다.
     * to 가 null 이면 하한(from)만 적용한다 (findFiltered 와 동일한 null-safe 패턴).
     */
    @Query(value = "SELECT COUNT(*) FROM audit_log a WHERE a.intent = 'REJECT' AND " +
           "a.created_at >= CAST(:from AS timestamp) AND " +
           "(CAST(:to AS timestamp) IS NULL OR a.created_at <= CAST(:to AS timestamp))",
           nativeQuery = true)
    long countRejectedSince(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
