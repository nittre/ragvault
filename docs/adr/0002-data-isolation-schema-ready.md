# ADR-0002: 직원 간 데이터 격리 — Phase 0 단순 정책 + 스키마는 미리 (옵션 D)

- **상태**: Accepted
- **결정일**: 2026-05-19
- **결정자**: 시니어 백엔드 엔지니어 (사용자 옵션 D 채택)
- **관련 ADR**: —
- **영향 받는 문서**: `requirements/03-data-sync-pipeline.md`, `requirements/04-rag-search-strategy.md` 섹션 5-0, `requirements/07-auth-security.md` 섹션 5, `requirements/08-text-to-sql.md`

## 컨텍스트

Dedicated Instance 모델은 **고객사 단위** 격리만 다룬다. 그러나 한 고객사 300명 안에서 부서별 데이터(HR, 임원, 법무, 영업비밀)에 대한 직원 간 접근 권한 모델은 어디에도 정의되지 않았다.

신입사원이 "임원 보너스"를 RAG로 조회할 수 있다면:
- PIPA(개인정보보호법) 위반 가능
- 사내 정보보안 등급 분류 위반
- 영업비밀 노출

이 권한 모델은 **데이터 모델·검색 쿼리에 깊이 박힘** — 나중에 추가하려면 `document_chunks` 수백만 행 마이그레이션 + 모든 검색 쿼리 재작성 + backfill 불가능에 가까움.

## 결정

**Phase 0 정책 단순, 스키마는 미리 (옵션 D)**.

1. **스키마 추가**:
   - `rag_table_config.allowed_groups TEXT[] DEFAULT ['all']`
   - `rag_table_config.data_sensitivity VARCHAR(20) DEFAULT 'internal'` (public/internal/restricted)
   - `document_chunks.access_groups TEXT[] DEFAULT ['all']` + GIN 인덱스
   - `sql_table_config` 도 동일 필드 추가
   - `user_groups` / `group_definitions` 테이블 (Phase 0 엔 비활성)

2. **Phase 0 정책 (운영)**:
   - 모든 청크의 `access_groups = ['all']`
   - 모든 사용자에게 `user_groups = ['all']` 자동 부여
   - 검색 쿼리에 `access_groups && $userGroups` 필터 항상 적용 (Phase 0 는 자명히 매칭)
   - `data_sensitivity = 'restricted'` 등록 시 Spring Boot 가 거부
   - `data_sensitivity = 'internal'` 등록 시 admin Discord 알람

3. **고객사 admin 합의서**: "민감·기밀 데이터 RAG 등록 금지. Phase 1+ 에 부서별 권한 분리 제공 예정."

4. **Phase 1+**: `user_groups` 활성화, 부서별 그룹 정의, 마이그레이션 비용 0 (스키마 그대로, 데이터만 부여).

## 결과

### 장점
- Phase 0 일정 영향 거의 0 (스키마 컬럼 추가뿐, 검색 쿼리 한 줄 추가)
- Phase 1+ 전환 시 마이그레이션 비용 0 — 운영 중 backfill 가능
- 운영 정책 + 등록 가드로 위험 통제
- PIPA 대응 — "민감정보 등록 안 함" 자체가 컴플라이언스 한 방법
- Scope(시스템 권한)과 데이터 권한 개념 분리 — Phase 1+ 확장 자연스러움

### 단점·트레이드오프
- Phase 0 운영 중 admin 등록 실수 시 데이터 노출 위험 (운영 정책 의존)
- 코드 리뷰 시 죽은 컬럼처럼 보일 수 있음 → 코멘트로 의도 명시

### 후속 작업
- 코드: 모든 벡터 검색 쿼리에 `access_groups && $userGroups` 필터 추가
- 코드: 등록 시 `data_sensitivity` 검증 가드 (restricted 거부)
- 인프라: Discord 알람 채널 — admin 등록 알림
- Phase 1+ : 부서 그룹 정의 + user_groups 부여 + 합의서 격상

## 대안

### 옵션 A — Phase 0 정책 단순, 스키마도 단순 (스키마 없음)
가장 단순. 그러나 Phase 1+ 마이그레이션 비용이 가장 큰 위험. 거부.

### 옵션 B — 테이블 단위 ACL 즉시 도입
정밀하지만 Phase 0 +1주, 사용자 그룹 동기화 부담. Phase 0 부적합.

### 옵션 C — 청크/행 단위 ACL 즉시 도입
가장 정밀. Phase 0 +3~4주, 동기화 파이프라인 복잡도 큼. Phase 0 부적합.

## 참고

- 권위 출처: `requirements/03-data-sync-pipeline.md` 섹션 3, `requirements/04-rag-search-strategy.md` 섹션 5-0
- Scope vs 데이터 그룹 분리: `requirements/07-auth-security.md` 섹션 5
