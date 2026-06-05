# ADR-0003: ALB Multi-AZ 의무 명시 (컴퓨트만 Single AZ)

> ⚠️ **사내 서비스 전환(2025-06)으로 현재 적용 제외.** 본 ADR은 AWS 환경 전용. 클라우드 도입 결정 시 재검토 필요.

- **상태**: Accepted
- **결정일**: 2026-05-19
- **결정자**: 시니어 백엔드 엔지니어 (사용자 옵션 A 채택)
- **관련 ADR**: —
- **영향 받는 문서**: `requirements/01-architecture.md` 섹션 4-3·8-5, `requirements/02-stack-reference.md` ALB, `requirements/TEAM-OVERVIEW.md` 섹션 8-2·8-5·FAQ Q7

## 컨텍스트

여러 문서가 ALB 를 **Single AZ** 로 표기하고 있었다.

- `01-architecture.md 4-3` 도식: `ALB (Single AZ)`
- `TEAM-OVERVIEW.md 8-2`: Public Subnet AZ-a 만 그려져 있고 ALB 가 그 안에
- `Q7. DB Multi-AZ만 하고 컴퓨트 Single AZ 면 …` 문장에 ALB 도 Single AZ 라는 암시

### 문제점
- AWS 강제 — ALB 는 최소 2개 AZ 의 서브넷에 attach 해야 생성됨. `aws_lb` Terraform 리소스는 `InvalidParameter: At least two subnets in two different Availability Zones must be specified` 로 거부.
- 즉, 현재 문서대로 Terraform 모듈을 작성하면 **첫 고객사 온보딩에서 인프라 배포 실패**.
- 비용 영향 0 — ALB 시간당 요금은 AZ 수와 무관 (LCU 도 트래픽 기반).

## 결정

**ALB·RDS = Multi-AZ, 컴퓨트(EC2 / GPU) = Single AZ (AZ-a)**.

- Public Subnet 을 AZ-a + AZ-c 양쪽에 만든다 (ALB attach 용)
- Private App Subnet 도 AZ-a + AZ-c (AZ-c 는 빈 reserve)
- Private LLM Subnet 은 AZ-a 만 (GPU 노드)
- Private Data Subnet 은 AZ-a + AZ-c (RDS Multi-AZ Primary/Standby)
- NAT Gateway 는 AZ-a 에만 1개 (시간당 요금 절감)

문서 표기:
- "ALB (Single AZ)" → "ALB (Multi-AZ 의무)" 로 정정
- HA 정책 섹션에 "ALB·RDS Multi-AZ, 컴퓨트 Single AZ (AZ-a)" 명시
- FAQ Q7 정정

## 결과

### 장점
- AWS 현실과 일치 → Terraform 실제로 동작
- 의도(컴퓨트 비용 절감)와 ALB 동작(자동 Multi-AZ) 명확히 분리
- 비용 영향 0
- AZ 다운 시 트래픽 인입 자체는 살아있음 (백엔드 EC2 가 AZ-a 에 있어 502/503 반환은 별개)

### 단점·트레이드오프
- AZ-c 에 빈 App Subnet reserve (실제 EC2 없음) — Terraform 모듈 약간 더 복잡
- 도식 추가 라벨링

### 후속 작업
- Terraform 모듈에서 ALB `subnets = [pub_a.id, pub_c.id]` 명시
- 비용 표는 변경 없음 ($415 유지)

## 대안

### 옵션 B — 컴퓨트도 Multi-AZ 로 격상
AZ 다운에 자동 대응 가능하나 비용 +$220/월 (GPU 2배 등). 1년 1~2회 빈도 위해 항상 2배 컴퓨트 → ROI 나쁨. Phase 1+ 검토.

### 옵션 C — ALB 대신 NLB 또는 EC2 직접 노출
NLB 는 L4 → SSL 종료·경로 라우팅·WAF·ACM 통합이 깨짐. 보안 최악. 거부.

## 참고

- 권위 출처: `requirements/01-architecture.md` 섹션 4-3·8-5
- 비용 표 단일 출처: `requirements/01-architecture.md` 섹션 8-1 ($415)
