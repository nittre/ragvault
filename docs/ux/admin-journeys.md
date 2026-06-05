# Customer Admin Journeys — Phase 0

> 시니어 UX 리서처 Task Efficiency 분석 (2026-05-21).
> Power User 페르소나 — 영화 분해 대신 효율·정확성·실수 방지 중심.

## 분석 프레임 — Task Efficiency 7축

```
1. Task 정의       — Job to be done
2. Clicks-to-Complete — 최소 클릭 수
3. Time-on-Task    — 평균·최악 시간
4. Error Prevention — 잘못 누르면 무엇이 일어나나
5. Undo / Recovery — 실수 후 되돌릴 수 있나
6. Batch / Bulk    — N건 처리 시 합리적 시간 증가
7. Discoverability  — 처음 admin 이 어떻게 찾는가
```

## 페르소나

```
주인공: 박 OO, 42세, 고객사 IT 관리자
배경:   IT 친숙도 높음 (기존 사내 시스템 N개 관리 경험)
빈도:   주 2~5회 사용. Task 단위 짧고 명확.
도구:   사내 노트북 Chrome, 듀얼 모니터, 회사 메신저, Discord
```

## 시나리오 5개 + 묶음 2개

| # | 시나리오 | 빈도 | 영향 |
|---|---------|------|------|
| A1 | 첫 admin 인계 받음 | 1회 (온보딩) | 매우 큼 (모든 admin task 의 전제) |
| A2 | 새 직원 사용자 추가 | 월 5~10건 + 분기 입사 대량 | 중간 (batch 영역) |
| A3 | RAG 테이블 등록 | 월 1~2건 | 큼 (data_sensitivity 가드) |
| A4 | SQL 테이블 등록 | 월 0~1건 | 큼 (excluded_columns) |
| A8 | DDL 이벤트 처리 | 월 0~3건 | 매우 큼 (RAG 품질 영향) |
| A10 | Audit Log 조회 | 주 1회 + 신고 대응 | 중간 (사고 추적) |
| A5~A7, A9 | 검색 설정·파라미터 한도·통계·API Key 회전 | 분기~연 | 작음 (admin REST API 패턴) |

---

# A1: 첫 admin 인계 받음

## Task 정의

```
[Job to be done]
관리자가 서버 세팅 후
고객사 admin 에게 시스템 운영 권한을 안전하게 인계.

[성공 조건]
- 고객사 admin 이 모든 admin task 수행 가능
- 우리 회사 초기 계정 비활성화 (감사 추적용 보존)
- 인계 후 우리는 read-only 또는 무권한
```

## 흐름

```
[T1.1] 우리가 고객사 admin 계정 추가 (Open WebUI Admin Panel)
[T1.2] SES 임시 비밀번호 + 운영 가이드 메일 발송
[T1.3] 고객사 admin 첫 로그인 (S1 흐름 + admin 권한)
[T1.4] 고객사 admin 이 /admin 진입 → admin UI 둘러봄
[T1.5] 7일 grace period 후 우리 초기 계정 자동 비활성화
```

## 결정사항

| 영역 | 결정 |
|------|------|
| **Phase 0 Admin UI 존재 여부** | **A. REST API + 간단한 Web UI** ★ ADR-0009 — Phase 0 일정 +2~3주 (→ 4.5~4.7개월) |
| **Admin UI 진입 경로** | A. `customera.ragservice.com/admin/*` — 같은 도메인 별도 경로 |
| 인계 시점 | 7일 grace period — 우리 초기 계정 7일 후 자동 비활성화 cron |
| Onboarding 자료 | `docs/customer-admin-guide.md` 1페이지 + 인계 메일에 링크. Phase 1+ 인앱 튜토리얼 |
| Admin 권한 회수 | 고객사 admin 이 admin Panel 에서 다른 admin 추가 + 본인 해제 가능. **자기 자신 마지막 admin 일 때 해제 X**. 비상 시 Role |
| 다중 admin | Phase 0 다중 admin 가능 (Open WebUI 기본). 부서별 권한은 Phase 1+ |

---

# A2: 새 직원 사용자 추가

## Task 정의

```
[Job to be done]
신규 입사자에게 시스템 계정 부여 + 임시 비밀번호 메일 자동 발송.

[빈도]
- 평상시: 월 5~10건 단일 추가
- 분기 신규 입사 대량: 5~20명 (3·6·9·12월 첫 주)
```

## 흐름

```
[T2.1] /admin/users 진입
[T2.2] "+ 사용자 추가" 또는 "CSV 업로드"
[T2.3] 폼/CSV 입력 (이메일·이름·역할)
[T2.4] 저장 → SES 자동 발송
[T2.5] 신규 사용자 첫 로그인 (S1 흐름)
```

