# infra

ragvault 의 **Docker Compose 기반 인프라** 정의입니다. 공통 기반(base) 위에 제품별 오버레이(overlay)를 얹는 **계층형 구조**로, 로컬 개발과 개발 서버 배포를 분리합니다. 모든 서비스는 외부 도커 네트워크 **`rag-net`** 에 참여합니다.

```bash
docker network create rag-net    # 최초 1회
```

---

## Compose 파일 구성

| 파일 | 역할 | `build:` | 용도 |
|------|------|:---:|------|
| `compose.base.yml` | 공통 기반: `ollama`, `pgvector`, `ollama-init` | 없음 | 단독 실행 안 함(오버레이와 조합) |
| `compose.internal.yml` | 챗 서비스 오버레이 | 있음 | 로컬에서 챗 스택 빌드·기동 |
| `compose.widget.yml` | 위젯 서비스 오버레이 | 있음 | 로컬에서 위젯 스택 빌드·기동 |
| `compose.dev.yml` | 개발 서버 전체 스택 | **없음** | Jenkins 가 전송한 이미지로 컨테이너만 교체 |

### 실행 예시
```bash
# 로컬 — 챗 서비스
docker compose -f infra/compose.base.yml -f infra/compose.internal.yml up -d --build

# 로컬 — 위젯 서비스
docker compose -f infra/compose.base.yml -f infra/compose.widget.yml up -d --build

# 개발 서버 (이미지 사전 적재 상태)
docker compose -f infra/compose.dev.yml --env-file infra/.env.dev up -d
```

---

## 서비스 목록

### 공통 (base)
| 서비스 | 이미지 | 역할 | 포트(로컬) |
|--------|--------|------|-----------|
| `ollama` | ollama/ollama | LLM/임베딩/비전 추론 (`OLLAMA_MAX_LOADED_MODELS=2`) | 11434 |
| `ollama-init` | ollama/ollama | 기동 시 `bge-m3`·`qwen2.5:3b` 자동 pull (1회) | — |
| `pgvector` | pgvector/pgvector:pg16 | 벡터 DB — `ragdb`(챗) + `widget_db`(위젯) | 5432 |

### 챗 스택 (internal)
| 서비스 | 역할 | 포트(로컬) |
|--------|------|-----------|
| `app-internal` | 챗 백엔드 | 18090→8080 |
| `rag-frontend` | 챗 프론트엔드(Nginx) | 18080→80 |
| `redis` | ShedLock 분산 락 | 6379 |
| `searxng` | 웹검색 메타서치 엔진 | 18081→8080 |
| `mysql-sample` | 샘플 외부 DB(binlog ROW 모드, GTID) | 3306 |
| `mariadb-board` | 샘플 외부 게시판 DB(binlog) | 3307 |

### 위젯 스택 (widget)
| 서비스 | 역할 | 포트(로컬) |
|--------|------|-----------|
| `app-widget` | 위젯 백엔드 | 8081 |
| `admin`(widget-admin) | 위젯 어드민 SPA(Nginx) | 5173→80 |
| `widget-demo` | 임베드 데모(Nginx + 프록시) | 8080→80 |
| `shop-mariadb` | 샘플 고객 datasource(`shop_db`) | 3308 |

> `compose.dev.yml` 은 위 서비스들을 개발 서버 포트(pgvector `35432`, redis `6379`, searxng `8888`, 앱 `18090/18091`, 프론트 `18080/18082/18083`)로 노출하며 이미지를 직접 빌드하지 않습니다.

---

## 초기화 스크립트

| 경로 | 역할 |
|------|------|
| `pg-init/01-widget-db.sh` | pgvector 최초 기동 시 `widget_db`·`widget` 유저 자동 생성(챗/위젯 DB 분리) |
| `internal/mysql-init/01-grants.sql` | mysql-sample 권한 부여 |
| `internal/mariadb-init/*.sql` | mariadb-board 스키마·시드 |
| `internal/searxng/settings.yml` | SearXNG 설정 |
| `widget/mariadb-init/*.sql` | shop-mariadb 스키마·시드 |
| `widget/nginx/widget-nginx.conf` | 위젯 데모 Nginx — 정적 서빙 + `/api` 백엔드 프록시 |

---

## SearXNG (웹검색)

챗 서비스의 웹검색(`WebSearchService`)이 사용하는 **셀프호스팅 메타서치 엔진**입니다. 여러 검색 엔진 결과를 집계해 JSON 으로 반환하며, 외부 상용 검색 API 없이 사내 폐쇄망에서 웹검색을 제공하기 위해 채택했습니다. 백엔드는 `RAG_SEARXNG_URL`(컨테이너 내부 `http://searxng:8080`)로 접근합니다.

---

## 인프라 아키텍처

```
                    ┌───────────────── rag-net (external docker network) ─────────────────┐
                    │                                                                      │
 [사내 사용자] ───► rag-frontend ─/api─► app-internal ─┬─► pgvector(ragdb, pgvector)      │
                    │                                   ├─► redis (ShedLock)               │
                    │                                   ├─► searxng (웹검색)               │
                    │                                   ├─► ollama (qwen2.5vl / bge-m3)    │
                    │                                   └─► mysql-sample / mariadb-board   │
                    │                                        (외부 datasource 동기화)      │
 [고객 방문자] ───► widget-demo/iframe ─► app-widget ──┬─► pgvector(widget_db)             │
 [고객 어드민] ───► widget-admin ─────────┘            ├─► ollama                          │
                    │                                   └─► shop-mariadb (고객 datasource) │
                    └──────────────────────────────────────────────────────────────────┘
```

- **공유 자원**: `ollama` 와 `pgvector` 는 두 서비스가 공용. pgvector 내부에서 DB(`ragdb`/`widget_db`)로 데이터를 격리
- **데이터 경계**: 위젯 서비스는 사내 DB(`mysql-sample`/`mariadb-board`)에 연결되지 않음 — 고객 datasource(`shop-mariadb`)만 사용
- **배포 모델**: 로컬은 overlay 로 직접 빌드, 개발 서버는 Jenkins 가 이미지를 `docker save | ssh docker load` 로 전송 후 `compose.dev.yml` 로 컨테이너 교체(`up -d --no-deps --force-recreate`)

CI/CD 파이프라인 상세는 루트 [README](../README.md#서비스-구동-방법) 및 `jenkins/Jenkinsfile.*` 참고.
