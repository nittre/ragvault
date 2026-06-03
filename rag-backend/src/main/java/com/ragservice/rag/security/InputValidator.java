package com.ragservice.rag.security;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 사용자 입력 검증 컴포넌트.
 *
 * - 최대 길이 제한
 * - Prompt Injection 패턴 차단 (requirements/05-prompt-design.md)
 */
@Component
public class InputValidator {

    private static final int MAX_INPUT_LENGTH = 4000;

    private static final List<Pattern> BLOCKED_PATTERNS = List.of(
            Pattern.compile("(?i)ignore (previous|prior|all) instructions?"),
            Pattern.compile("(?i)you are now"),
            Pattern.compile("(?i)forget (everything|your role)"),
            Pattern.compile("(?i)system prompt"),
            Pattern.compile("(?i)reveal your instructions"),
            Pattern.compile("(?i)act as (a |an )?(\\w+ )?(developer|admin|root)")
    );

    public record ValidationResult(boolean valid, String reason) {
        static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    /**
     * 사용자 입력을 검증한다.
     *
     * @param input 사용자 입력 텍스트
     * @return ValidationResult (valid=true이면 통과)
     */
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
