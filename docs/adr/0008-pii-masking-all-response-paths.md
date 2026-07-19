# ADR-0008: PII 마스킹 원칙 — 모든 LLM 응답 경로에 STANDARD 마스킹 적용

- **상태**: Accepted
- **결정일**: 2026-06-30
- **결정자**: 개발팀
- **관련 ADR**: ADR-0004, ADR-0010
- **영향 받는 코드**: `core/src/main/java/com/ragvault/core/security/PiiMasker.java`, `core/.../domain/MaskingRule.java`, `core/.../repository/MaskingRuleRepository.java`, `app-internal/.../controller/AdminMaskingRuleController.java`, `RagTableConfig.pii_masking_level`

## 컨텍스트 (Why)

챗 서비스(`app-internal`)는 사내 datasource·문서·SQL 조회 결과를 근거로 LLM이 답변을 생성한다. 이 근거 자료에는 주민번호·전화번호·카드번호·사번·이름·주소 등 PII가 포함될 수 있고, RAG/SQL/HYBRID 등 어떤 경로로 답변이 만들어지든 최종적으로 사용자에게 반환되는 텍스트에는 PII가 노출되지 않아야 한다. 경로별로 마스킹 적용 여부가 다르면 특정 질의 유형에서만 PII가 새는 구멍이 생긴다.

## 결정 (What)

```
1. 모든 LLM 응답 경로(RAG/SQL/HYBRID/WEB_SEARCH 등)는 사용자에게 반환하기 직전
   PiiMasker.mask() 를 반드시 호출한다. 라우팅 레이어(QueryRouterService)가
   일괄 적용하는 것이 아니라, 각 서비스(RagService/TextToSqlService/
   HybridQueryService 등) 내부에서 개별적으로 호출한다 — 경로마다 반환 시점이
   다르기 때문.
2. 기본 레벨은 STANDARD. 테이블/데이터소스 단위로 NONE/STANDARD/AGGRESSIVE
   3단계를 rag_table_config.pii_masking_level 로 설정 가능.
3. 실제 정규식 규칙은 masking_rule DB 테이블로 관리(M6, Admin UI 노출) —
   enabled=true 규칙만 sort_order 순으로 적용(긴 패턴 먼저, 오탐 방지),
   level=standard 규칙은 STANDARD/AGGRESSIVE 모두, level=aggressive 규칙은
   AGGRESSIVE 에서만 적용. 60초 인메모리 캐시 + evict()(규칙 CUD 후 호출).
4. DB가 비어있거나 조회 실패 시 하드코딩 DEFAULT_RULES 로 안전하게 폴백한다
   (주민번호·전화번호·카드번호·사번·이름(honorific 컨텍스트)·구조적 주소는
   STANDARD, 계좌번호·사업자번호는 AGGRESSIVE). 이메일은 2026-05 운영
   결정으로 기본 비활성 — DEFAULT_RULES 에도 포함하지 않는다.
```

## 결과 (Consequences)

### 장점
- 어떤 질의 경로를 타든 최종 응답에 동일한 PII 보호 수준이 보장된다.
- 마스킹 규칙을 재배포 없이 DB에서 즉시 추가/수정할 수 있다(Admin UI).
- DB 장애 시에도 하드코딩 폴백으로 마스킹 자체가 통째로 비활성화되지 않는다.

### 단점·트레이드오프
- 정규식 기반이라 형식을 벗어난 PII(예: 공백·특수문자 삽입, 비정형 표기)는 탐지하지 못할 수 있다.
- 각 서비스가 개별적으로 `mask()` 를 호출하는 구조라, 신규 응답 경로 추가 시 호출을 빠뜨리면 정적으로 잡히지 않는다(리뷰 의존).
- 이름 마스킹은 오탐(과잉 마스킹)을, 이메일 비활성화는 누락(과소 마스킹)을 감수한 트레이드오프다.

### 후속 작업
- 신규 응답 경로 추가 시 `PiiMasker.mask()` 호출 누락을 잡아낼 수 있는 테스트/린트 규칙 마련 검토.
- 이메일 마스킹 비활성화 결정을 주기적으로 재검토.

## 대안 (검토했으나 채택 안 한 옵션)

### 옵션 A — LLM 프롬프트 지시만으로 PII 노출 억제 (후처리 마스킹 없음)
시스템 프롬프트에 "PII를 노출하지 마라"는 규칙만 추가. 구현이 단순하지만 LLM이 지시를 놓치거나 우회할 가능성을 결정적으로 막지 못한다.
**채택 안 한 이유**: 보안 통제는 결정적(deterministic)이어야 하며, LLM 준수 여부에 기대는 것은 허용할 수 없는 리스크.

### 옵션 B — 마스킹 규칙을 코드에 하드코딩만 유지 (DB화 없음)
배포 없이 규칙을 추가/조정할 수 없어 신규 PII 패턴 대응이 느려진다.
**채택 안 한 이유**: 운영 중 발견되는 PII 패턴에 빠르게 대응하기 위해 M6에서 DB 기반으로 전환.

## 참고

- ADR-0004 (SOURCE_TABLE 분리를 통한 데이터 경계)
- ADR-0010 (마스킹 실패 진단을 위한 원본 응답 단기 저장소)
