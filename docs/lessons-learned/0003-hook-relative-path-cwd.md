# LL-0003: PreToolUse Hook 상대경로 — CWD 변경 시 Hook 중단

## 메타데이터

| 항목 | 값 |
|------|----|
| 발생일 | 2026-05-21 |
| 카테고리 | `other` (Claude Code 하네스 설정) |
| 심각도 | HIGH |
| 관련 작업 | M0 rag-backend 부트스트랩 |
| 관련 ADR | 없음 |
| 관련 설정 | `.claude/settings.json` |

---

## 에러 상황 (시간 순)

1. Gradle 빌드 명령을 포그라운드(foreground)로 실행:
   ```bash
   cd /Users/youjinlee/rag-practice/rag-backend && JAVA_HOME=... ./gradlew test --tests "..." --info
   ```
2. Bash 도구의 **CWD가 `rag-backend`로 영구 변경**됨 (포그라운드 명령은 CWD를 세션에 유지함)
3. 이후 모든 Bash 명령에서 PreToolUse hook 실패:
   ```
   PreToolUse:Bash hook error: [python3 .claude/hooks/guardrail.py]:
   can't open file '/Users/youjinlee/rag-practice/rag-backend/.claude/hooks/guardrail.py':
   [Errno 2] No such file or directory
   ```
4. **모든 Bash 명령이 차단됨** — `pwd`, `ls` 같은 무해한 명령도 실행 불가

---

## 원인 (5 Whys)

1. 왜 hook이 깨졌나? → hook 명령이 상대경로 `python3 .claude/hooks/guardrail.py`를 사용
2. 왜 상대경로가 문제인가? → CWD가 프로젝트 루트가 아닌 경우(`rag-backend/`) 경로가 틀려짐
3. 왜 CWD가 바뀌었나? → Bash 도구에서 포그라운드로 실행된 `cd` 명령은 세션 CWD를 영구 변경함
4. 왜 설계 시 몰랐나? → hook이 항상 프로젝트 루트에서 실행된다고 가정했으나 보장되지 않음

---

## 해결

### 영구 해결 (적용됨)
`.claude/settings.json`의 hook 명령을 절대경로로 변경:

```json
// ❌ 이전 (상대경로 — CWD 의존)
"command": "python3 .claude/hooks/guardrail.py"

// ✅ 이후 (절대경로 — CWD 무관)
"command": "python3 /Users/youjinlee/rag-practice/.claude/hooks/guardrail.py"
```

---

## 재발 방지

### 시스템 (자동화)

1. **settings.json hook 명령 절대경로 원칙**:
   - hook command에 `./`로 시작하는 상대경로 금지
   - 신규 hook 추가 시 반드시 절대경로 또는 환경변수 사용

2. **포그라운드 `cd` 사용 최소화**:
   - 빌드 명령은 `-p` 옵션 또는 절대경로 사용:
     ```bash
     # ✅ CWD 변경 없이 rag-backend 빌드
     JAVA_HOME=... rag-backend/gradlew -p rag-backend clean build
     
     # ❌ CWD를 rag-backend로 변경 (세션 지속)
     cd rag-backend && ./gradlew clean build
     ```
   - 반드시 `cd`가 필요하면 백그라운드(`run_in_background: true`)로 실행 (CWD 변경이 메인 세션에 전파되지 않음)

---

## 비슷한 작업에 적용할 규칙

| When | Do |
|------|----|
| `.claude/settings.json`에 hook을 추가할 때 | 반드시 절대경로 사용 (`python3 /절대/경로/script.py`) |
| Gradle 빌드를 실행할 때 | `cd {dir} && ./gradlew` 대신 `gradlew -p {dir}` 사용 |
| 포그라운드로 `cd`를 실행했을 때 | 다음 명령에서 `cd /Users/youjinlee/rag-practice &&`로 루트 복귀 명시 |
| hook이 갑자기 "파일 없음" 에러를 내면 | CWD 확인 (`pwd`)하고 절대경로로 hook 수정 |
| 하위 디렉토리 작업이 필요할 때 | 백그라운드(`run_in_background: true`)로 실행하여 CWD 변경 방지 |

---

## 참고

- Claude Code Bash tool: "The working directory persists between commands, but shell state does not."
- 포그라운드 명령의 `cd`는 세션 CWD를 변경함 (백그라운드는 서브셸이므로 변경 안 됨)
