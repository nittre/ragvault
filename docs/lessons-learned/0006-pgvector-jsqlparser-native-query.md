# LL-0006: pgvector 네이티브 쿼리 — JSqlParserQueryEnhancer 파싱 실패

## 메타데이터

| 항목 | 값 |
|------|----|
| 발생일 | 2026-05-28 |
| 카테고리 | `data` |
| 심각도 | HIGH |
| 관련 작업 | M1 DocumentChunkRepository |
| 관련 ADR | ADR-0002 (access_groups 필터) |

---

## 에러 상황

Spring Data JPA `@Query(nativeQuery=true)`에 pgvector 전용 SQL 사용 시 컨텍스트 로드 실패:

```
QueryCreationException
  Caused by: java.lang.IllegalArgumentException at JSqlParserQueryEnhancer.java:127
    Caused by: net.sf.jsqlparser.parser.ParseException at CCJSqlParser.java:39603
```

**원인**: Spring Data JPA는 `@Query(nativeQuery=true)`로 선언된 쿼리를 `JSqlParserQueryEnhancer`로 파싱한다.
JSqlParser는 pgvector 전용 연산자를 지원하지 않는다:
- `<=>` — 코사인 거리 연산자
- `CAST(:embedding AS vector)` — pgvector 타입 캐스트
- `access_groups && ARRAY['all']` — PostgreSQL 배열 `&&` 연산자

문제가 된 쿼리:
```sql
SELECT content, source_table, source_id,
       1 - (embedding <=> CAST(:embedding AS vector)) AS score
FROM document_chunks
WHERE (embedding <=> CAST(:embedding AS vector)) < (1 - :threshold)
  AND access_groups && ARRAY['all']
ORDER BY embedding <=> CAST(:embedding AS vector)
LIMIT :topK
```

---

## 해결

`@Query(nativeQuery=true)` 제거 → 커스텀 리포지토리 구현으로 `EntityManager.createNativeQuery()` 직접 사용.

JSqlParser를 거치지 않으므로 PostgreSQL/pgvector 전용 SQL 자유롭게 사용 가능.

**파일 구조**:
```
DocumentChunkRepositoryCustom.java      ← 인터페이스
DocumentChunkRepositoryImpl.java        ← EntityManager 구현 (@Repository 없음!)
DocumentChunkRepository.java            ← extends JpaRepository + Custom
```

**핵심 주의사항**: `DocumentChunkRepositoryImpl`에 `@Repository` 붙이지 말 것.
Spring Data JPA가 명명 규칙(`{RepositoryName}Impl`)으로 자동 탐지한다.
`@Repository`를 붙이면 standalone 빈과 fragment 구현 두 곳에 등록되어
`BeanNotOfRequiredTypeException` 발생.

---

## @MockitoBean vs @TestConfiguration 순서 문제 (추가 발견)

M1 테스트 작성 시 추가로 발견:

`@ConditionalOnMissingBean`은 Spring 빈 팩토리 후처리 단계에서 평가된다.
`@MockitoBean`은 컨텍스트 생성 후 빈 오버라이드로 적용 — 평가 이후.
따라서 `@MockitoBean ChatClient chatClient`가 있어도 `@ConditionalOnMissingBean(ChatClient.class)` 평가 시 "없음"으로 판단됨.

**해결**: `spring.main.allow-bean-definition-overriding=true` + `@TestConfiguration` 내부 클래스 사용:
```java
@TestConfiguration
static class TestAiConfig {
    @Bean @Primary
    ChatClient chatClient() { return Mockito.mock(ChatClient.class); }
}
```
`@TestConfiguration`은 ApplicationContext 생성 시 함께 등록되어 `@ConditionalOnMissingBean`이 올바르게 감지.
빈 정의 등록 순서로 인해 `AiConfig.chatClient()`가 먼저 등록되므로 `allow-bean-definition-overriding=true` 필수.

---

## 비슷한 작업에 적용할 규칙

| When | Do |
|------|----|
| pgvector `<=>`, `&&`, `CAST(... AS vector)` 사용 시 | `@Query(nativeQuery=true)` 금지 → `EntityManager.createNativeQuery()` 사용 |
| Spring Data 커스텀 구현 클래스 작성 시 | `@Repository` 어노테이션 붙이지 말 것 |
| 테스트에서 `@ConditionalOnMissingBean` 빈 오버라이드 필요 시 | `@MockitoBean` 대신 `@TestConfiguration` + `spring.main.allow-bean-definition-overriding=true` |
| 다른 PostgreSQL 전용 문법 (JSON 연산자 `->`, `@>` 등) | 동일 문제 발생 가능 — 네이티브 쿼리는 `EntityManager` 직접 사용 |

---

## 재발 방지

- pgvector 관련 쿼리는 `DocumentChunkRepositoryImpl` 패턴으로만 작성 (ADR 반영 검토)
- `@Query(nativeQuery=true)`에서 `<=>`, `&&`, `CAST(... AS vector)` 사용 금지 → spec-check 추가 검토
