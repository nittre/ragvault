# Runbook: Ollama LLM 서버 장애

**심각도**: Critical
**예상 복구 시간**: 5~15분
**Discord 채널**: #critical-alerts

## 증상
- `/api/v1/health/deep` → `ollama.status: DOWN`
- 사용자 응답: "LLM 서비스에 연결할 수 없습니다" 에러
- CloudWatch: `alb-5xx-critical` 알람 발동

## 원인 분류

| 원인 | 확인 방법 | 빈도 |
|------|----------|------|
| Spot 인스턴스 회수 | EC2 콘솔 → Spot 요청 상태 | 높음 |
| Ollama 프로세스 크래시 | SSM → `systemctl status ollama` | 중간 |
| GPU 메모리 OOM | SSM → `nvidia-smi` | 낮음 |
| 모델 로드 실패 | SSM → `journalctl -u ollama -n 100` | 낮음 |

## 복구 절차

### Step 1: 상태 확인 (2분)

SSM Session Manager로 GPU 노드 접속:

```bash
aws ssm start-session --target <instance-id>

# Ollama 서비스 상태
systemctl status ollama

# GPU 상태
nvidia-smi

# 최근 로그
journalctl -u ollama -n 50 --no-pager
```

### Step 2-A: Spot 인스턴스 회수된 경우

```bash
# Spot 요청 재시작
aws ec2 request-spot-instances \
  --instance-count 1 \
  --type persistent \
  --launch-specification file://spot-spec.json
```

새 인스턴스 기동 후 docker compose restart 불필요 (Docker 자동 재시작)

### Step 2-B: Ollama 프로세스 크래시

```bash
systemctl restart ollama
sleep 15
# 모델 로드 확인
ollama list
curl http://localhost:11434/api/tags
```

### Step 2-C: GPU OOM

```bash
# 실행 중인 모델 언로드
ollama stop qwen2.5:14b-instruct-q4_K_M
# 메모리 확인 후 재시작
nvidia-smi
systemctl restart ollama
```

### Step 3: 검증 (1분)

```bash
curl http://localhost:11434/api/tags | jq '.models[].name'
# 확인 목록: qwen2.5:14b-instruct-q4_K_M, qwen2.5-vl:7b-instruct-q4_K_M, bge-m3
```

## 장기 대응
- Spot Fleet 예비 인스턴스 타입 추가 (`g5.2xlarge` 대안)
