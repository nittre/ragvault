package com.ragservice.rag.security;

import com.ragservice.rag.domain.MaskingRule;
import com.ragservice.rag.repository.MaskingRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PiiMaskerTest {

    PiiMasker masker;

    @BeforeEach
    void setup() {
        MaskingRuleRepository repo = mock(MaskingRuleRepository.class);
        // DB 기반 규칙 — 이메일 포함(테스트에서는 활성)으로 마스킹 로직을 검증한다.
        when(repo.findByEnabledTrueOrderBySortOrderAsc()).thenReturn(rules());
        masker = new PiiMasker(repo);
    }

    private List<MaskingRule> rules() {
        List<MaskingRule> list = new ArrayList<>();
        list.add(rule("주민등록번호", "\\d{6}-[1-4]\\d{6}", "[주민번호]", "standard", 10));
        list.add(rule("전화번호", "01[016789]-?\\d{3,4}-?\\d{4}", "[전화번호]", "standard", 20));
        list.add(rule("이메일", "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}", "[이메일]", "standard", 30));
        list.add(rule("카드번호", "\\d{4}[\\s\\-]?\\d{4}[\\s\\-]?\\d{4}[\\s\\-]?\\d{4}", "[카드번호]", "standard", 40));
        list.add(rule("사번", "EMP\\d{4,6}", "[사번]", "standard", 50));
        list.add(rule("계좌번호", "\\d{3,6}-?\\d{2,6}-?\\d{4,8}", "[계좌번호]", "aggressive", 60));
        list.add(rule("사업자번호", "\\d{3}-\\d{2}-\\d{5}", "[사업자번호]", "aggressive", 70));
        return list;
    }

    private MaskingRule rule(String name, String pattern, String replacement, String level, int sort) {
        MaskingRule r = new MaskingRule();
        r.setName(name);
        r.setPattern(pattern);
        r.setReplacement(replacement);
        r.setLevel(level);
        r.setEnabled(true);
        r.setSortOrder(sort);
        return r;
    }

    @Test
    void maskEmail() {
        assertThat(masker.mask("문의: test@example.com")).contains("[이메일]");
    }

    @Test
    void maskPhone() {
        assertThat(masker.mask("전화 010-1234-5678")).doesNotContain("010-1234-5678");
    }

    @Test
    void maskSsn() {
        assertThat(masker.mask("주민번호 900101-1234567")).doesNotContain("900101-1234567");
    }

    @Test
    void noMaskNormal() {
        assertThat(masker.mask("안녕하세요 일반 텍스트")).isEqualTo("안녕하세요 일반 텍스트");
    }

    @Test
    void nullSafe() {
        assertThat(masker.mask(null)).isNull();
    }

    @Test
    void maskCardNumber() {
        assertThat(masker.mask("카드번호 1234-5678-9012-3456")).doesNotContain("1234-5678-9012-3456");
    }

    @Test
    void maskMultiplePii() {
        String text = "이메일: user@company.com 전화: 010-9876-5432";
        String masked = masker.mask(text);
        assertThat(masked).contains("[이메일]");
        assertThat(masked).doesNotContain("010-9876-5432");
    }

    // M2: masking_level 지원 테스트
    @Test
    void maskLevelNone() {
        assertThat(masker.mask("010-1234-5678", "none")).isEqualTo("010-1234-5678");
    }

    @Test
    void maskLevelStandard() {
        assertThat(masker.mask("010-1234-5678", "standard")).doesNotContain("010-1234-5678");
    }

    @Test
    void maskLevelAggressive() {
        // 계좌번호 패턴: 123-456-7890123
        assertThat(masker.mask("계좌 123-456-7890123", "aggressive")).contains("[계좌번호]");
    }

    @Test
    void maskLevelNull() {
        // null level → STANDARD 적용
        assertThat(masker.mask("010-1234-5678", (String) null)).doesNotContain("010-1234-5678");
    }

    // M6: standard 레벨에서는 aggressive 규칙 미적용
    @Test
    void aggressiveRuleSkippedAtStandard() {
        // 계좌번호는 aggressive 규칙 → STANDARD 에서는 마스킹되지 않아야 함
        assertThat(masker.mask("계좌 123-456-7890123", "standard")).contains("123-456-7890123");
    }
}
