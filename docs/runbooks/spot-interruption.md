# Runbook: Spot 인스턴스 회수 대응

**심각도**: Warning → Critical (복구 지연 시)
**예상 복구 시간**: 3~10분 (자동) / 15~30분 (수동)
**Discord 채널**: #warning-alerts

## 증상
- CloudWatch: `alb-healthy-hosts-critical` 알람 (정상 호스트 0개)
- Discord: "Spot interruption notice received" 로그
- k3s: GPU 노드 NotReady

## 자동 복구 흐름

```
Spot 회수 알림 (2분 전)
    |
    v
spot-termination-handler.sh 감지
    |
    v
k3s kubectl drain <node>
    |
    v
Spot 인스턴스 종료
    |
    v
aws_spot_instance_request (persistent) 자동 재요청
    |
    v
새 Spot 인스턴스 기동 (3~8분)
    |
    v
k3s 자동 재등록
    |
    v
Ollama 모델 로드 (AMI 사전 pull)
```

## 수동 확인 절차

### Step 1: Spot 요청 상태 확인

```bash
aws ec2 describe-spot-instance-requests \
  --filters "Name=tag:Project,Values=rag-saas" \
  --query 'SpotInstanceRequests[*].[SpotInstanceRequestId,Status.Code,InstanceId]'
```

### Step 2: 신규 인스턴스 k3s 등록 확인

```bash
kubectl get nodes
# GPU 노드가 Ready 상태인지 확인
```

### Step 3: Ollama 모델 로드 확인

SSM Session Manager로 새 GPU 인스턴스 접속 후:

```bash
aws ssm start-session --target <new-instance-id>
ollama list
# 3개 모델 확인: qwen2.5:14b-instruct-q4_K_M, qwen2.5-vl:7b-instruct-q4_K_M, nomic-embed-text
```

### Step 4: 서비스 복구 확인

```bash
curl https://<host>/api/v1/health/deep | jq '.ollama'
# status: UP 확인
```

## Spot 가용성 부족 시 (On-Demand 전환)

Terraform 변수 변경:

```bash
# terraform.tfvars 에서 ec2_gpu_instance_type 을 On-Demand 타입으로 교체
# 이후 terraform apply -target=aws_spot_instance_request.gpu
```

## 예방
- AMI 최신 모델 사전 pull 유지 (분기마다 packer 재빌드)
- Spot Fleet 대체 인스턴스 타입 설정: `g5.2xlarge`, `g4dn.xlarge`
