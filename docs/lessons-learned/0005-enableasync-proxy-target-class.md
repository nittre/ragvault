# LL-0005: @EnableAsync 기본값 JDK 프록시 → @Async 빈 타입 불일치

## 메타데이터

| 항목 | 값 |
|------|----|
| 발생일 | 2026-05-28 |
| 카테고리 | `runtime` |
| 심각도 | HIGH |
| 관련 작업 | M1 ApiKeyAuthFilter + @EnableAsync |
| 관련 ADR | 없음 |

---

## 에러 상황

`ApiKeyAuthFilter` (`@Component`)에 `@Async` 메서드(`updateLastUsedAt`)를 붙인 상태에서
`SecurityConfig.filterChain(HttpSecurity, ApiKeyAuthFilter)`가 `ApiKeyAuthFilter` 타입으로 주입받으려 할 때:

```
BeanNotOfRequiredTypeException: Bean named 'apiKeyAuthFilter' is expected to be of type
'com.ragservice.rag.filter.ApiKeyAuthFilter'
but was actually of type 'jdk.proxy3.$Proxy188'
```

**원인**: `@EnableAsync`의 `proxyTargetClass` 기본값은 `false`.
`proxyTargetClass=false`이면 `@Async` 빈에 JDK dynamic proxy 적용.
JDK proxy는 인터페이스만 구현 — 구체 클래스(`ApiKeyAuthFilter`) 타입 유지 불가.

`spring.aop.proxy-target-class=true` (Spring Boot 기본)는 AspectJ weaving에만 적용.
`@EnableAsync`의 프록시 방식은 별도 설정 필요.

---

## 해결

```java
// RagBackendApplication.java
@EnableAsync(proxyTargetClass = true)  // CGLIB proxy → 구체 타입 상속 유지
```

CGLIB 프록시는 `ApiKeyAuthFilter`를 **상속**하므로 `instanceof ApiKeyAuthFilter` 통과.
JDK 프록시는 인터페이스(Filter)만 구현하므로 `instanceof ApiKeyAuthFilter` 실패.

---

## 비슷한 작업에 적용할 규칙

| When | Do |
|------|----|
| `@EnableAsync` 사용 시 | 항상 `@EnableAsync(proxyTargetClass = true)` 사용 |
| `@Async` 메서드가 `@Component`/`@Service`에 있고 | 해당 빈을 구체 타입으로 주입받는다면 CGLIB proxy 필수 |
| `@Scheduled`, `@Transactional`도 동일 원리 | 구체 타입 주입 필요 시 `proxyTargetClass=true` |
| `@Async`를 Filter에 붙이는 것 자체를 피하려면 | 별도 `@Service AsyncService`로 분리 (더 깔끔) |

---

## 재발 방지

- `RagBackendApplication.java`에 `@EnableAsync(proxyTargetClass = true)` 고정 (이미 적용됨)
- 코드 리뷰 시: `@EnableAsync` 없이 `(proxyTargetClass=true)` 누락 확인
