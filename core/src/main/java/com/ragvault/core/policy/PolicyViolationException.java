package com.ragvault.core.policy;

/**
 * AccessPolicy 게이트 위반 시 던지는 예외.
 * 사용자에게 노출 가능한 메시지를 담아야 한다.
 */
public class PolicyViolationException extends RuntimeException {

    public PolicyViolationException(String message) {
        super(message);
    }
}