## Task Efficiency

```
단일 추가:    3 클릭 / 1~2분
CSV 업로드:   5 클릭 / 5분 (15명 일괄)
1명당 평균:   ~2분 (단일) / ~20초 (CSV)
```

## 결정사항

| 영역 | 결정 |
|------|------|
| **Batch 추가 (CSV)** | A. **Phase 0 부터 CSV 업로드** ★. 폼 (`email,name`). 서버 검증 (이메일 형식·중복) 후 일괄 생성 + 자동 SES 메일. +1주 작업 (총 일정 ~4.5~4.7개월) |
| **사용자 목록 검색·필터·정렬** | A. **검색바 + 필터 + 정렬** — 검색 (이메일·이름), 필터 (role: admin/user, status: active/inactive), 정렬 (가입일·이름) |
| 도메인 검증 | Phase 0 화이트리스트 없음 (admin 책임). 등록 폼에 "외부 이메일 주의" 안내만 |
| SES 메일 발송 옵션 | 항상 자동 (디폴트). "메일 발송 안 함" 옵션은 Phase 1+ |
| 비활성화 vs 삭제 | **디폴트 비활성화** (audit log 보존). 명시 "삭제" 버튼 별도 (GDPR 대응). 삭제 시 audit log 의 user_email masking 후 보존 |
| 마지막 admin 보호 | 시스템 강제 — 마지막 admin 의 자기 자신 user 강등·비활성화·삭제 거부 + 명확한 에러 |
| 권한 변경 audit | 변경 가능 + audit log 기록 (`action=user_role_change`, before/after) |
| 사용자 그룹 | Phase 0 모든 사용자 `['all']` (ADR-0002). 그룹 UI Phase 1+ |

---

# A3·A4 (묶음): RAG·SQL 테이블 등록

## Task 정의

```
[A3 - Job to be done]
고객사 admin 이 새 MySQL 테이블을 RAG 대상으로 등록 → 자동 동기화 시작

[A4 - Job to be done]
고객사 admin 이 새 MySQL 테이블을 SQL 조회 대상으로 등록 → Text-to-SQL 활용

[공통 패턴]
admin UI → 테이블 관리 → 등록 폼 → 가드 검증 → 동기화/캐시 갱신
```

## 흐름 (A3 기준)

```
[T3.1] /admin/rag-tables 진입
[T3.2] "+ 테이블 등록"
[T3.3] 폼 입력 (필드 11개)
[T3.4] 등록 가드 (data_sensitivity restricted 거부, internal 알람)
[T3.5] 모달 — "지금 초기 동기화 시작?"
[T3.6] 동기화 진행 모니터링 (sync_jobs 폴링)
```

## 결정사항

| 영역 | 결정 |
|------|------|
| **MySQL 스키마 자동 조회 (A3-1/A4-1)** | A. **자동 조회 + 체크박스 + PII 자동 추천** ★. 테이블 드롭다운 + 컬럼 체크박스 + PK/FK 자동 감지 + PII 컬럼 (이름·email·phone) `excluded_columns` 자동 추천 |
| **content_columns 멀티 셀렉트 (A3-2)** | A. **체크박스 + 순서 드래그** — LLM 프롬프트 결합 순서. React DnD |
| **등록 후 초기 동기화 (A3-3)** | A. **모달 confirmation** — "테이블이 등록됐습니다. 지금 동기화? (예상 N분 / 30분 cron 대기)" [지금 시작][나중에] |
| data_sensitivity restricted | 클라이언트 측 즉시 비활성 + 에러 ("Phase 1+ 부서별 권한 후 가능. 영업 트랙 문의") |
| 동기화 진행 모니터링 | `/admin/sync-jobs/{job_id}` 페이지 — 폴링 5초 (Phase 0). SSE는 Phase 1+ |
| 중복 등록 검증 | 폼 제출 시 서버 검증. 클라이언트 측 실시간은 Phase 1+ |
| 비활성화 vs 삭제 | 비활성화=청크 보존 (재활성 시 재사용). 삭제=CASCADE (청크도 삭제) — 두 버튼 분리 |
| **A4 sample_queries 입력 (A4-2)** | 폼 wizard ({질문, SQL} 추가 식). JSON 직접은 "고급 모드" 토글 |
| **A4 FK 자동 감지 (A4-3)** | 자동 감지 표시. admin 이 수동 추가·삭제 가능 |
| **A4 Read-only DB 권한 검증 (A4-4)** | 등록 시 자동 검증 (SELECT 권한 확인 쿼리) → 실패 시 에러 |

---

# A8: DDL 이벤트 처리

