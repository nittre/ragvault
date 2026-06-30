package com.ragservice.rag.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * business_knowledge 테이블 엔티티 — 데이터소스 단위 백과사전(비즈니스 지식).
 *
 * 레코드 단위 구조: title(도메인 설명) + knowledge_role(rule|measure) + content(본문) + pinned.
 * - knowledge_role=rule : content 는 규칙·정의(자연어).
 * - knowledge_role=measure : content 는 도메인 도출 쿼리(SQL/자연어 하이브리드).
 * - pinned=true : 매 SQL 생성마다 항상 주입(메모리 캐시), false : 질문 임베딩 동적 검색.
 * embedding(vector(1024))은 JPA 직접 매핑 불가 — native query 에서만 처리 (LL-0006).
 *
 * @see com.ragvault.core.domain.DocumentChunk 동일 패턴
 */
@Entity
@Table(name = "business_knowledge")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BusinessKnowledge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "datasource_id", nullable = false)
    private Integer datasourceId;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "knowledge_role", nullable = false, length = 20)
    private String knowledgeRole;   // 'rule' | 'measure'

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "pinned", nullable = false)
    private boolean pinned;

    // embedding(vector(1024)) — JPA 매핑 제외, native query 에서 처리

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
