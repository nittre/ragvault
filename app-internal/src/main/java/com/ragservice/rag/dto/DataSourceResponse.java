package com.ragservice.rag.dto;

import java.time.Instant;

/**
 * 데이터소스 응답 DTO.
 * password_enc / ssh_private_key_enc / ssh_passphrase_enc 는 절대 포함하지 않는다 (보안).
 */
public record DataSourceResponse(
        Integer id,
        String name,
        String description,
        String dbType,
        String host,
        Integer port,
        String dbName,
        String username,
        boolean isActive,
        boolean sshEnabled,
        String sshHost,
        Integer sshPort,
        String sshUser,
        Instant createdAt,
        Instant updatedAt
) {}
