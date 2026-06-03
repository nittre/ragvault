
## LL-0007: Apache Tika 2.x API 변경사항
- **날짜**: 2026-05-28
- **증상**: `org.apache.tika.detect.AutoDetectParser` cannot find symbol; `Metadata.RESOURCE_NAME_KEY` cannot find symbol
- **원인**: Tika 2.x에서 `AutoDetectParser`는 `org.apache.tika.parser` 패키지로 이동. `Metadata.RESOURCE_NAME_KEY`는 `TikaCoreProperties.RESOURCE_NAME_KEY`로 대체됨.
- **해결**: import를 `org.apache.tika.parser.AutoDetectParser`로 수정; `meta.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename)` 사용.

## LL-0008: 테스트 @Primary 이중 등록 → NoUniqueBeanDefinitionException
- **날짜**: 2026-05-28
- **증상**: `NoUniqueBeanDefinitionException: more than one 'primary' bean found among candidates: [chatClient, vlmChatClient]`
- **원인**: `TestAiConfig`에서 `chatClient`와 `vlmChatClient` 모두 `@Primary`로 등록 시 Spring이 `ChatClient` 타입 단순 주입(`IntentClassifierService` 등)에서 ambiguity 발생.
- **해결**: `vlmChatClient` mock에서 `@Primary` 제거. VLM은 `@Qualifier("vlmChatClient")`로 명시적 주입되므로 `@Primary` 불필요.

## LL-0009: @WebMvcTest + @Component 필터의 새 의존성 → NoSuchBeanDefinitionException
- **날짜**: 2026-05-28
- **증상**: `HealthControllerTest` 실패 — `No qualifying bean of type 'StringRedisTemplate' available`
- **원인**: 새로 추가된 `AdminSessionFilter(@Component)`가 `StringRedisTemplate`을 주입받는데, `@WebMvcTest` 슬라이스는 Redis 자동 구성을 로드하지 않아 빈이 없음.
- **해결**: `HealthControllerTest`에 `@MockitoBean private StringRedisTemplate stringRedisTemplate;` 추가. `@WebMvcTest`에서 `@Component` 필터가 추가될 때마다 해당 필터의 의존성을 mock으로 제공해야 함.
