package com.ragservice.rag.runner;

import com.ragservice.rag.domain.RagRole;
import com.ragservice.rag.domain.RagUser;
import com.ragservice.rag.repository.RagUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 최초 배포 시 SUPER_ADMIN Bootstrap.
 *
 * BOOTSTRAP_SUPER_ADMIN_EMAIL 환경변수가 설정돼 있고,
 * rag_users 테이블에 해당 이메일로 SUPER_ADMIN 이 없으면 자동으로 등록한다.
 *
 * - 멱등성 보장: 이미 존재하면 아무것도 하지 않는다.
 * - 재시작 시 중복 생성 없음.
 * - Open WebUI 에 해당 이메일로 계정이 존재해야 실제 로그인 가능.
 *
 * PII 원칙: 로그에 이메일 직접 노출 금지 (마스킹 처리).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagUserBootstrapRunner implements ApplicationRunner {

    private final RagUserRepository ragUserRepository;

    @Value("${rag.bootstrap.super-admin-email:#{null}}")
    private String bootstrapSuperAdminEmail;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
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

        ragUserRepository.save(superAdmin);
        log.info("Bootstrap SUPER_ADMIN 등록 완료 (hash={})", maskEmail(bootstrapSuperAdminEmail));
    }

    private String maskEmail(String email) {
        if (email == null) return "***";
        int at = email.indexOf('@');
        if (at <= 2) return "***";
        return email.substring(0, 2) + "***" + email.substring(at);
    }
}
