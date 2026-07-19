# frontend/internal (챗 서비스 프론트엔드)

**사내 챗 서비스**(`app-internal`)의 프론트엔드 SPA 입니다. 임직원용 **채팅 UI** 와 관리자용 **어드민 콘솔** 을 하나의 앱에 통합했습니다. Nginx 컨테이너로 서빙되며 `rag-frontend:latest` 이미지로 배포됩니다(ADR-0003: 프론트엔드 모노레포 통합).

- 이미지: `rag-frontend:latest` · 로컬 노출 포트 `18080`
- 백엔드: [app-internal](../../app-internal/README.md) (`:8080`)

---

## 주요 기능

- **채팅** (`src/pages/ChatPage.tsx`, `src/components/chat`) — RAG 답변 스트리밍/표시, 마크다운(react-markdown + remark-gfm) 렌더, 출처 표기, 파일/이미지 업로드(heic2any 로 HEIC 변환)
- **로그인** (`src/pages/LoginPage.tsx`) — JWT 인증
- **설정** (`src/pages/SettingsPage.tsx`) — 비밀번호 변경
- **어드민 콘솔** (`src/pages/admin/`) — 사용자, API Key, 데이터소스, DDL 이벤트, 지식/지식문서, 마스킹 규칙, 파라미터 한도, RAG/SQL 테이블, SQL 로그, 감사 로그, 사용 통계 페이지

```
src/pages/admin/
  UsersPage · ApiKeysPage · DataSourcesPage · DdlEventsPage
  KnowledgePage · KnowledgeDocsPage · MaskingRulesPage · ParamLimitsPage
  RagTablesPage · SqlTablesPage · SqlLogsPage · UsageStatsPage · AuditLogsPage
```

---

## 기술 스택

| 항목 | 내용 |
|------|------|
| 프레임워크 | React 18.3 + TypeScript 5.6 |
| 빌드 | Vite 6 |
| 스타일 | Tailwind CSS 3.4 |
| 서버 상태 | TanStack React Query 5 |
| 클라이언트 상태 | Zustand 5 |
| 라우팅 | React Router 6 |
| HTTP | axios |
| 마크다운 | react-markdown + remark-gfm |
| 아이콘 | lucide-react |
| 기타 | heic2any(HEIC→이미지), uuid, clsx |

---

## 아키텍처

```
src/
├── main.tsx / App.tsx    엔트리 · 라우팅
├── pages/                ChatPage, LoginPage, SettingsPage, admin/*
├── components/           chat/ · admin/ · common/
├── api/                  axios API 클라이언트 (백엔드 호출)
├── stores/               Zustand 스토어 (인증·UI 상태)
├── types/                TypeScript 타입
├── utils/                TypeScript 유틸(ragParamKeys 등)
└── index.css             Tailwind
```

빌드 산출물(`dist/`)은 Nginx 가 서빙하고, `/api`·`/v1` 요청은 `nginx.conf` 가 백엔드로 프록시합니다.

---

## 구동

```bash
npm install
npm run dev       # Vite 개발 서버
npm run build     # tsc -b && vite build → dist/

# 컨테이너 (nginx 서빙)
docker build -t rag-frontend:latest .
```
