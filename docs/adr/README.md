# Architecture Decision Records (ADR)

ragvault 프로젝트의 중요한 아키텍처·정책 결정을 기록한다.

## ADR 정책

- **불변 (Immutable)**: 한 번 Accepted 된 ADR은 수정하지 않는다. 변경이 필요하면 새 ADR 작성 → `Supersedes ADR-NNNN`.
- **번호 부여**: `NNNN-kebab-case-slug.md` (4자리 zero-pad)
- **단일 출처 원칙**: ADR은 결정의 권위 출처. 코드·인프라와 충돌 시 ADR 우선, 후속 갱신 필요.

## ADR 목록

| # | 제목 | 상태 | 결정일 |
|---|------|------|--------|
| [0001](0001-multiformat-document-parser.md) | 멀티포맷 문서 파서 전략 — Apache Tika + opendataloader-pdf | Accepted | 2026-06-30 |
| [0002](0002-image-captioning-vectorization.md) | 이미지 벡터화 전략 — 비전 모델 캡셔닝 후 텍스트 임베딩 | Accepted | 2026-06-30 |
| [0003](0003-frontend-monorepo-integration.md) | 프론트엔드 모노레포 통합 — 외부 레포 의존 제거 | Accepted | 2026-06-30 |
| [0004](0004-chat-service-knowledge-docs.md) | 챗 서비스 지식문서 관리 — SOURCE_TABLE·API·디렉토리 분리 전략 | Accepted | 2026-06-30 |
| [0005](0005-qwen25vl-unified-model.md) | qwen2.5vl:7b 단일 멀티모달 모델 통합 | Accepted | 2026-06-30 |
| [0006](0006-pdf-ocr-fallback.md) | PDF 텍스트 추출 실패 시 Tesseract OCR 폴백 | Accepted | 2026-07-03 |
| [0007](0007-multiturn-rag-retrieval.md) | 멀티턴 RAG — 검색 쿼리 재작성 및 WEB_SEARCH 폴백 | Accepted | 2026-07-03 |
| [0008](0008-pii-masking-all-response-paths.md) | PII 마스킹 원칙 — 모든 LLM 응답 경로에 STANDARD 마스킹 적용 | Accepted | 2026-06-30 |
| [0009](0009-phase0-admin-web-ui.md) | Phase 0 Admin Web UI — 계정 발급 메일·비밀번호 재설정·임베디드 관리자 셸 | Accepted | 2026-06-30 |
| [0010](0010-response-raw-storage.md) | LLM 원본 응답 단기 저장소 — PII 마스킹 실패 진단 | Accepted | 2026-06-30 |
| [0011](0011-self-issued-jwt-auth.md) | 자체 발급 JWT 인증으로 전환 (Open WebUI 세션 제거) | Accepted | 2026-06-30 |
