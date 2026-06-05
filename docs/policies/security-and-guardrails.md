# 보안·가드레일 정책

> 위험 명령어 가드레일 + 핵심 보안 원칙 + PII·인증·격리 모델.

## 가드레일 (Bash 위험 명령 차단)

위치: `.claude/hooks/guardrail.py`
등록: `.claude/settings.json` 의 `hooks.PreToolUse[Bash]`

### 동작
`Bash` 도구 호출 직전 `stdin` 으로 받은 command 를 27개 위험 패턴과 매칭 → 매칭 시 `permissionDecision: "ask"` JSON 반환 → 사용자 명시 승인 필요.

### 감지 패턴 (카테고리별)

| 카테고리 | 패턴 |
|---------|------|
| 파일 파괴 | `rm -rf` · `rm /*` · `dd of=` · `mkfs` · `shred` |
| Git 강제·파괴 | `push --force` · `reset --hard` · `clean -f` · `branch -D` · `checkout .` |
| SQL 파괴 | `DROP TABLE/DATABASE/SCHEMA` · `TRUNCATE` · `DELETE FROM` (WHERE 없음) |
| K8s/Helm (레거시) | `kubectl delete` · `helm uninstall` · `kubectl drain` — 현 시스템 미사용 |
| 인프라 관리 도구 | `destroy` · `apply -destroy` · `state rm` |
| AWS | `ec2 terminate-instances` · `rds delete-db-instance` · `s3 rb` · `iam delete-*` |
| 권한·sudo | `chmod -R 777` · `chown -R` · `sudo` |
| 우회 | `curl|sh` · `wget|sh` |

### 정책
- **페일 오픈** — hook 자체 실패 시 작업 차단하지 않음
- 정상 명령은 통과
- 위험 패턴 잡히면 사용자가 명시 승인해야 실행
- 검증된 안전 명령은 `.claude/settings.json` 의 `permissions.allow` 에 사전 등록 가능

### 테스트
```bash
echo '{"tool_name":"Bash","tool_input":{"command":"rm -rf /tmp/foo"}}' | python3 .claude/hooks/guardrail.py
# → {"hookSpecificOutput": {"permissionDecision": "ask", ...}}

echo '{"tool_name":"Bash","tool_input":{"command":"ls -la"}}' | python3 .claude/hooks/guardrail.py
# → (출력 없음, 통과)
```

---

## 핵심 보안 원칙 (코드 작성 시 의무)

### 1. PII 마스킹 일관 적용
**모든 응답·로그 경로**에 `PiiMasker` (정규식 + Phase 1+ NER) 적용.
- RAG: 동기화 시점 마스킹 ([requirements/03 섹션 5](../../requirements/03-data-sync-pipeline.md))
- SQL: Layer 1 (생성·검증 단계) + Layer 3 (응답 후처리) — ADR 옵션 D
- audit_log: PII 마스킹 후 저장
- 채팅 응답 본문은 audit_log 에 저장 안 함

### 2. `access_groups` 필터 항상 적용
모든 벡터 검색 쿼리에 `access_groups && $userGroups` 강제.
- Phase 0 는 자명히 `['all']` 매칭이지만 누락 금지 (ADR-0002)
- 정적 분석: `grep -rn "embedding <=>" | grep -v "access_groups"` → 결과 있으면 BLOCKER

### 3. 인증 모델 — API Key + Scope + X-User-* 백엔드 주입
- API Key: `sk-rag-*`, bcrypt 해시 저장
- Scope: `api:chat`, `api:admin`, `api:sync`, `api:config`, `api:audit`, `api:apikey`
- X-User-Email/Id/Role 헤더는 **Open WebUI 백엔드 프록시가 주입** — 브라우저 직접 추가 금지
- Spring Boot `TrustedHeaderFilter` 가 외부 IP 발신 시 헤더 제거

### 4. Scope ≠ 데이터 접근 그룹
- Scope: 시스템 권한 (엔드포인트 호출 가능 여부)
- `user_groups`: 데이터 접근 권한 (어떤 RAG 청크·SQL 테이블 볼 수 있는가)
- 두 개념 분리 — 같은 사용자가 `api:chat` 있어도 HR 데이터 못 볼 수 있어야 함 (Phase 1+)

### 5. SSRF Guard (URL Fetch)
URL Fetch 모든 호출 전 `SsrfGuard.validate(url)` 통과 의무:
- private IP (10/8, 172.16/12, 192.168/16, 127/8, 169.254/16, IPv6 ULA) 차단
- DNS resolve 후 모든 IP 재검증 (DNS rebinding 방어)
- Scheme 화이트리스트 (http/https 만)
- redirect follow 시 매 hop 재검증
- 응답 크기 5MB 한도, 타임아웃 30초

### 6. SQL 안전성
- `SqlValidator` JSqlParser AST 검증
- SELECT 만, `SELECT *` 거부
- `sql_table_config.excluded_columns` 가 SELECT/WHERE/ORDER BY 에 등장하면 거부
- 위험 키워드(INSERT/UPDATE/DELETE/DROP/TRUNCATE/ALTER/CREATE/GRANT/REVOKE/LOAD_FILE/INTO OUTFILE) 차단
- 멀티 스테이트먼트 거부
- LIMIT 강제 (max 1,000)
- Read-only MySQL 계정 (`rag_readonly`)

### 7. Secrets 평문 노출 금지
- DB 비밀번호·API Key·SMTP 자격증명 → Secrets Manager
- `application.yml` 에 평문 금지
- Git 히스토리 검사 (pre-commit hook 검토)

### 8. 가드레일 우회 금지
hook 비활성화·sudo 권한 부여 명령은 코드 리뷰에서 BLOCKER. 가드레일 자체 변경은 ADR + 사용자 명시 승인 필요.

### 9.  IAM 최소 권한
- `DeployRole` 에 `AdministratorAccess` 금지
- 인프라 관리 도구 모듈이 실제 사용하는 Action 만 화이트리스트
- ExternalId + sourceIP 조건 (Trust Policy)

### 10. 데이터 외부 전송 정책
[`requirements/TEAM-OVERVIEW.md`](../../requirements/TEAM-OVERVIEW.md): "데이터 외부 전송 없음"
- Ollama (로컬·고객사 GPU) — 외부 호출 0
- 임베딩 모델 (bge-m3) — 로컬
- URL Fetch — 사용자 명시 URL 만, 회사 RAG 데이터 미전송
- Tavily/외부 검색 — Phase 0 보류

## 참고

- 인증·인가 상세: [`requirements/07-auth-security.md`](../../requirements/07-auth-security.md)
- PII·동기화: [`requirements/03-data-sync-pipeline.md`](../../requirements/03-data-sync-pipeline.md)
- Text-to-SQL 보안: [`requirements/08-text-to-sql.md`](../../requirements/08-text-to-sql.md) 섹션 12
- URL/파일/멀티모달 보안: [`requirements/10-multimodal-files-url.md`](../../requirements/10-multimodal-files-url.md) 섹션 7
- 관련 정책: [team-and-workflow.md](team-and-workflow.md), [decisions-and-lessons.md](decisions-and-lessons.md), [engineering-conventions.md](engineering-conventions.md)
