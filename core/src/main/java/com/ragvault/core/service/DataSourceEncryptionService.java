package com.ragvault.core.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 데이터소스 비밀번호 AES-256-GCM 암호화 서비스.
 *
 * 암호문 형식: Base64(12바이트 IV || GCM 암호문 + 16바이트 인증태그)
 *
 * 키는 생성자를 통해 주입받는다. 각 앱의 @Configuration 에서
 * 앱별 프로퍼티(rag.datasource.enc-key 또는 widget.encryption.key)를 읽어 빈을 등록한다.
 */
@Slf4j
@Service
public class DataSourceEncryptionService {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_LEN = 128;

    private final SecretKey secretKey;

    /**
     * @param encKeyBase64 Base64 인코딩된 32바이트 AES 키
     */
    public DataSourceEncryptionService(String encKeyBase64) {
        if (encKeyBase64 == null || encKeyBase64.isBlank()) {
            throw new IllegalStateException(
                    "암호화 키가 설정되지 않았습니다. openssl rand -base64 32 로 생성하세요.");
        }
        byte[] keyBytes = Base64.getDecoder().decode(encKeyBase64);
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "암호화 키는 32바이트(256비트)여야 합니다. 현재 길이: " + keyBytes.length + "바이트");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        log.info("DataSourceEncryptionService initialized (AES-256-GCM)");
    }

    /**
     * 평문을 AES-256-GCM 으로 암호화한다.
     *
     * @param plaintext 평문 비밀번호
     * @return Base64(IV + ciphertext)
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LEN, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_LEN + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_LEN);
            System.arraycopy(ciphertext, 0, combined, IV_LEN, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("암호화 실패", e);
        }
    }

    /**
     * Base64(IV + ciphertext) 를 복호화한다.
     *
     * @param encoded Base64 인코딩된 암호문
     * @return 평문 비밀번호
     */
    public String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(combined, 0, iv, 0, IV_LEN);
            byte[] ciphertext = new byte[combined.length - IV_LEN];
            System.arraycopy(combined, IV_LEN, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LEN, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("복호화 실패", e);
        }
    }
}
