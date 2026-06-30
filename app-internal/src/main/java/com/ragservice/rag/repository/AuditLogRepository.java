package com.ragservice.rag.repository;

import com.ragservice.rag.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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
}
