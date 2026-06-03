# Runbook: 고객사 MySQL 연결 단절

**심각도**: Warning
**예상 복구 시간**: 10~30분
**Discord 채널**: #warning-alerts

## 증상
- binlog 동기화 중단 (binlog lag > 60분)
- Discord: "binlog 1시간 lag Warning" 알람
- 신규 데이터 RAG 반영 안 됨 (기존 데이터는 정상 응답)

## 원인 분류

| 원인 | 가능성 |
|------|--------|
| VPC Peering/VPN 단절 | 높음 |
| 고객사 MySQL 비밀번호 변경 | 중간 |
| 고객사 MySQL 서버 재시작 | 중간 |
| binlog 포지션 리셋 (GTID 변경) | 낮음 |

## 복구 절차

### Step 1: 연결 확인 (2분)

SSM Session Manager로 App 노드 접속 후:

```bash
# MySQL 포트 연결 확인
nc -zv <customer-mysql-host> 3306

# MySQL 접속 확인
mysql -h <customer-mysql-host> -u raguser -p -e "SELECT 1"
```

### Step 2-A: 네트워크 단절

```bash
# VPC Peering 상태 확인
aws ec2 describe-vpc-peering-connections \
  --filters "Name=status-code,Values=active"
# 비활성이면 고객사 IT 담당자 연락
```

### Step 2-B: 비밀번호 변경

```bash
# Secrets Manager 업데이트
aws secretsmanager update-secret \
  --secret-id <customer-id>-rag-db-password \
  --secret-string '{"password":"NEW_PASS"}'

# App 재시작
kubectl rollout restart deployment/rag-backend -n rag-system
```

### Step 3: binlog 재연결

```bash
# Admin API로 초기 동기화 재실행
curl -X POST https://<host>/api/v1/admin/sync/initial \
  -H "Authorization: Bearer <admin-key>"
```

## 예방
- Discord 알람: binlog lag 60분 초과 시 자동 알림 (ADR-0001: binlog 30분 + GTID 전용)
- GTID 체크: 매 동기화 시 GTID 연속성 검증
