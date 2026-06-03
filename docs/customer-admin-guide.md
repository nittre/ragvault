# RAG 서비스 고객사 Admin 가이드

> 버전: Phase 0 | 최종 수정: 2026-05-28
> 대상: 고객사 IT 관리자 (시스템 admin 권한 보유자)

## Admin 패널 접속

URL: `https://[회사도메인].ragservice.com/admin`
권한: Open WebUI에서 **admin** 역할이 부여된 계정만 접속 가능

---

## 1. 사용자 관리 (`/admin/users`)

### 개별 사용자 추가

1. API Keys 화면에서 **신규 발급** 클릭
2. 이메일·Scope 입력 후 발급
3. **발급된 API Key는 1회만 표시** — 즉시 사용자에게 전달

### 일괄 추가 (CSV)

CSV 형식: `email,scope` (첫 줄 헤더)

```csv
email,scope
user1@company.com,api:chat
user2@company.com,api:chat
```

1. 사용자 관리 화면 → **CSV 업로드**
2. 파일 선택 후 처리 결과 확인

### Scope 종류

| Scope | 권한 |
|-------|------|
| `api:chat` | 일반 채팅만 |
| `api:admin` | Admin 패널 접근 |
| `api:incident-response` | 원본 응답 조회 (30분 TTL) |

---

## 2. RAG 테이블 등록 (`/admin/rag-tables`)

회사 MySQL 테이블을 RAG 검색 대상으로 등록합니다.

### 등록 절차

1. **테이블명**: MySQL 테이블 이름 (예: `products`)
2. **컨텐츠 컬럼**: 검색 대상 컬럼 (콤마 구분, 예: `name,description,content`)
3. **PK 컬럼**: 기본키 (기본값: `id`)
4. **청크 크기**: 텍스트 분할 크기 (기본값: 500 토큰)
5. **등록 + 동기화 시작** 클릭

> `data_sensitivity = restricted` 테이블은 등록이 거부됩니다 (ADR-0002)

### 동기화 주기

- 자동: **30분마다** binlog 기반 증분 동기화 (ADR-0001)
- 수동: Sync 화면에서 즉시 실행 가능

---

## 3. SQL 테이블 등록 (`/admin/sql-tables`)

자연어 SQL 조회 대상 테이블을 등록합니다.

### 등록 시 주의사항

- **제외 컬럼**: PII 포함 컬럼 반드시 제외 (주민등록번호, 전화번호 등) (ADR-0007)
- **샘플 쿼리**: 자주 사용하는 질문 패턴 예시 (SQL 생성 품질 향상)

---

## 4. DDL 이벤트 처리 (`/admin/ddl-events`)

MySQL 스키마 변경(DDL) 발생 시 등록된 테이블에 영향을 분석합니다.

### 위험도별 대응

| 위험도 | 내용 | 조치 |
|--------|------|------|
| LOW | 컬럼 추가 등 영향 적음 | 자동 처리 |
| MEDIUM | 컬럼명 변경 등 | 7일 내 admin 확인 |
| HIGH | 테이블 삭제·컬럼 제거 | 즉시 수동 처리 필요 |

Discord `#ddl-alerts` 채널에서 알림 수신 → Admin 패널에서 처리

---

## 5. 감사 로그 조회 (`/admin/audit-logs`)

### 조회 필터

- 사용자 이메일, Action 유형, 기간 필터 제공
- **원본 응답 조회**: `api:incident-response` scope 필요, **발생 30분 이내만** 가능 (ADR-0010)

### PII 관련 사고 대응

1. 사용자 신고 수신 즉시 response_id 확인
2. Admin 패널 → 감사 로그 → 원본 조회
3. PII 노출 여부 확인 후 RAG SaaS 팀 보고

---

## 6. 검색 설정 (`/admin/search-config`)

전체 사용자에게 적용되는 기본 검색 파라미터를 조정합니다.

> 사용자가 개별 파라미터를 조정한 경우 사용자 설정이 우선 적용됩니다 (ADR-0005 7단계 우선순위)

---

## 7. 파라미터 한도 (`/admin/param-limits`)

사용자가 조정 가능한 파라미터의 범위를 제한합니다.

| Guard 유형 | 설명 |
|----------|------|
| **Guard A** | 범위 클램핑 (min~max 사이로 강제) |
| **Guard B** | 강제 고정 (사용자 조정 불가) |

---

## 8. 주기적 점검 사항

### 일간

- [ ] Discord #warning-alerts 확인
- [ ] `/status` 페이지 모든 컴포넌트 정상 확인

### 주간

- [ ] 신규 DDL 이벤트 처리 완료 확인
- [ ] 사용량 통계 검토 (`/admin/usage-stats`)

### 월간

- [ ] 비활성 API Key 폐기
- [ ] audit_log 이상 패턴 검토

---

## 긴급 연락처

| 상황 | 연락처 |
|------|--------|
| 시스템 장애 | Discord #critical-alerts |
| PII 노출 의심 | RAG SaaS 팀 즉시 연락 |
| 보안 사고 | [보안 담당자 이메일] |

[시스템 상태 페이지](/status) | [사용자 가이드](/docs/user-guide.md)