## Task 정의

```
[Job to be done]
회사 MySQL DDL 발생 → 30분 cron 감지 → ddl_events 기록 + Discord 알람 →
admin 이 위험도별 결정 (무시·설정 업데이트·재동기화)

[빈도]
- LOW (CREATE TABLE 등): 월 0~5건 → 자동 처리
- MEDIUM (ADD COLUMN NOT NULL 등): 월 0~2건 → 7일 안 결정 또는 자동 fallback
- HIGH (DROP COLUMN/RENAME 등): 월 0~1건 → admin 수동 결정 필수

[성공 조건]
- HIGH 가 7일 이상 미결정으로 쌓이지 않음
- 잘못된 결정으로 RAG 품질 갑자기 저하 X
```

## 흐름

```
[T8.1] Discord 알람 받음 (#alerts-warning 또는 #alerts-critical)
[T8.2] 알람 deep link 클릭 → /admin/ddl-events/{id}
[T8.3] 자동 영향 분석 패널 확인
[T8.4] 위험도별 wizard — 추천 액션 + 3 버튼 (무시·설정·재동기화)
[T8.5] action_taken·notes 기록 → processed_at 설정
[T8.6] 결정 후속 동작 (재동기화 시 sync_jobs)
```

## 결정사항

| 영역 | 결정 |
|------|------|
| **변경 영향 미리보기 (A8-1)** | A. **자동 영향 분석 패널** ★. ① 영향 받는 rag_table_config (content_columns 일치), ② 영향 받는 청크 수, ③ 지난 30일 사용자 검색 빈도, ④ 재동기화 예상 시간 |
| **결정 액션 3종 UX (A8-2)** | A. **위험도별 추천 + wizard** — LOW: 정보만 / MEDIUM: 추천 액션 + 3 버튼 / HIGH: 경고 + 영향 강조. notes 입력 옵션 |
| **Discord 알람 deep link (A8-3)** | A. **Deep link 포함** ★. 메시지에 `/admin/ddl-events/{id}` URL. 클릭 즉시 상세 |
| 카운트다운 표시 | "MEDIUM — 5일 12시간 남음" + 24h 미만 붉은색 |
| 목록 정렬·필터 | 디폴트 HIGH→MEDIUM→LOW (시간 역순). 필터 4종 (위험도·테이블·처리 상태·기간) |
| 알람 반복 | Phase 0 = 발생 시 1회. Phase 1+ = 일일 다이제스트 메일 |
| Batch 처리 | 체크박스 + 일괄 "무시·재동기화" 액션 가능 |
| RAG·SQL 양쪽 영향 | 상세 화면에 두 영향 분리 표시 |

## 와이어프레임 (DDL 상세 화면)

```
┌─────────────────────────────────────────────────────────────┐
│ DDL 이벤트 #12345                                  [← 목록] │
├─────────────────────────────────────────────────────────────┤
│ 🔴 HIGH — 사람 결정 필수 (자동 적용 안 함)                 │
│ 발생: 2026-05-21 09:42 (30분 cron 감지)                    │
│                                                             │
│ SQL:                                                        │
│   ALTER TABLE products DROP COLUMN description;             │
│                                                             │
│ 📊 자동 영향 분석                                          │
│ ┌─────────────────────────────────────────────────────┐   │
│ │ RAG 영향:                                            │   │
│ │   • rag_table_config: products → content_columns    │   │
│ │     에 'description' 포함 ⚠️                         │   │
│ │   • 영향 청크: 12,345 개                             │   │
│ │   • 지난 30일 검색 빈도: 4,231 회                   │   │
│ │                                                      │   │
│ │ SQL 영향:                                            │   │
│ │   • sql_table_config: products (allowed_columns 영향)│  │
│ │                                                      │   │
│ │ 재동기화 예상 시간: 약 35분                          │   │
│ └─────────────────────────────────────────────────────┘   │
│                                                             │
│ 💡 추천 액션                                               │
│   HIGH 위험: 신중 결정 필요. 일반적으로 다음 중 하나:        │
│   ① content_columns 에서 description 제거 + 재동기화        │
│   ② 일시적 비활성화 후 대체 컬럼 확인                       │
│                                                             │
│ [무시 (영향 없음 확인)]                                    │
│ [설정 업데이트] → rag_table_config 편집 폼                 │
│ [강제 재동기화] → 확인 모달 (예상 35분)                    │
│                                                             │
│ 비고 (옵션):                                                │
│ [______________________________________________]            │
│                                                             │
│                                            [취소] [결정]   │
└─────────────────────────────────────────────────────────────┘
```

---

# A10: Audit Log 조회

## Task 정의

