# ADR-0004: 챗 서비스 지식문서 관리 — SOURCE_TABLE·API·디렉토리 분리 전략

- **상태**: Accepted
- **결정일**: 2026-06-30
- **결정자**: 개발팀
- **관련 ADR**: ADR-0001, ADR-0002
- **영향 받는 코드**: `app-internal/src/.../service/KnowledgeDocIngestionService.java`, `app-internal/src/.../controller/AdminKnowledgeController.java`, `frontend/internal/src/pages/admin/KnowledgeDocsPage.tsx`

## 컨텍스트 (Why)

위젯 서비스(`app-widget`)에 지식문서 파일 업로드·임베딩·RAG 기능이 존재한다.
챗(내부) 서비스(`app-internal`)에도 동일 기능이 필요해졌다.

두 서비스는 **동일한 pgvector 인스턴스**를 공유하므로 아무런 분리 없이 구현하면
위젯과 챗의 벡터 데이터가 뒤섞여 RAG 검색 정확도가 오염된다.

분리 방법으로 세 가지를 검토했다:

| 방법 | 설명 |
|------|------|
| A. DB 스키마 분리 | 별도 스키마에 임베딩 테이블 생성 |
| B. SOURCE_TABLE 컬럼 분리 | 동일 테이블에 `source_table` 컬럼으로 구분 |
| C. 별도 pgvector 인스턴스 | 두 서비스가 각자 DB 사용 |

## 결정 (What)

**옵션 B 채택 — `source_table` 컬럼으로 분리.**

```
위젯: SOURCE_TABLE = "knowledge_doc"
챗:   SOURCE_TABLE = "internal_knowledge_doc"
```

- `KnowledgeDocIngestionService`를 `app-internal`에 별도 클래스로 생성
  (`core`의 공용 서비스가 아닌 서비스별 클래스 — SOURCE_TABLE이 다르기 때문)
- API prefix: `/api/v1/admin/knowledge` (챗 서비스 기존 컨벤션 `/api/v1/` 유지)
- 파일 디렉토리: `knowledge-internal/` (위젯의 `knowledge/`와 물리적 분리)
- UI 화면·용어·메뉴는 위젯과 **완전히 동일**하게 구현
  (지식문서 관리, 파일 업로드, 전체 재임베딩, 마크다운 추가)

## 결과 (Consequences)

### 장점
- **데이터 격리**: 두 서비스의 벡터 검색이 서로 오염되지 않음.
- **인프라 무변경**: 새 DB 인스턴스나 스키마 마이그레이션 없이 기존 `document_chunks` 테이블 재사용.
- **UI 일관성**: 동일한 화면·용어로 관리자 학습 비용 최소화.
- **독립적 운영**: 서비스별 디렉토리 분리로 파일 시스템 충돌 없음.

### 단점·트레이드오프
- **코드 중복**: `KnowledgeDocIngestionService`가 위젯의 `KnowledgeIngestionService`와 로직이 거의 동일하다. SOURCE_TABLE만 다르기 때문에 `core`로 추출하지 않고 서비스별로 유지한다.
  - 향후 인터페이스 추출을 고려할 수 있으나 현재 두 서비스만 존재하므로 조기 추상화를 피한다.
- **감사 로그 불일치**: 위젯은 `AuditLogService`를 사용하지만 챗 서비스에는 해당 서비스가 없어 `@Slf4j` 로깅으로 대체.

### 후속 작업
- 챗 서비스에 `AuditLogService` 도입 시 `AdminKnowledgeController`의 로그 통합
- 볼륨 마운트: `internal_knowledge_data:/app/knowledge-internal` (compose.internal.yml)

## 대안 (검토했으나 채택 안 한 옵션)

### 옵션 A — DB 스키마 분리
- 격리가 완벽하지만 Flyway 마이그레이션, JPA 멀티-스키마 설정, pgvector 익스텐션 재설치 등 추가 비용이 크다.
- **채택 안 한 이유**: 현재 두 서비스 규모에서 과도한 복잡도.

### 옵션 C — 별도 pgvector 인스턴스
- 완전한 격리지만 DB 프로세스 추가, 메모리·스토리지 이중화, Ollama 연결 관리 복잡도 증가.
- **채택 안 한 이유**: 로컬·소규모 운영 환경 가정에서 리소스 낭비.
