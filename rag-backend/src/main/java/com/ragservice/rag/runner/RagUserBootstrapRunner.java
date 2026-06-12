package com.ragservice.rag.runner;

import com.ragservice.rag.domain.RagRole;
import com.ragservice.rag.domain.RagUser;
import com.ragservice.rag.repository.RagUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 최초 배포 시 SUPER_ADMIN Bootstrap + 초기 비밀번호 일괄 설정.
 *
 * 1. BOOTSTRAP_SUPER_ADMIN_EMAIL 로 SUPER_ADMIN 생성 (없는 경우).
 * 2. BOOTSTRAP_INITIAL_PASSWORD 로 password_hash 가 null 인 모든 사용자에게
 *    BCrypt 해시를 일괄 설정하고 password_change_required = true 로 표시.
 *    → ADR-0011: Open WebUI 제거 후 기존 사용자들의 최초 로그인 지원.
 *
 * PII 원칙: 로그에 이메일 직접 노출 금지 (마스킹 처리).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagUserBootstrapRunner implements ApplicationRunner {

    private final RagUserRepository ragUserRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${rag.bootstrap.super-admin-email:#{null}}")
    private String bootstrapSuperAdminEmail;

    @Value("${rag.bootstrap.initial-password:#{null}}")
    private String bootstrapInitialPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        bootstrapSuperAdmin();
        initMissingPasswords();
    }

    private void bootstrapSuperAdmin() {
        if (bootstrapSuperAdminEmail == null || bootstrapSuperAdminEmail.isBlank()) {
            log.debug("rag.bootstrap.super-admin-email 미설정 — SUPER_ADMIN bootstrap 건너뜀");
            return;
        }

        if (ragUserRepository.existsByEmail(bootstrapSuperAdminEmail)) {
            log.info("Bootstrap SUPER_ADMIN 이미 존재 (hash={}) — 건너뜀",
                    maskEmail(bootstrapSuperAdminEmail));
            return;
        }

        RagUser superAdmin = new RagUser();
        superAdmin.setEmail(bootstrapSuperAdminEmail);
        superAdmin.setName("Super Admin");
        superAdmin.setRole(RagRole.SUPER_ADMIN);
        superAdmin.setActive(true);
        superAdmin.setCreatedBy("bootstrap");
        superAdmin.setCreatedAt(Instant.now());
        superAdmin.setUpdatedAt(Instant.now());
        // 초기 비밀번호는 아래 initMissingPasswords() 에서 일괄 처리

        ragUserRepository.save(superAdmin);
        log.info("Bootstrap SUPER_ADMIN 등록 완료 (hash={})", maskEmail(bootstrapSuperAdminEmail));
    }

    private void initMissingPasswords() {
        if (bootstrapInitialPassword == null || bootstrapInitialPassword.isBlank()) {
            log.debug("rag.bootstrap.initial-password 미설정 — 비밀번호 초기화 건너뜀");
            return;
        }

        List<RagUser> noPassword = ragUserRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(u -> u.getPasswordHash() == null)
                .toList();

        if (noPassword.isEmpty()) {
            log.debug("비밀번호 미설정 사용자 없음 — 초기화 건너뜀");
            return;
        }

        String hashed = passwordEncoder.encode(bootstrapInitialPassword);
        for (RagUser user : noPassword) {
            user.setPasswordHash(hashed);
            user.setPasswordChangeRequired(true);
            user.setUpdatedAt(Instant.now());
            ragUserRepository.save(user);
        }
        log.info("초기 비밀번호 일괄 설정 완료 — {}명", noPassword.size());
    }

    private String maskEmail(String email) {
        if (email == null) return "***";
        int at = email.indexOf('@');
        if (at <= 2) return "***";
        return email.substring(0, 2) + "***" + email.substring(at);
    }
}