```
[Job to be done]
사용자 신고·보안 의심·정기 감사 시 audit_logs 조회·분석

[Triggers]
- 사용자 신고: 월 1~3건
- 보안 의심: 인증 실패 다수 / 인젝션 패턴
- 정기 감사: 월·분기 (PIPA 대응)
```

## 흐름

```
[T10.1] /admin/audit-logs 진입
[T10.2] 필터 입력 (사용자·시간·액션·상태·error_id·의도·응답 시간)
[T10.3] 결과 목록 (페이지네이션)
[T10.4] 특정 로그 클릭 → 상세
[T10.5] 필요 시 CSV export
```

## 결정사항

| 영역 | 결정 |
|------|------|
| **필터 옵션 (A10-1)** | A. **필수 5종 + 의도 + 응답 시간** ★. 사용자/시간/액션/상태/error_id + 의도(RAG/SQL/...) + 응답 시간 범위. IP·API Key 는 Phase 1+ |
| **응답 본문 접근 (A10-2)** | A. **CloudWatch Logs 직접 접근** ★. 응답 본문은 CloudWatch (30일 보존). admin 이 콘솔/CLI 로 검색. audit_log 에 `correlation_id` 표시. PII 위험은 IAM 제한 |
| Export | CSV (최대 10,000건, 동기). Excel·JSON 은 Phase 1+ |
| 보존 만료 알림 | Phase 0 명시 없음. Phase 1+ 만료 임박 알림 + S3 archive |
| 사용량 통계 vs Audit | **별도 대시보드** — `/admin/usage-stats` (집계) ≠ `/admin/audit-logs` (상세). Grafana 통합은 Phase 1+ |
| 사용자별 history | A2 사용자 row 클릭 → "이 사용자의 audit log" 필터 자동 적용 |
| 의심 패턴 자동 감지 | Phase 0 수동만. Phase 1+ Grafana 대시보드 |

---

# 🆕 점검의 가장 큰 발견

## Phase 0 Admin Web UI 도입 결정

**A1-1 의 결정이 Phase 0 전체 일정·작업량에 가장 큰 영향**:

```
[변경 전 — 시니어 백엔드 가정]
admin = REST API + CLI/Postman. Web UI 는 Phase 1+

[변경 후 — 시니어 UX 점검 결과]
admin = REST API + 간단한 Web UI (Phase 0). 
일정: 약 3.5~4개월 → 약 4.5~4.7개월

[이유]
- 고객사 admin Power User 라도 GUI 가 첫인상·신뢰감 결정적
- CLI 만으로 영업 어려움 (첫 고객사 매우 critical)
- DDL 처리·사용자 batch·테이블 등록 등 복잡 task 가 GUI 없이는 운영 부담 큼
- Open WebUI 가 admin 영역을 못 다룸 — 별도 작업 불가피

[작업 추가]
- /admin/* React (또는 Thymeleaf) SPA 라우트
- 화면 7개 (users, rag-tables, sql-tables, search-config, ddl-events, audit-logs, usage-stats, api-keys)
- Spring Boot REST API 확장 (이미 명시된 admin API 그대로 활용)
- +2~3주 일정
```

→ **ADR-0009 으로 backfill** (다음 단계).

---

# Phase 1+ 후보 (Customer Admin 영역)

| 영역 | Phase 1+ 후보 |
|------|--------------|
| 인앱 튜토리얼 | admin onboarding 자체 학습 (A1) |
| 도메인 화이트리스트 | 이메일 도메인 검증 자동화 (A2) |
| SES 발송 옵션 | "메일 발송 안 함" 옵션 (A2) |
| 부서별 그룹 UI | access_groups 활용 (A2) |
| 클라이언트 실시간 검증 | 폼 입력 중 중복 검증 (A3) |
| SSE 동기화 진행 | 폴링 → SSE (A3) |
| 알람 다이제스트 | 일일 메일 (A8) |
| Audit Excel/JSON export | (A10) |
| 만료 임박 알림 + S3 archive | (A10) |
| Grafana 통합 대시보드 | 의심 패턴 자동 감지 (A10) |
| 응답 본문 신고 시점 보관 API | (A10) |

---

## 점검 메타데이터

- 점검자: 시니어 UX 리서처 + 사용자
- 점검 일자: 2026-05-21
- 결정 수: 모호함 40+ 건
- 가장 큰 발견: Phase 0 Admin Web UI 도입 (일정 +2~3주)

## 참고

- End User Journey: [`user-journeys.md`](user-journeys.md)
- 요구사항 권위 출처: [`requirements/`](../../requirements/)
- 모든 결정 기록: [`docs/adr/`](../adr/)
- 운영 정책: [`docs/policies/`](../policies/)
