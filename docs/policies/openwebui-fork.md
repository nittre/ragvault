# Open WebUI Fork 정책

> Phase 0 UX 결정 누적으로 Open WebUI Fork 영역이 커짐.
> **Fork 범위 명시·upstream 동기화 전략·Fork 깊이 최소화 원칙**.

## 배경

[CLAUDE.md](../../CLAUDE.md) + ADR + UX 점검 결과:
- ADR-0006: 백엔드 프록시 X-User-* 헤더 주입
- ADR-0010: 원본 응답 short-lived storage (admin UI 조회)
- user-journeys.md S1~S10: 클립 버튼·출처 카드·좋아요/싫어요 사유 폼·"이 답변 다시" 토스트 등
- 09-user-parameter-tuning.md: 사이드 패널 13개 파라미터

→ Fork 변경 파일 ~20+ 예상. **upstream 동기화 부담** 매우 큼.

## 원칙

```
[Fork 깊이 최소화]
- Open WebUI 핵심 로직(인증·대화 관리·세션·DB)은 절대 수정 안 함
- 변경은 "추가" 위주 (extension), "교체" 최소화

[upstream 동기화]
- 분기마다 (3개월) upstream merge
- Fork 한 파일이 upstream 에서 변경되면 conflict — 검토 후 cherry-pick

[변경 범위 추적]
- Git: feature branch `customer-rag-extensions` 단일 브랜치
- CHANGELOG.md 에 변경 파일·이유·관련 ADR 명시
```

## Fork 범위 — Backend (Python/FastAPI)

| 변경 영역 | 파일 (예상) | 이유 | 관련 ADR |
|---------|-----------|------|---------|
| `/v1/chat/completions` 프록시 | `backend/apps/openai/main.py` | X-User-* 헤더 주입 (브라우저 헤더 폐기 후 세션 사용자로 새로 구성) | ADR-0006 |
| `/v1/files` 프록시 | `backend/apps/openai/files.py` 신규 | 첨부파일 → Spring Boot `/v1/files` 라우팅 | requirements/10 |
| `/auth/verify` 엔드포인트 | `backend/apps/users/auth.py` | admin UI 의 세션 검증 호출용 | ADR-0009 |
| SES SMTP 환경변수 | `backend/config.py`, helm values | 메일 발송 (Open WebUI 기본 SMTP 설정 활용) | ADR-0014 (SES) |
| (선택) Functions/Filters | `backend/apps/webui/internal/db.py` 신규 모듈 | 만약 Plugin 방식 도입 시 — Phase 1+ 검토 |

## Fork 범위 — Frontend (Svelte)

