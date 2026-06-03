package com.ragservice.rag.repository;

import com.ragservice.rag.domain.ConversationParamOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * conversation_param_overrides 레포지토리.
 *
 * Stage 5 (대화별 override) 조회/저장/삭제 — ADR-0005 7단계 우선순위 체인.
 */
public interface ConversationParamOverrideRepository extends JpaRepository<ConversationParamOverride, UUID> {

    /** (conversation_id, user_email) 복합키로 override 조회. */
    Optional<ConversationParamOverride> findByConversationIdAndUserEmail(
            String conversationId, String userEmail);

    /** 특정 대화의 특정 사용자 override 삭제. */
    void deleteByConversationIdAndUserEmail(String conversationId, String userEmail);

    /** 사용자의 모든 대화 override 삭제 (전체 초기화). */
    void deleteByUserEmail(String userEmail);

    /** 사용자의 모든 대화 override 목록 조회. */
    List<ConversationParamOverride> findAllByUserEmail(String userEmail);
}
