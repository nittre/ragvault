package com.ragvault.widget.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragvault.core.domain.RagRole;
import com.ragvault.core.domain.RagUser;
import com.ragvault.core.repository.RagUserRepository;
import com.ragvault.core.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * JWT 인증 통합 테스트.
 *
 * 시나리오:
 *   a) 미인증 /admin/me → 401
 *   b) /api/v1/auth/login (슈퍼어드민) → 200 + Set-Cookie: token=...
 *   c) 쿠키로 /admin/me → 200 + email / role
 *   d) 잘못된 site-key 로 /v1/widget/chat → 401 (SiteKeyFilter 동작 확인)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtAuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RagUserRepository ragUserRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private static final String ADMIN_EMAIL = "jwt-test-admin@widget.local";
    private static final String ADMIN_PASSWORD = "test-password-jwt";

    @BeforeEach
    void setUp() {
        ragUserRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(u -> u.getEmail().equals(ADMIN_EMAIL))
                .forEach(ragUserRepository::delete);

        RagUser admin = new RagUser();
        admin.setEmail(ADMIN_EMAIL);
        admin.setName("Test Admin");
        admin.setRole(RagRole.SUPER_ADMIN);
        admin.setActive(true);
        admin.setPasswordHash(passwordEncoder.encode(ADMIN_PASSWORD));
        admin.setPasswordChangeRequired(false);
        admin.setCreatedBy("test");
        admin.setCreatedAt(Instant.now());
        admin.setUpdatedAt(Instant.now());
        ragUserRepository.save(admin);
    }

    @Test
    void unauthenticated_adminMe_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_withValidCredentials_returnsCookieAndResponse() throws Exception {
        Map<String, String> body = Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.role").value("SUPER_ADMIN"))
                .andExpect(header().exists("Set-Cookie"));
    }

    @Test
    void login_withValidCookie_adminMe_returns200() throws Exception {
        // 직접 토큰 생성해서 쿠키로 전달
        String token = jwtService.generateToken(ADMIN_EMAIL, RagRole.SUPER_ADMIN);

        mockMvc.perform(get("/api/admin/me")
                        .cookie(new jakarta.servlet.http.Cookie("token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.role").value("SUPER_ADMIN"));
    }

    @Test
    void login_withWrongPassword_returns401() throws Exception {
        Map<String, String> body = Map.of("email", ADMIN_EMAIL, "password", "wrong-password");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void widgetChat_withoutSiteKey_returns401() throws Exception {
        // SiteKeyFilter 가 /v1/widget/** 에 동작하는지 확인
        mockMvc.perform(post("/v1/widget/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"test\"}]}"))
                .andExpect(status().isUnauthorized());
    }
}
