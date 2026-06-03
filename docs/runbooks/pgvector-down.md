# Runbook: PostgreSQL(pgvector) 장애

**심각도**: Critical
**예상 복구 시간**: 2~30분 (자동 복구 포함)
**Discord 채널**: #critical-alerts

## 증상
- `/api/v1/health/deep` → `postgres.status: DOWN`
- RAG 검색 불가, 모든 응답 500 에러

## 원인 분류

| 원인 | 확인 방법 |
|------|----------|
| RDS 재시작 (패치) | RDS 콘솔 → 이벤트 로그 |
| 연결 수 초과 | CloudWatch: `DatabaseConnections > 80` |
| 스토리지 부족 | CloudWatch: `FreeStorageSpace < 5GB` |
| Multi-AZ 페일오버 | RDS 콘솔 → 상태 "failover" |

## 복구 절차

### RDS 재시작/페일오버 (자동 복구)

- Multi-AZ 페일오버: 60~120초 내 자동 복구
- Spring Boot HikariCP: 연결 풀 자동 재연결 (connectionTimeout=30s)
- **조치 불필요** — 2분 대기 후 `/api/v1/health/deep` 재확인

### 연결 수 초과

SSM Session Manager로 App 노드 접속 후:

```bash
# HikariCP 최대 연결 수 확인
curl http://localhost:8080/actuator/metrics/hikaricp.connections.max

# 필요 시 application 재시작
kubectl rollout restart deployment/rag-backend -n rag-system
```

### 스토리지 부족

```bash
# RDS 스토리지 즉시 확장
aws rds modify-db-instance \
  --db-instance-identifier <id> \
  --allocated-storage 100 \
  --apply-immediately
```

## 예방
- CloudWatch 알람: `rds-storage-critical` (5GB 미만 시 즉시 알림)
- 주간 VACUUM/ANALYZE 자동 실행 (RDS 기본 autovacuum)
