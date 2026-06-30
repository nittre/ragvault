package com.ragvault.widget.security;

import com.ragvault.core.repository.MaskingRuleRepository;
import com.ragvault.core.security.PiiMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PiiMasker 단위 테스트.
 * 빈 DB 반환 시 DEFAULT_RULES fallback으로 동작하는지 검증한다.
 */
class PiiMaskerTest {

    PiiMasker masker;

    @BeforeEach
    void setup() {
        MaskingRuleRepository repo = mock(MaskingRuleRepository.class);
        when(repo.findByEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of());
        masker = new PiiMasker(repo);
    }

    @Test
    void maskPhoneNumber() {
        String result = masker.mask("연락처: 010-1234-5678 입니다");
        assertThat(result).contains("[전화번호]");
        assertThat(result).doesNotContain("010-1234-5678");
    }

    @Test
    void maskResidentNumber() {
        String result = masker.mask("주민번호: 901231-1234567");
        assertThat(result).contains("[주민번호]");
        assertThat(result).doesNotContain("901231-1234567");
    }

    @Test
    void maskCardNumber() {
        String result = masker.mask("카드번호: 1234-5678-9012-3456");
        assertThat(result).contains("[카드번호]");
        assertThat(result).doesNotContain("1234-5678-9012-3456");
    }

    @Test
    void emailNotMaskedByDefault() {
        // 이메일 마스킹은 운영 결정(2026-05)으로 DEFAULT_RULES에서 제외 — DB 규칙으로만 활성화
        String text = "이메일: test@example.com 로 연락주세요";
        assertThat(masker.mask(text)).isEqualTo(text);
    }

    @Test
    void noMaskNormalText() {
        String text = "안녕하세요, FAQ를 확인해 주세요.";
        assertThat(masker.mask(text)).isEqualTo(text);
    }

    @Test
    void nullSafe() {
        assertThat(masker.mask(null)).isNull();
    }
}
