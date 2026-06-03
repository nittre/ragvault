# ADR-0005: 파라미터 우선순위 — 통합 7단계 체인 + 관리자 가드 분리

- **상태**: Accepted
- **결정일**: 2026-05-19
- **결정자**: 시니어 백엔드 엔지니어 (사용자 옵션 A 채택)
- **관련 ADR**: —
- **영향 받는 문서**: `requirements/09-user-parameter-tuning.md` 섹션 4·9, `requirements/04-rag-search-strategy.md` 섹션 9, `requirements/08-text-to-sql.md`

## 컨텍스트

파라미터(`top_k`, `temperature`, `similarity_threshold` 등) 최종 값을 결정하는 우선순위가 두 문서에서 다르게 정의되어 있었고, 같은 09 문서 안에서도 텍스트(섹션 4)와 코드(섹션 9)가 어긋났다.

### 문제 ①
- 04 문서: 4단계 (요청 extra body > 모델 변형 > search_config > hardcoded)
- 09 섹션 4: 4단계 (대화별 > 사용자 프로필 > 시스템 기본값 > hardcoded)
- 09 섹션 9 코드: 5단계 (시스템 → 프로필 → 대화별 → 요청 → 관리자 한계)
- **세 모델이 호환 안 됨**

### 문제 ②
모델 변형(precise/balanced/broad)과 사용자 프로필 충돌 시 결과 미정.

### 문제 ③
관리자 한계(`admin_param_limits`)에 `is_locked`(강제 고정)와 `min/max`(클램핑) 의미가 같은 단계에 묶여 있어 보안 사고 위험.
- "관리자가 0.5~0.9 범위로 잠갔는데 사용자가 0.7 설정 → 통과" 가 의도된 동작인지 모호.

## 결정

**통합 7단계 체인 + 관리자 가드 두 종류 분리**.

### 적용 순서 (위→아래, 나중이 이김)
```
Stage 1: hardcoded fallback
Stage 2: search_config 테이블 기본값
Stage 3: 모델 변형 (precise/balanced/broad) ← Open WebUI 드롭다운
Stage 4: 사용자 프로필 (user_param_profiles) ← 패널에서 '💾 프로필 저장'
Stage 5: 대화별 override (conversation_param_overrides) ← '📋 대화별만'
Stage 6: 요청별 override (body의 rag_params) ← 디버깅·CLI·Phase 1+ A/B
```

### 관리자 가드 — 두 종류로 분리
```
Guard A — 범위 클램핑 (soft, silent)
  · admin_param_limits.min_value / max_value
  · Stage 1~6 결과를 min/max 로 잘라낸다
  · 사용자 자유 안에서 안전 한계만 보장

Guard B — 강제 고정 (hard, override)
  · admin_param_limits.is_locked = true + fixed_value
  · Stage 1~6 결과를 무시하고 fixed_value 덮어쓴다
  · 가장 마지막 단계 (절대 우회 불가)
```

### 추상→구체 원칙
모델 변형(추상) < 프로필(안정적 선호) < 대화별(이 대화만) < 요청별(이 한 요청만, 가장 구체).

### 단일 출처
- 09 문서가 권위 출처
- 04·08 문서는 09 ParameterResolver 를 따른다고 명시

## 결과

### 장점
- 두 문서의 모든 케이스 통합
- Guard A/B 분리로 보안과 UX 양립
  - 강제 고정 = 사용자 입력 무관
  - 클램핑 = 사용자 자유 안 안전 가드
- 09 섹션 4(텍스트)와 9(코드) 동기화
- 04·08 파라미터도 같은 체인 통과 — 단일 진실 출처

### 단점·트레이드오프
- 7단계 + 2 Guard = 다소 많아 보임. 다만 unit test 단계별 작성 쉬움.

### 후속 작업
- `ParameterResolver` 코드: 09 섹션 9 의사코드 그대로 구현
- code-reviewer 회귀 검증: 새 파라미터 도입 시 7단계 적용 확인
- 운영 디버깅용 `/api/v1/user/conversations/{id}/effective-params` 엔드포인트가 source 메타데이터를 반환

## 대안

### 옵션 B — 모델 변형 폐지 (6단계 단순화)
모델 변형 UX(precise/balanced/broad) 손실. 사용자 친화 저하. 거부.

### 옵션 C — 사용자 프로필 폐지
09 핵심 가치 손실. 09:1 Phase 0 결정과 충돌. 거부.

## 참고

- 권위 출처: `requirements/09-user-parameter-tuning.md` 섹션 4·9
- 적용 예시: `requirements/04-rag-search-strategy.md` 섹션 9
- admin 가드 스키마: `requirements/09-user-parameter-tuning.md` 섹션 7-3 (`admin_param_limits`)
