# frontend/widget-admin (위젯 서비스 어드민)

**위젯 서비스**(`app-widget`)를 운영하기 위한 **관리자 콘솔 SPA** 입니다. Site-Key 발급, 지식문서 관리, 고객 데이터소스·RAG/SQL 테이블 설정, 대화 로그·통계 확인 등을 수행합니다. Nginx 컨테이너(`widget-admin:latest`)로 서빙됩니다.

- 이미지: `widget-admin:latest` · 로컬 노출 포트 `5173`
- 백엔드: [app-widget](../../app-widget/README.md) (`:8081`, 어드민 API 는 JWT 쿠키 인증)

---

## 주요 기능 (`src/pages/admin/`)

- **Site-Key** (`SiteKeysPage`) — 위젯 삽입용 Site-Key 발급/관리
- **지식문서** (`KnowledgePage`) — 멀티포맷 문서 업로드·임베딩 관리
- **데이터소스** (`DataSourcesPage`) — 고객 DB 연결 등록/암호화 저장
- **RAG/SQL 테이블** (`RagTablesPage`, `SqlTablesPage`) — text-to-sql 대상 테이블 설정
- **쿼리 콘솔** (`QueryConsolePage`) — SQL 검증/실행 테스트
- **검색 설정** (`SearchConfigPage`) — top-k / threshold
- **마스킹** (`MaskingPage`) — PII 마스킹 규칙
- **대화 로그** (`ConversationsPage`) — 방문자 대화 이력
- **감사 로그 / 통계** (`AuditLogPage`, `StatsPage`)
- **사용자** (`UsersPage`), 로그인 (`LoginPage`)

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
| HTTP | axios (withCredentials — httpOnly JWT 쿠키) |
| 아이콘 | lucide-react |

---

## 아키텍처

```
src/
├── main.tsx / App.tsx    엔트리 · 라우팅
├── pages/                LoginPage, admin/*
├── components/           재사용 UI
├── api/                  axios API 클라이언트
├── stores/               Zustand 스토어
└── types/                TypeScript 타입
```

어드민 API 호출은 credentials(쿠키)를 포함하므로, 백엔드 `WIDGET_ADMIN_CORS_ORIGINS` 화이트리스트에 이 앱의 origin 이 등록되어야 합니다.

---

## 구동

```bash
npm install
npm run dev       # Vite 개발 서버 (:5173)
npm run build     # tsc -b && vite build → dist/

docker build -t widget-admin:latest .
```
