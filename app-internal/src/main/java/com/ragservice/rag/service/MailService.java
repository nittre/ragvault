package com.ragservice.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import jakarta.mail.internet.MimeMessage;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 메일 발송 서비스 — Thymeleaf 템플릿 기반.
 *
 * 지원 메일:
 * 1. 계정 발급 (email/account-created)
 * 2. 비밀번호 재설정 (email/password-reset)
 *
 * @Async 비동기 발송으로 응답 지연 방지.
 * SMTP 자격증명은 환경변수로만 주입 (spring.mail.username/password).
 * th:text 로 XSS 방지 (th:utext 사용 금지).
 *
 * ADR-0009: Phase 0 Admin Web UI
 * requirements/07-auth-security.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${spring.mail.username:noreply@ragservice.com}")
    private String fromAddress;

    @Value("${rag.mail.support-email:support@ragservice.com}")
    private String supportEmail;

    @Value("${rag.mail.service-url:https://app.ragservice.com}")
    private String serviceUrl;

    /**
     * 계정 발급 메일 발송 (비동기).
     * API Key는 이 메일 발송 후 다시 조회 불가 — 수신자에게 안내.
     */
    @Async
    public void sendAccountCreated(String toEmail, String userName, String companyName,
                                    String apiKey, String scope, String expiresAt) {
        Context ctx = new Context(Locale.KOREAN);
        ctx.setVariable("userName", userName);
        ctx.setVariable("userEmail", toEmail);
        ctx.setVariable("companyName", companyName);
        ctx.setVariable("apiKey", apiKey);
        ctx.setVariable("scope", scope);
        ctx.setVariable("expiresAt", expiresAt);
        ctx.setVariable("serviceUrl", serviceUrl);
        ctx.setVariable("supportEmail", supportEmail);

        sendHtml(toEmail, "[RAG 서비스] 계정이 발급되었습니다", "email/account-created", ctx);
    }

    /**
     * 비밀번호 재설정 메일 발송 (비동기).
     */
    @Async
    public void sendPasswordReset(String toEmail, String userName, String resetUrl,
                                   int expireMinutes, String requestIp) {
        Context ctx = new Context(Locale.KOREAN);
        ctx.setVariable("userName", userName);
        ctx.setVariable("resetUrl", resetUrl);
        ctx.setVariable("expireMinutes", expireMinutes);
        ctx.setVariable("requestIp", requestIp);
        ctx.setVariable("requestTime",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        ctx.setVariable("supportEmail", supportEmail);

        sendHtml(toEmail, "[RAG 서비스] 비밀번호 재설정 요청", "email/password-reset", ctx);
    }

    private void sendHtml(String to, String subject, String templateName, Context ctx) {
        try {
            String html = templateEngine.process(templateName, ctx);
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(msg);
            log.info("메일 발송 완료: to={} subject={}", to, subject);
        } catch (Exception e) {
            log.error("메일 발송 실패: to={} error={}", to, e.getMessage());
        }
    }
}
