package com.ragservice.rag.controller;

import com.ragvault.core.domain.RagUser;
import com.ragvault.core.dto.LoginRequest;
import com.ragvault.core.dto.LoginResponse;
import com.ragvault.core.service.JwtService;
import com.ragvault.core.service.RagUserService;
import com.ragservice.rag.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * ADR-0011: 자체 JWT 로그인 / 로그아웃 / 비밀번호 변경 엔드포인트.
 *
 * POST /api/v1/auth/login          — 이메일+비밀번호 → httpOnly 쿠키 token 발급
 * POST /api/v1/auth/logout         — 쿠키 만료(maxAge=0)
 * POST /api/v1/auth/change-password — 비밀번호 변경 (인증 필요)
 *
 * SecurityConfig 에서 /api/v1/auth/** permitAll.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class JwtAuthController {

    private final RagUserService ragUserService;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @RequestBody LoginRequest req,
            HttpServletRequest request,
            HttpServletResponse response) {

        RagUser user = ragUserService.findByEmail(req.email())
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED,
                        "이메일 또는 비밀번호가 올바르지 않습니다."));

        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(UNAUTHORIZED,
                    "이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        String token = jwtService.generateToken(user.getEmail(), user.getRole());
        setTokenCookie(response, token, jwtService.expirySeconds());

        auditLogService.log(user.getEmail(), "LOGIN", null, null, request.getRemoteAddr(), null);

        return ResponseEntity.ok(new LoginResponse(
                user.getEmail(),
                user.getRole().name(),
                Instant.now().plusSeconds(jwtService.expirySeconds()),
                user.isPasswordChangeRequired()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) {
        String email = authentication != null ? authentication.getName() : "unknown";
        setTokenCookie(response, "", 0);
        auditLogService.log(email, "LOGOUT", null, null, request.getRemoteAddr(), null);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @RequestBody ChangePasswordRequest req,
            org.springframework.security.core.Authentication authentication) {

        String email = authentication.getName();
        ragUserService.changePassword(email, req.currentPassword(), req.newPassword());
        return ResponseEntity.ok().build();
    }

    private void setTokenCookie(HttpServletResponse response, String value, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from("rag-token", value)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}
}
