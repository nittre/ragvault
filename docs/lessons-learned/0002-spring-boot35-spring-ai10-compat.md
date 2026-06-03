# LL-0002: Spring Boot 3.5 + Spring AI 1.0.0 호환성 - 아티팩트명·테스트 API 변경

## 메타데이터

| 항목 | 값 |
|------|----|
| 발생일 | 2026-05-21 |
| 카테고리 | `tooling`, `build` |
| 심각도 | HIGH |
| 관련 작업 | M0 rag-backend 부트스트랩 |
| 관련 ADR | ADR-0004 (Spring AI 전면 채택) |
| 관련 Requirements | requirements/02-stack-reference.md |

---

## 에러 상황 (시간 순)

### 실패 1 — Spring AI 아티팩트명 변경
```
FAILURE: Could not find org.springframework.ai:spring-ai-ollama-spring-boot-starter:.
```
- **원인**: Spring AI 1.0.0 GA에서 Ollama starter 아티팩트명이 변경됨
- **구 이름**: `spring-ai-ollama-spring-boot-starter`
- **신 이름**: `spring-ai-starter-model-ollama`

### 실패 2 — `@MockBean` 제거
```
warning: [removal] MockBean in org.springframework.boot.test.mock.mockito has been deprecated and marked for removal
java.lang.IllegalStateException at MockitoPostProcessor.java:225
```
- **원인**: Spring Boot 3.4부터 `@MockBean` 제거 예고 → Spring Boot 3.5에서 동작 불안정
- **신 API**: `@MockitoBean` (`org.springframework.test.context.bean.override.mockito.MockitoBean`)

### 실패 3 — `ChatClient.Builder` prototype 스코프 mock 불가
```
Unable to override bean 'chatClientBuilder': only singleton beans can be overridden.
```
- **원인**: Spring AI의 `ChatClient.Builder`는 prototype 스코프 bean → `@MockitoBean`으로 override 불가

### 실패 4 — `excludeAutoConfiguration` 속성 제거
```
error: cannot find symbol — method excludeAutoConfiguration() at @interface SpringBootTest
```
- **원인**: Spring Boot 3.5에서 `@SpringBootTest(excludeAutoConfiguration = {...})` 속성 제거

### 실패 5 — 잘못된 AutoConfiguration 클래스 지정
```
IllegalStateException: The following classes could not be excluded because they are not auto-configuration classes:
  - org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration
```
- **원인**: `OllamaApiAutoConfiguration`은 내부 `@Configuration`이지 `@AutoConfiguration`이 아님
- **실제 등록된 auto-config** (`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 확인 필요):
  - `org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration` ✅
  - `org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration` ✅

---

## 원인 (5 Whys)

1. 왜 빌드가 깨졌나? → Spring AI BOM 버전과 아티팩트명이 0.x → 1.0.0 GA 전환 시 크게 바뀜
2. 왜 테스트가 깨졌나? → Spring Boot 3.5가 `@MockBean`, `excludeAutoConfiguration` 같은 레거시 API를 정리함
3. 왜 미리 몰랐나? → requirements 문서에 버전별 호환성 변경사항이 기록되지 않음
4. 왜 디버깅이 길어졌나? → 에러 메시지가 각 실패마다 달라 연관성을 파악하는 데 시간 소요

---

## 해결

### 임시 해결
- `build.gradle` 아티팩트명: `spring-ai-ollama-spring-boot-starter` → `spring-ai-starter-model-ollama`
- 테스트: `@MockBean` → `@MockitoBean` (또는 제거 후 auto-config exclude 방식으로 전환)

### 영구 해결 (적용됨)
```java
// ✅ Spring Boot 3.5 + Spring AI 1.0.0 기준 올바른 테스트 패턴
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.autoconfigure.exclude=" +
            "org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration," +
            "org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration"
    }
)
@ActiveProfiles("test")
class RagBackendApplicationTests { ... }
```

```groovy
// ✅ build.gradle Spring AI 1.0.0 GA 기준 올바른 아티팩트명
implementation 'org.springframework.ai:spring-ai-starter-model-ollama'
```

---

## 재발 방지

### 시스템 (자동화)

1. **spec-check 패턴 추가** — `spring-ai-ollama-spring-boot-starter` 구 아티팩트명 사용 감지:
   ```bash
   grep -rn "spring-ai-ollama-spring-boot-starter" rag-backend/
   # 발견 시: spring-ai-starter-model-ollama 로 교체
   ```

2. **spec-check 패턴 추가** — 구 `@MockBean` import 감지:
   ```bash
   grep -rn "org.springframework.boot.test.mock.mockito.MockBean" rag-backend/src/test/
   # 발견 시: org.springframework.test.context.bean.override.mockito.MockitoBean 으로 교체
   ```

3. **새 AutoConfiguration 확인 절차** — auto-config exclude 필요 시 반드시 jar 내부 확인:
   ```bash
   unzip -p {jar-path} META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
   ```

---

## 비슷한 작업에 적용할 규칙

| When | Do |
|------|----|
| Spring AI를 새 프로젝트에 추가할 때 | `spring-ai-starter-model-ollama` 사용 (구 이름 사용 금지) |
| Spring Boot 3.4+ 테스트에서 bean mock이 필요할 때 | `@MockitoBean` 사용 (`@MockBean` 금지) |
| `@SpringBootTest`에서 auto-config 제외가 필요할 때 | `properties = {"spring.autoconfigure.exclude=..."}` 사용 |
| auto-config 클래스명을 exclude에 지정하기 전 | jar 내 `AutoConfiguration.imports` 파일로 실제 등록 여부 확인 |
| Spring AI 버전을 업그레이드할 때 | `spring-ai-autoconfigure-model-ollama` jar의 `AutoConfiguration.imports` 재확인 |
| prototype 스코프 bean을 테스트에서 교체해야 할 때 | `@MockitoBean` 대신 `@TestConfiguration` + `@Primary` mock bean 제공 |

---

## 참고

- Spring AI 1.0.0 Release Notes: https://spring.io/blog/2024/05/30/spring-ai-1-0-0-released
- Spring Boot 3.5 Migration Guide
- 관련 커밋: M0 rag-backend bootstrap
