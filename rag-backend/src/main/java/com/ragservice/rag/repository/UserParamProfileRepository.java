package com.ragservice.rag.repository;

import com.ragservice.rag.domain.UserParamProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * user_param_profiles 레포지토리.
 *
 * Stage 4 (사용자 프로필) 조회/저장/삭제 — ADR-0005 7단계 우선순위 체인.
 */
public interface UserParamProfileRepository extends JpaRepository<UserParamProfile, UUID> {

    /** 사용자 이메일로 프로필 조회. 없으면 Optional.empty(). */
    Optional<UserParamProfile> findByUserEmail(String userEmail);

    /** 사용자 프로필 삭제 (프로필 초기화). */
    void deleteByUserEmail(String userEmail);
}
