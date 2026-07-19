package com.ragvault.widget.aspect;

import com.ragvault.core.security.Auditable;
import com.ragvault.widget.service.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@link Auditable} 어노테이션이 붙은 메서드가 정상 반환될 때 위젯 서비스의
 * {@link AuditLogService#log} 를 자동 호출하는 Aspect.
 *
 * SpEL 평가 컨텍스트에는 메서드 파라미터(이름 접근), 반환값(#result), 스프링 빈(@beanName)이 포함된다.
 * IP 주소는 RequestContextHolder 로 직접 추출하며, actor 를 비워두면 SecurityContext 에서 기본 추출한다.
 *
 * (설계 3-2) advice 본문 전체를 try/catch 로 감싸 감사 로그 실패가 절대 비즈니스 로직을 차단하지 않도록 한다.
 */
@Slf4j
@Aspect
@Component
public class AuditLogAspect {

    private final AuditLogService auditLogService;
    private final BeanFactory beanFactory;
    private final SpelExpressionParser parser = new SpelExpressionParser();

    public AuditLogAspect(AuditLogService auditLogService, BeanFactory beanFactory) {
        this.auditLogService = auditLogService;
        this.beanFactory = beanFactory;
    }

    @AfterReturning(
            pointcut = "@annotation(auditable)",
            returning = "result")
    public void recordAudit(JoinPoint joinPoint, Auditable auditable, Object result) {
        try {
            StandardEvaluationContext context = buildContext(joinPoint, result);

            String action = evaluate(auditable.action(), context);
            String actor = evaluate(auditable.actor(), context);
            if (actor == null || actor.isBlank()) {
                actor = defaultActor();
            }
            String targetType = evaluate(auditable.targetType(), context);
            String targetId = evaluate(auditable.targetId(), context);
            String detail = evaluate(auditable.detail(), context);
            String ip = currentIp();

            auditLogService.log(actor, action, targetType, targetId, detail, ip);
        } catch (Exception e) {
            // 감사 로그 실패가 비즈니스 로직을 차단하면 안 됨 — 예외를 삼키고 로그만 남긴다.
            log.error("AuditLogAspect failed for {}: {}",
                    joinPoint.getSignature(), e.getMessage(), e);
        }
    }

    private StandardEvaluationContext buildContext(JoinPoint joinPoint, Object result) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setBeanResolver(new BeanFactoryResolver(beanFactory));
        context.addPropertyAccessor(new MapAccessor());

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        context.setVariable("result", result);
        return context;
    }

    /** 빈 문자열이면 null 반환, 아니면 SpEL 평가 후 String 으로 변환. */
    private String evaluate(String spel, StandardEvaluationContext context) {
        if (spel == null || spel.isBlank()) {
            return null;
        }
        Expression expression = parser.parseExpression(spel);
        return expression.getValue(context, String.class);
    }

    /** actor SpEL 미지정 시 SecurityContext 에서 기본 추출. 없으면 "unknown". */
    private String defaultActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getName() != null) {
            return authentication.getName();
        }
        return "unknown";
    }

    /** 요청 스레드가 아니면 null 로 폴백. */
    private String currentIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            return attrs.getRequest().getRemoteAddr();
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
