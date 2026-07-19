package com.ragservice.rag.aspect;

import com.ragservice.rag.service.AuditLogService;
import com.ragvault.core.security.Auditable;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.context.expression.MapAccessor;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * {@link Auditable} 어노테이션이 붙은 메서드가 정상 반환될 때 챗 서비스 {@link AuditLogService}로
 * 감사 로그를 남기는 Aspect.
 *
 * 설계 요구사항:
 * - actor SpEL이 비어 있으면 SecurityContext에서 인증 사용자 이름을 기본 추출한다(없으면 "unknown").
 * - IP는 RequestContextHolder로 현재 요청에서 직접 추출한다(요청 스레드가 아니면 null).
 * - advice 본문 전체를 try/catch로 감싼다: 감사 로그 실패(SpEL 평가·빈 조회 포함)가
 *   이미 정상 종료된 비즈니스 로직으로 예외 전파돼선 안 된다(@AfterReturning의 특성상 예외가
 *   그대로 호출자에게 전파되므로 반드시 삼켜야 한다).
 */
@Slf4j
@Aspect
@Component
public class AuditLogAspect {

    private final AuditLogService auditLogService;
    private final BeanFactory beanFactory;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer paramNameDiscoverer = new DefaultParameterNameDiscoverer();

    public AuditLogAspect(AuditLogService auditLogService, BeanFactory beanFactory) {
        this.auditLogService = auditLogService;
        this.beanFactory = beanFactory;
    }

    @AfterReturning(pointcut = "@annotation(auditable)", returning = "result")
    public void record(JoinPoint joinPoint, Auditable auditable, Object result) {
        try {
            EvaluationContext context = buildContext(joinPoint, result);

            String userEmail = evalActor(auditable.actor(), context);
            String action = evalString(auditable.action(), context);
            String intent = evalString(auditable.targetType(), context);
            String ip = extractIp();

            boolean extended = isSet(auditable.userMessage()) || isSet(auditable.hasContext())
                    || isSet(auditable.isBlocked()) || isSet(auditable.sourceCount());

            if (extended) {
                String userMessage = evalString(auditable.userMessage(), context);
                boolean hasContext = evalBoolean(auditable.hasContext(), context);
                boolean isBlocked = evalBoolean(auditable.isBlocked(), context);
                int sourceCount = evalInt(auditable.sourceCount(), context);
                auditLogService.log(userEmail, action, intent, userMessage, ip, null,
                        hasContext, isBlocked, sourceCount);
            } else {
                String requestSummary = evalString(auditable.targetId(), context);
                auditLogService.log(userEmail, action, intent, requestSummary, ip, null);
            }
        } catch (Exception e) {
            // 감사 로그 실패가 비즈니스 로직을 차단하면 안 됨 (원본 메서드는 이미 정상 종료됨).
            log.error("감사 로그 기록 실패 (비즈니스 로직에는 영향 없음): {}", e.getMessage(), e);
        }
    }

    private EvaluationContext buildContext(JoinPoint joinPoint, Object result) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setBeanResolver(new BeanFactoryResolver(beanFactory));
        context.addPropertyAccessor(new MapAccessor());

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String[] paramNames = paramNameDiscoverer.getParameterNames(method);
        Object[] args = joinPoint.getArgs();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length && i < args.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        context.setVariable("result", result);
        return context;
    }

    /** actor SpEL이 비면 SecurityContext에서 기본 추출한다. */
    private String evalActor(String expr, EvaluationContext context) {
        if (isSet(expr)) {
            String value = evalString(expr, context);
            return value != null ? value : "unknown";
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "unknown";
    }

    private String evalString(String expr, EvaluationContext context) {
        if (!isSet(expr)) return null;
        Object value = parser.parseExpression(expr).getValue(context);
        return value != null ? value.toString() : null;
    }

    private boolean evalBoolean(String expr, EvaluationContext context) {
        if (!isSet(expr)) return false;
        Object value = parser.parseExpression(expr).getValue(context);
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    private int evalInt(String expr, EvaluationContext context) {
        if (!isSet(expr)) return 0;
        Object value = parser.parseExpression(expr).getValue(context);
        return value instanceof Number n ? n.intValue() : 0;
    }

    private String extractIp() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra) {
                return sra.getRequest().getRemoteAddr();
            }
        } catch (Exception e) {
            log.debug("IP 추출 실패 (요청 스레드가 아님): {}", e.getMessage());
        }
        return null;
    }

    private boolean isSet(String expr) {
        return expr != null && !expr.isEmpty();
    }
}
