# ragvault

사내 RAG 서비스(**app-internal**)와 외부 임베드 위젯(**app-widget**)을 **하나의 코드베이스**로 통합한 모노레포.
공유 `core` + 제품별 app 모듈 + 계층형 인프라 구조(모듈러 모놀리스 / 제품 라인).

> 배경·전체 재구성 로드맵: `~/.claude/plans/snazzy-finding-bachman.md`

## 구조 (Phase 0 — composite build)

```
ragvault/
├── settings.gradle      # composite build (includeBuild)
├── core/                # 공유: RAG·text-to-sql·웹검색·embedding·문서 파싱·AccessPolicy
├── app-internal/        # 사내 RagVault   (구 rag-practice/rag-backend)
│                        #   JWT/ApiKey 인증, 전체 기능
├── app-widget/          # 외부 임베드 위젯 (구 ragvault-chatbot/backend)
│                        #   site-key 인증, 지식문서(멀티포맷) 관리, RAG→점진 확장
├── frontend/            # 프론트엔드 (각 앱 자체 Dockerfile 독립 빌드)
│   ├── widget-admin/    #   위젯 어드민 SPA   (구 ragvault-chatbot/admin)
│   └── widget-embed/    #   위젯 임베드/데모  (구 ragvault-chatbot/widget)
└── infra/               # compose.base + 제품별 overlay
```

원본 repo(`rag-practice`, `ragvault-chatbot`)는 **히스토리 아카이브로 보존**한다.
백엔드·프론트엔드 모두 현재 워킹트리를 그대로 import 한 상태(빌드 환경 동일: Spring Boot 3.5.0 / Java 21 / Spring AI 1.0.0).
`internal` 프론트엔드(`rag-practice/rag-frontend`, chat+admin 통합 SPA)는 후속 이관 예정.

## 빌드 (각 app 독립)

```bash
cd app-internal && ./gradlew build    # 사내
cd app-widget   && ./gradlew build    # 위젯
```

## 진행 단계

- [x] **Phase 0** — 모노레포 스캐폴드 + 두 backend 흡수 + 독립 빌드 그린
- [ ] **Phase 1** — 무손실 코어 추출 (diff≈0 순수 클래스)
- [ ] **Phase 2** — fork된 로직 클래스 reconciliation
- [ ] **Phase 3** — AccessPolicy(default-deny) 도입 — dataScope 기반 데이터 노출 제어
- [ ] **Phase 4** — 제품 전용 코드 확정
- [ ] **Phase 5** — 인프라 계층화 (compose.base + 제품별 overlay)

### 핵심 보안 불변식
> 외부 위젯(app-widget)은 **사내 datasource·PII 에 절대 닿지 않는다.**
> 단 고객사(외부) datasource 대상 text-to-sql·웹검색은 허용 — 경계는 *capability* 가 아니라 *dataScope* 로 집행한다.
