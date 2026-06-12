package com.ragservice.rag.dto;

import java.time.Instant;

public record LoginResponse(
        String email,
        String role,
        Instant expiresAt,
        boolean passwordChangeRequired
) {}
