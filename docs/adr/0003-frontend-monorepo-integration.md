# ADR-0003: 프론트엔드 모노레포 통합 — 외부 레포 의존 제거

- **상태**: Accepted
- **결정일**: 2026-06-30
- **결정자**: 개발팀
- **관련 ADR**: -
- **영향 받는 파일**: `frontend/`, `infra/compose.widget.yml`, `infra/compose.internal.yml`

## 컨텍스트 (Why)

백엔드(`core`, `app-widget`, `app-internal`)는 `ragvault` 모노레포에 통합됐으나,
프론트엔드는 외부 레포를 Docker Compose 빌드 컨텍스트로 직접 참조하고 있었다.

```yaml
# 이관 전
admin:    context: ../../ragvault-chatbot/admin      # 외부 레포
widget:   volume:  ../../ragvault-chatbot/widget     # 외부 레포
rag-frontend: context: ../../rag-practice/rag-frontend  # 외부 레포
```

이로 인해:
1. `ragvault` 단독 클론만으로 전체 스택 빌드 불가
2. 프론트엔드 변경과 백엔드 변경이 서로 다른 레포에 흩어짐
3. CI/CD 파이프라인 구성 시 멀티-레포 체크아웃 필요

## 결정 (What)

```
세 프론트엔드 모두 ragvault/frontend/ 아래로 복사 이관한다.

frontend/
├── widget-admin/    ← ragvault-chatbot/admin
├── widget-embed/    ← ragvault-chatbot/widget
└── internal/        ← rag-practice/rag-frontend

- 원본 레포는 히스토리 보존용 아카이브로 유지 (삭제 안 함)
- git 히스토리는 이관하지 않음 (단순 파일 복사)
- 각 앱은 자체 Dockerfile로 독립 빌드 (npm workspace 미도입)
- compose 빌드 컨텍스트를 모노레포 내부 경로로 교체
```

## 결과 (Consequences)

### 장점
- **자급자족**: `git clone ragvault` 후 `docker compose up` 한 번으로 전체 스택 기동.
- **단일 PR**: 백엔드 API 변경과 프론트엔드 변경을 같은 PR에서 검토·배포 가능.
- **경로 단순화**: 상대 경로 `../../` 제거로 compose 파일 가독성 향상.

### 단점·트레이드오프
- **git 히스토리 단절**: 이관 이전 프론트엔드 커밋 히스토리는 원본 레포에만 존재.
- **번들 크기 증가**: 모노레포 `node_modules/` 중복 가능성 → 각 앱 독립 설치로 허용.
- **내부 SPA 이관 범위**: `rag-practice/rag-frontend`의 nginx.conf가 `rag-backend:8080`을 참조했으나 ragvault compose 서비스명은 `app-internal` → 이관 시 nginx.conf 수정 필요.

### 후속 작업
- 원본 레포(`ragvault-chatbot`, `rag-practice`)에 아카이브 안내 README 추가 ✅
- CI/CD 파이프라인을 단일 레포 기준으로 재구성 (추후)
- npm workspace 도입 검토 — 현재는 앱별 독립 `node_modules`, 필요 시 전환

## 대안 (검토했으나 채택 안 한 옵션)

### 옵션 A — 외부 레포 참조 유지 (현상 유지)
- 변경 최소. 원본 레포 히스토리 자연스럽게 이어짐.
- **채택 안 한 이유**: 모노레포 단독 빌드 불가 문제가 지속되어 개발·배포 마찰 증가.

### 옵션 B — git subtree / submodule 사용
- 히스토리 보존 가능.
- **채택 안 한 이유**: submodule은 팀 학습 곡선·운영 복잡도가 높다. subtree는 push 방향 관리가 번거롭다. 이 프로젝트 규모에서 단순 복사로 충분하다고 판단.

### 옵션 C — npm workspace 루트 도입
- 패키지 중복 제거, 공통 타입 공유 가능.
- **채택 안 한 이유**: 세 앱(widget-admin·widget-embed·internal)의 기술 스택·빌드 방식이 달라 통합 효익보다 설정 복잡도가 크다. 독립 Dockerfile 철학 유지.

## 참고

- `infra/compose.widget.yml` — widget 스택 빌드 컨텍스트
- `infra/compose.internal.yml` — internal 스택 빌드 컨텍스트
