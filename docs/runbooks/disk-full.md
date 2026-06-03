# Runbook: 디스크 공간 부족

**심각도**: Critical (/ 기준 90% 초과)
**예상 복구 시간**: 5~20분
**Discord 채널**: #critical-alerts

## 증상
- CloudWatch: EC2 `disk_used_percent > 90`
- 로그 기록 불가, 파일 업로드 실패

## 원인 분류

| 원인 | 확인 방법 |
|------|----------|
| Ollama 모델 캐시 누적 | `du -sh /root/.ollama/models/` |
| 로그 파일 누적 | `du -sh /var/log/` |
| S3 미업로드 임시 파일 | `du -sh /tmp/` |
| 컨테이너 이미지 누적 | `crictl images` |

## 복구 절차

### Step 1: 공간 확인

SSM Session Manager로 대상 노드 접속 후:

```bash
df -h
du -sh /* 2>/dev/null | sort -rh | head -20
```

### Step 2: 빠른 정리

```bash
# 로그 로테이션
journalctl --vacuum-size=500M

# 임시 파일 정리
find /tmp -type f -mtime +1 -delete

# 오래된 컨테이너 이미지
crictl rmi --prune
```

### Step 3: Ollama 모델 정리 (GPU 노드)

```bash
# 사용하지 않는 모델만 삭제 (필수 3개 유지)
ollama list
# 유지 목록: qwen2.5:14b-instruct-q4_K_M, qwen2.5-vl:7b-instruct-q4_K_M, nomic-embed-text
ollama rm <불필요한-모델>
```

### Step 4: EBS 볼륨 확장

```bash
# 온라인 확장 (재시작 불필요)
aws ec2 modify-volume --volume-id <vol-id> --size 150

# 파일시스템 확장
sudo growpart /dev/xvda 1
sudo xfs_growfs /
```

## 예방
- CloudWatch 알람 추가: `disk_used_percent > 80` (Warning), `> 90` (Critical)
- 로그 로테이션 설정: `/etc/logrotate.d/rag`
