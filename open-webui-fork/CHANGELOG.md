# Open WebUI Fork CHANGELOG

base: ghcr.io/open-webui/open-webui:v0.6.5
branch: customer-rag-extensions
date: 2026-05-28

## [fork/backend] ADR-0006: X-User-* 헤더 서버 주입
- 파일: backend/apps/openai/main.py
- 이유: 클라이언트 헤더 위변조 방지, 세션 기반 사용자 식별

## [fork/backend] /v1/files Spring Boot 프록시 (신규)
- 파일: backend/apps/openai/files.py (신규)
- 이유: 파일 업로드를 Spring Boot /v1/files 로 라우팅

## [fork/backend] /auth/verify Admin UI 세션 검증 (ADR-0009)
- 파일: backend/apps/users/auth_verify.py (신규)
- 이유: Admin SPA 인증 게이트 역할

## [fork/frontend] 사이드 패널 13 파라미터 (requirements/09)
- 파일: frontend/src/lib/components/chat/Settings/Sidebar.svelte
- 이유: RAG 파라미터 사용자 직접 조정

## [fork/frontend] 출처 카드 (user-journeys.md S2)
- 파일: frontend/src/lib/components/chat/Messages/Citations.svelte

## [fork/frontend] 좋아요/싫어요 사유 폼
- 파일: frontend/src/lib/components/chat/Messages/Feedback.svelte

## [fork/frontend] 의도 분류 라벨
- 파일: frontend/src/lib/components/chat/Messages/IntentBadge.svelte

## [fork/frontend] "이 답변 다시 받기" 토스트 (user-journeys.md S9)
- 파일: frontend/src/lib/components/common/Toast.svelte

## [fork/frontend] Fallback 카드 + SSE 단절 표시
- 파일: frontend/src/lib/components/common/ErrorBanner.svelte

## [fork/frontend] 파일 첨부 미리보기 카드 (user-journeys.md S6)
- 파일: frontend/src/lib/components/chat/AttachmentPreview.svelte

## [fork/frontend] 클립 버튼 파일·이미지 통합 입력
- 파일: frontend/src/lib/components/chat/MessageInput.svelte
