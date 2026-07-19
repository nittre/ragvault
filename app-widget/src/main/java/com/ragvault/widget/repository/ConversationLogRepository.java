package com.ragvault.widget.repository;

import com.ragvault.widget.domain.ConversationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ConversationLogRepository extends JpaRepository<ConversationLog, Long> {

    // 페이징 조회 (siteKey 필터, 부분/대소문자 무시 검색 — 사이트키는 사용자가 직접 타이핑하는 랜덤 문자열이라 완전 일치는 실사용에서 매칭이 거의 안 됨)
    Page<ConversationLog> findBySiteKeyContainingIgnoreCase(String siteKey, Pageable pageable);

    // 기간별 건수 (통계용)
    @Query("SELECT COUNT(c) FROM ConversationLog c WHERE c.createdAt >= :from")
    long countSince(@Param("from") Instant from);

    @Query("SELECT COUNT(c) FROM ConversationLog c WHERE c.createdAt >= :from AND c.hasContext = true")
    long countWithContextSince(@Param("from") Instant from);

    @Query("SELECT COUNT(c) FROM ConversationLog c WHERE c.createdAt >= :from AND c.isBlocked = true")
    long countBlockedSince(@Param("from") Instant from);

    // 일별 집계 (최근 N일)
    @Query(value = """
            SELECT DATE(created_at AT TIME ZONE 'UTC') as day, COUNT(*) as cnt
            FROM conversation_logs
            WHERE created_at >= :from
            GROUP BY day ORDER BY day
            """, nativeQuery = true)
    List<Object[]> dailyCountsSince(@Param("from") Instant from);

    // 라우팅 분류별 집계 (챗 서비스 어드민의 '라우팅 상세'와 동일한 체계)
    @Query(value = """
            SELECT action, COUNT(*) as cnt
            FROM conversation_logs
            WHERE created_at >= :from
            GROUP BY action
            """, nativeQuery = true)
    List<Object[]> actionCountsSince(@Param("from") Instant from);
}