| 변경 영역 | 파일 (예상) | 이유 | 관련 결정 |
|---------|-----------|------|---------|
| 사이드 패널 13 파라미터 | `src/lib/components/chat/Settings/Sidebar.svelte` 신규 + `src/lib/stores/params.ts` 신규 | 09 문서 — RAG 파라미터 튜닝 UI | requirements/09 |
| 클립 버튼 (📎) | `src/lib/components/chat/MessageInput.svelte` | 파일·이미지 통합 첨부 (Open WebUI 기본 + 메뉴 확장) | user-journeys.md S6/S7 |
| 출처 카드 | `src/lib/components/chat/Messages/Citations.svelte` 신규 | Top-K 전부, 점수 비노출, [N] 인라인 인용 | user-journeys.md S2 M2-6 |
| 좋아요/싫어요 사유 폼 | `src/lib/components/chat/Messages/Feedback.svelte` | 카운트 + mini-form ("정확하지 않음·부족함·무관함·기타") | user-journeys.md S2 M2-7 |
| "이 답변 다시 받기" 토스트 | `src/lib/components/common/Toast.svelte` 확장 | 모델·파라미터 변경 시 즉시 재생성 액션 | user-journeys.md S9 M9-6/M9-7 |
| 의도 분류 라벨 | `src/lib/components/chat/Messages/IntentBadge.svelte` 신규 | `📊 SQL` / `📄 RAG` / 등 | user-journeys.md S3 M3-3 |
| 상태 페이지 링크 | `src/lib/components/common/ErrorBanner.svelte` | Fallback 카드의 [상태 페이지] 링크 | user-journeys.md S10 M10-4 |
| 페이지 title + favicon | `src/app.html`, `static/favicon.ico` | 화이트라벨 (Phase 0 최소) | user-journeys.md S1 #4 |
| Empty State | `src/lib/components/chat/EmptyState.svelte` | **내부 사용자 대상이라 기본 그대로** (S1 #9 결정) — 변경 X | user-journeys.md S1 #9 |
| 파일 첨부 미리보기 카드 | `src/lib/components/chat/AttachmentPreview.svelte` | 다중 카드 + 누적 크기 (`8MB / 100MB`) | user-journeys.md S6 M6-2 |
| 응답 출처 메타 카드 (파일·이미지·URL) | 위 Citations 확장 또는 신규 컴포넌트 | 메타데이터 + 썸네일 + 다운로드 | user-journeys.md S5~S7 |

## Fork 범위 — Admin Web UI (별도)

```
admin Web UI 는 Open WebUI Fork 아님 — 별도 SPA (React 또는 Thymeleaf)
경로: customera.ragservice.com/admin/*
인증: Open WebUI 세션 검증 API 호출 (N2 결정)
```

→ ADR-0009 참고. **별도 작업 영역**.

## upstream 동기화 전략

```
[브랜치 구조]
upstream/main          ← Open WebUI 원본
fork/main              ← Open WebUI 원본 mirror (fetch 만)
fork/customer-rag      ← 우리 변경 적용 (단일 feature branch)

[정기 동기화 (분기)]
1. fork/main pull upstream/main
2. fork/customer-rag rebase fork/main
3. Conflict 발생 시:
   - 우리 변경 파일과 upstream 변경 파일 비교
   - 우리 의도 보존 + upstream 개선 흡수
4. 회귀 테스트 (스모크: 로그인 + 채팅 + 파일 첨부 + 사이드 패널)
5. 통과 시 fork/customer-rag merge

[긴급 보안 패치 (upstream)]
1. fork/main 즉시 pull
2. fork/customer-rag rebase + 회귀 테스트 후 즉시 배포
```

## Fork 회피 패턴 (Phase 1+ 검토)

```
1. Open WebUI Functions/Filters Plugin API
   - 우리 백엔드 프록시·UI 확장을 Plugin 으로 분리
   - upstream 동기화 부담 ↓
   - 단점: Plugin API 안정성 검증 필요
   - Phase 1+ 검토

2. iframe / Web Components
   - admin UI 처럼 별도 SPA 로 분리
   - Open WebUI 내장 없이 외부 호출
```

## 변경 추적

```
[CHANGELOG.md 필수 항목]
- 각 PR 의 변경 파일·이유·관련 ADR
- upstream version 추적 (예: v0.3.42 기반 fork)
- 동기화 일자·conflict 해결 메모

[Git 컨벤션]
- Commit 메시지 prefix:
  · [fork/backend]   ← 백엔드 프록시
  · [fork/frontend]  ← Svelte 컴포넌트
  · [sync/upstream]  ← upstream merge
  · [test]           ← 회귀 테스트
```

## 작업 시작 전 체크리스트

```
☐ Open WebUI upstream 버전 결정 (예: v0.3.42)
☐ fork 브랜치 구조 생성
☐ Backend 변경 영역 PR 1 (X-User-* 주입)
☐ Frontend 변경 영역 PR N개 (사이드 패널·클립·출처·...)
☐ 회귀 테스트 시나리오 정의 (스모크 6~8개)
☐ CHANGELOG.md 초기화
☐ Helm 차트의 image tag (fork-v0.3.42-rag1 등) 명명 규칙
```

## 참고

- 권위 결정 출처: ADR-0006, ADR-0009, ADR-0010, user-journeys.md 전체, 09-user-parameter-tuning.md
- Phase 0 일정 영향: 이미 ADR-0009 + N7 N8 결정에 포함됨 (~4.5~4.7개월)
- Fork 회피 옵션: Phase 1+ Functions/Filters Plugin 검토
