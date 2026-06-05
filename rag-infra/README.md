# rag-infra

사내 RAG 서비스 배포 파일.

## 구조

```
rag-infra/
├── docker-compose.prod.yml  — 운영 배포 (rag-backend, open-webui, redis, prometheus, grafana)
└── README.md
```

## 배포

```bash
# 이미지 Pull
IMAGE_TAG=abc1234 docker compose -f docker-compose.prod.yml pull

# 서비스 기동 / 재배포
IMAGE_TAG=abc1234 docker compose -f docker-compose.prod.yml up -d --remove-orphans

# 상태 확인
docker compose -f docker-compose.prod.yml ps

# 로그 확인
docker compose -f docker-compose.prod.yml logs -f rag-backend
```

## 환경변수

비밀값은 `/opt/ragvault/.env` 파일에 저장 (서버에서 직접 관리).  
`.env.internal.example`을 참고해 작성.

```bash
cp .env.internal.example /opt/ragvault/.env
vi /opt/ragvault/.env   # 실제 값으로 편집
```

## 의존성

- Docker + Docker Compose >= 2.20
