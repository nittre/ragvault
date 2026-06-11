package com.ragservice.rag.service;

import com.ragservice.rag.domain.RagRole;
import com.ragservice.rag.domain.RagUser;
import com.ragservice.rag.repository.RagUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.springframework.http.HttpStatus.*;

/**
 * rag_users 테이블 기반 사용자 관리 서비스.
 *
 * PII 원칙: 로그에 email 을 직접 노출하지 않고 hash 또는 마스킹 처리.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagUserService {

    private final RagUserRepository ragUserRepository;

    /**
     * 사용자 생성.
     * 중복 이메일 → 409 CONFLICT.
     */
    @Transactional
    public RagUser createUser(String email, String name, RagRole role, String createdBy) {
        if (ragUserRepository.existsByEmail(email)) {
            log.warn("사용자 생성 실패 — 이미 존재하는 이메일 (hash={})", maskEmail(email));
            throw new ResponseStatusException(CONFLICT, "이미 존재하는 이메일입니다.");
        }

        RagUser user = new RagUser();
        user.setEmail(email);
        user.setName(name);
        user.setRole(role);
        user.setActive(true);
        user.setCreatedBy(createdBy);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());

        RagUser saved = ragUserRepository.save(user);
        log.info("사용자 생성 완료 (hash={}, role={})", maskEmail(email), role);
        return saved;
    }

    /**
     * 사용자 수정.
     * 없으면 → 404 NOT_FOUND.
     */
    @Transactional
    public RagUser updateUser(String email, String name, RagRole role, boolean active, String updatedBy) {
        RagUser user = getRequiredByEmail(email);
        user.setName(name);
        user.setRole(role);
        user.setActive(active);
        user.setUpdatedAt(Instant.now());

        RagUser saved = ragUserRepository.save(user);
        log.info("사용자 수정 완료 (hash={}, role={}, active={}, updatedBy={})",
                maskEmail(email), role, active, maskEmail(updatedBy));
        return saved;
    }

    /**
     * 사용자 삭제 (논리 삭제가 아닌 물리 삭제).
     * 없으면 → 404 NOT_FOUND.
     * SUPER_ADMIN 이 1명 남은 경우 삭제 방지.
     */
    @Transactional
    public void deleteUser(String email) {
        RagUser user = getRequiredByEmail(email);

        // SUPER_ADMIN 마지막 1명 삭제 방지
        if (user.getRole() == RagRole.SUPER_ADMIN) {
            long superAdminCount = ragUserRepository.findAllByOrderByCreatedAtDesc().stream()
                    .filter(u -> u.getRole() == RagRole.SUPER_ADMIN && u.isActive())
                    .count();
            if (superAdminCount <= 1) {
                throw new ResponseStatusException(CONFLICT,
                        "마지막 SUPER_ADMIN 은 삭제할 수 없습니다.");
            }
        }

        ragUserRepository.delete(user);
        log.info("사용자 삭제 완료 (hash={})", maskEmail(email));
    }

    /**
     * 전체 사용자 목록 (active 포함).
     */
    @Transactional(readOnly = true)
    public List<RagUser> listUsers() {
        return ragUserRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 이메일로 활성 사용자 조회. Optional 반환.
     */
    @Transactional(readOnly = true)
    public Optional<RagUser> findByEmail(String email) {
        return ragUserRepository.findByEmailAndActiveTrue(email);
    }

    /**
     * 이메일로 사용자 조회. 없으면 404 던짐.
     */
    @Transactional(readOnly = true)
    public RagUser getRequiredByEmail(String email) {
        return ragUserRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(u -> u.getEmail().equals(email))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "사용자를 찾을 수 없습니다."));
    }

    /**
     * PII 원칙: 이메일 로그 마스킹.
     * ex) user@example.com → us***@example.com
     */
    private String maskEmail(String email) {
        if (email == null) return "***";
        int at = email.indexOf('@');
        if (at <= 2) return "***";
        return email.substring(0, 2) + "***" + email.substring(at);
    }
}
