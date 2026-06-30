package com.ragvault.widget.security;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 사용자 입력 검증 컴포넌트.
 *
 * - 최대 길이 제한
 * - Prompt Injection 패턴 차단
 *
 * 외부 익명 방문자 대상 서비스이므로 prompt injection 방어 필수.
 */
@Component
public class InputValidator {

    private static final int MAX_INPUT_LENGTH = 2000;

    private static final List<Pattern> BLOCKED_PATTERNS = List.of(
            Pattern.compile("(?i)ignore (previous|prior|all) instructions?"),
            Pattern.compile("(?i)you are now"),
            Pattern.compile("(?i)forget (everything|your role)"),
            Pattern.compile("(?i)system prompt"),
            Pattern.compile("(?i)reveal your instructions"),
            Pattern.compile("(?i)act as (a |an )?(\\w+ )?(developer|admin|root)"),
            Pattern.compile("(?i)disregard (all|previous) (instructions?|rules?)"),
            Pattern.compile("(?i)jailbreak")
    );

    public record ValidationResult(boolean valid, String reason) {
        static ValidationResult ok() { return new ValidationResult(true, null); }
        static ValidationResult fail(String reason) { return new ValidationResult(false, reason); }
    }

    public ValidationResult validate(String input) {
        if (input == null || input.isBlank()) {
            return ValidationResult.fail("Empty input");
        }
        if (input.length() > MAX_INPUT_LENGTH) {
            return ValidationResult.fail("Input too long: max " + MAX_INPUT_LENGTH);
        }
        for (Pattern p : BLOCKED_PATTERNS) {
            if (p.matcher(input).find()) {
                return ValidationResult.fail("Suspicious pattern detected");
            }
        }
        return ValidationResult.ok();
    }
}
