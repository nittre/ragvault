package com.ragvault.core.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 감사 로그 자동 기록 마커. 각 앱(app-internal/app-widget)의 AuditLogAspect가
 * 이 어노테이션이 붙은 메서드가 정상 반환될 때 자기 AuditLogService.log(...)를 호출한다.
 *
 * 모든 속성은 SpEL 표현식 문자열이다. 평가 컨텍스트에는 메서드 파라미터(이름으로 접근)와
 * 반환값(#result)이 포함된다. 문자열 리터럴은 "'LITERAL'"처럼 작은따옴표로 감싼다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    /** 액션명 SpEL. 예: "'LOGIN'", "@chatAuditActionResolver.resolve(#result.intent())" */
    String action();

    /** 행위자 이메일 SpEL. 비워두면 Aspect가 SecurityContextHolder에서 기본 추출한다. */
    String actor() default "";

    String targetType() default "";

    String targetId() default "";

    String detail() default "";

    // 챗 서비스 전용 확장 필드 — 비워두면 6-인자 log() 오버로드를 쓴다.
    String userMessage() default "";

    String hasContext() default "";

    String isBlocked() default "";

    String sourceCount() default "";
}
