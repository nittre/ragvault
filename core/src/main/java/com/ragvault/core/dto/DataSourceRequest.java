package com.ragvault.core.dto;

/**
 * 데이터소스 등록/수정 요청 DTO.
 * password / sshPrivateKey / sshPassphrase 는 평문 수신 — 서비스 계층에서 암호화.
 */
public record DataSourceRequest(
        String name,
        String description,
        String dbType,
        String host,
        Integer port,
        String dbName,
        String username,
        String password,

        // SSH 터널 설정
        Boolean sshEnabled,
        String sshHost,
        Integer sshPort,
        String sshUser,
        String sshPrivateKey,   // PEM 개인키 전문 (평문) — 응답에 절대 포함 금지
        String sshPassphrase,   // PEM passphrase (평문) — 응답에 절대 포함 금지

        // 등록 시 LLM 으로 테이블·컬럼 자연어 설명 자동 생성 (기본 false)
        Boolean autoDescribe
) {}
