package com.ragvault.widget.runner;

import com.ragvault.core.domain.RagRole;
import com.ragvault.core.domain.RagUser;
import com.ragvault.core.repository.RagUserRepository;
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
 * 최초 기동 시 SUPER_ADMIN 계정 bootstrap.
 *
 * 환경변수:
 *   BOOTSTRAP_ADMIN_EMAIL    — 슈퍼어드민 이메일 (기본: admin@widget.local)
 *   BOOTSTRAP_ADMIN_PASSWORD — 초기 비밀번호 (기본: changeme123)
 *
 * 이미 존재하면 멱등 처리(건너뜀).
 * password_hash 가 null 인 사용자 전체에 초기 비밀번호 일괄 설정.
 *
 * PII 원칙: 로그에 이메일 직접 노출 금지.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagUserBootstrapRunner implements ApplicationRunner {

    private final RagUserRepository ragUserRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${widget.bootstrap.admin-email:admin@widget.local}")
    private String bootstrapAdminEmail;

    @Value("${widget.bootstrap.admin-password:changeme123}")
    private String bootstrapAdminPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        bootstrapSuperAdmin();
        initMissingPasswords();
    }

    private void bootstrapSuperAdmin() {
        if (ragUserRepository.existsByEmail(bootstrapAdminEmail)) {
            log.info("Bootstrap SUPER_ADMIN 이미 존재 (hash={}) — 건너뜀",
                    maskEmail(bootstrapAdminEmail));
            return;
        }

        RagUser superAdmin = new RagUser();
        superAdmin.setEmail(bootstrapAdminEmail);
        superAdmin.setName("Super Admin");
        superAdmin.setRole(RagRole.SUPER_ADMIN);
        superAdmin.setActive(true);
        superAdmin.setCreatedBy("bootstrap");
        superAdmin.setCreatedAt(Instant.now());
        superAdmin.setUpdatedAt(Instant.now());

        ragUserRepository.save(superAdmin);
        log.info("Bootstrap SUPER_ADMIN 등록 완료 (hash={})", maskEmail(bootstrapAdminEmail));
    }

    private void initMissingPasswords() {
        if (bootstrapAdminPassword == null || bootstrapAdminPassword.isBlank()) {
            log.debug("widget.bootstrap.admin-password 미설정 — 비밀번호 초기화 건너뜀");
            return;
        }

        List<RagUser> noPassword = ragUserRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(u -> u.getPasswordHash() == null)
                .toList();

        if (noPassword.isEmpty()) {
            log.debug("비밀번호 미설정 사용자 없음 — 초기화 건너뜀");
            return;
        }

        String hashed = passwordEncoder.encode(bootstrapAdminPassword);
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
