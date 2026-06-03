#!/usr/bin/env python3
"""
PreToolUse 가드레일 — 위험 Bash 명령어 감지 시 사용자 명시 승인 요청.

동작:
- stdin 으로 tool input 받음 (JSON, Claude Code hook spec)
- Bash 명령어를 위험 패턴 집합과 매칭
- 매칭되면 stdout JSON 으로 permissionDecision="ask" + 사유 반환
  → Claude Code 가 사용자에게 명시 승인 요청
- 매칭 없으면 permissionDecision 미지정 (기본 흐름)

설계 원칙:
- 완벽한 차단은 불가능 (uname -a 우회, base64 encode 우회 등)
- 표면적·일반적 위험 패턴 차단으로 실수 방지가 목적
- 정당한 작업도 차단되지만, 사용자가 "approve" 한 번 누르면 진행
"""
import json
import re
import sys
from dataclasses import dataclass
from typing import List, Optional


@dataclass
class Rule:
    name: str
    pattern: re.Pattern
    reason: str


# 위험 패턴 — 카테고리별 정리. 각 패턴은 사유와 함께 정의.
RULES: List[Rule] = [
    # ─── 파일 시스템 파괴 ───────────────────────────
    Rule(
        name="rm-rf",
        pattern=re.compile(r"\brm\s+(-[a-zA-Z]*[rRfF][a-zA-Z]*|--recursive|--force)"),
        reason="rm -rf / -r / -f: 재귀·강제 삭제는 복구 불가능",
    ),
    Rule(
        name="rm-slash",
        pattern=re.compile(r"\brm\s+[^|;&]*\s+/(\s|$)"),
        reason="rm /: 루트 경로 삭제 시도",
    ),
    Rule(
        name="dd-of",
        pattern=re.compile(r"\bdd\s+.*\bof="),
        reason="dd of=: 디스크·파일 직접 덮어쓰기",
    ),
    Rule(
        name="mkfs",
        pattern=re.compile(r"\bmkfs(\.\w+)?\b"),
        reason="mkfs: 파일 시스템 포맷",
    ),
    Rule(
        name="shred",
        pattern=re.compile(r"\bshred\b"),
        reason="shred: 복구 불가능한 파일 파쇄",
    ),

    # ─── Git 강제·파괴 작업 ─────────────────────────
    Rule(
        name="git-push-force",
        pattern=re.compile(r"\bgit\s+push\b[^|;&]*\s(-f|--force)(\s|$)"),
        reason="git push --force: 원격 히스토리 덮어쓰기, 협업자 변경 손실 가능",
    ),
    Rule(
        name="git-reset-hard",
        pattern=re.compile(r"\bgit\s+reset\b[^|;&]*--hard"),
        reason="git reset --hard: 워킹 디렉토리 변경 사항 손실",
    ),
    Rule(
        name="git-clean-force",
        pattern=re.compile(r"\bgit\s+clean\b[^|;&]*-[a-zA-Z]*[fF]"),
        reason="git clean -f: untracked 파일 영구 삭제",
    ),
    Rule(
        name="git-branch-delete-force",
        pattern=re.compile(r"\bgit\s+branch\b[^|;&]*-[a-zA-Z]*D"),
        reason="git branch -D: 머지 안 된 브랜치 강제 삭제",
    ),
    Rule(
        name="git-checkout-discard",
        pattern=re.compile(r"\bgit\s+checkout\s+(\.|--\s+\.)"),
        reason="git checkout . : 변경 사항 일괄 폐기",
    ),

    # ─── SQL 파괴 ───────────────────────────────────
    Rule(
        name="sql-drop",
        pattern=re.compile(r"\bDROP\s+(TABLE|DATABASE|SCHEMA|INDEX)\b", re.IGNORECASE),
        reason="SQL DROP: 데이터 영구 손실",
    ),
    Rule(
        name="sql-truncate",
        pattern=re.compile(r"\bTRUNCATE\s+(TABLE\s+)?[a-zA-Z_]", re.IGNORECASE),
        reason="SQL TRUNCATE: 테이블 전체 데이터 즉시 삭제",
    ),
    Rule(
        name="sql-delete-no-where",
        pattern=re.compile(
            r"\bDELETE\s+FROM\s+[a-zA-Z_][\w.]*\s*(;|$|--)", re.IGNORECASE
        ),
        reason="DELETE FROM (WHERE 없음): 테이블 전체 삭제",
    ),

    # ─── Kubernetes / Helm 파괴 ─────────────────────
    Rule(
        name="kubectl-delete",
        pattern=re.compile(r"\bkubectl\s+delete\b"),
        reason="kubectl delete: 리소스 영구 삭제",
    ),
    Rule(
        name="helm-uninstall",
        pattern=re.compile(r"\bhelm\s+(uninstall|delete)\b"),
        reason="helm uninstall: 차트 일괄 제거",
    ),
    Rule(
        name="kubectl-drain",
        pattern=re.compile(r"\bkubectl\s+drain\b"),
        reason="kubectl drain: 노드의 모든 Pod evict",
    ),

    # ─── Terraform 파괴 ─────────────────────────────
    Rule(
        name="terraform-destroy",
        pattern=re.compile(r"\bterraform\s+destroy\b"),
        reason="terraform destroy: 인프라 전체 삭제",
    ),
    Rule(
        name="terraform-apply-destroy",
        pattern=re.compile(r"\bterraform\s+apply\b[^|;&]*-destroy"),
        reason="terraform apply -destroy: 인프라 삭제 적용",
    ),
    Rule(
        name="terraform-state-rm",
        pattern=re.compile(r"\bterraform\s+state\s+rm\b"),
        reason="terraform state rm: state 에서 리소스 제거 (인프라는 남지만 추적 끊김)",
    ),

    # ─── AWS 위험 작업 ──────────────────────────────
    Rule(
        name="aws-ec2-terminate",
        pattern=re.compile(r"\baws\s+ec2\s+terminate-instances\b"),
        reason="aws ec2 terminate-instances: EC2 영구 종료",
    ),
    Rule(
        name="aws-rds-delete",
        pattern=re.compile(r"\baws\s+rds\s+delete-db-instance\b"),
        reason="aws rds delete-db-instance: RDS 인스턴스 영구 삭제",
    ),
    Rule(
        name="aws-s3-rb",
        pattern=re.compile(r"\baws\s+s3\s+rb\b"),
        reason="aws s3 rb: S3 버킷 삭제",
    ),
    Rule(
        name="aws-iam-delete",
        pattern=re.compile(r"\baws\s+iam\s+delete-"),
        reason="aws iam delete-*: IAM 권한 변경 (사용자/롤/정책 삭제)",
    ),

    # ─── 권한 / sudo ────────────────────────────────
    Rule(
        name="chmod-777-recursive",
        pattern=re.compile(r"\bchmod\s+(-R\s+777|777\s+-R)\b"),
        reason="chmod -R 777: 권한 완전 개방, 보안 위험",
    ),
    Rule(
        name="chown-recursive",
        pattern=re.compile(r"\bchown\s+-R\b"),
        reason="chown -R: 디렉토리 트리 소유권 재귀 변경",
    ),
    Rule(
        name="sudo",
        pattern=re.compile(r"(^|\s|;|&&|\|)sudo\b"),
        reason="sudo: 관리자 권한 명령 실행",
    ),

    # ─── 우회 위험 ──────────────────────────────────
    Rule(
        name="curl-pipe-shell",
        pattern=re.compile(
            r"\bcurl\b[^|;&]*\|\s*(sh|bash|zsh)\b|\bwget\b[^|;&]*\|\s*(sh|bash|zsh)\b"
        ),
        reason="curl | sh: 검증 안 된 원격 스크립트 실행",
    ),
]


def detect(command: str) -> List[Rule]:
    """매칭된 모든 규칙 반환."""
    matched = []
    for rule in RULES:
        if rule.pattern.search(command):
            matched.append(rule)
    return matched


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except json.JSONDecodeError:
        # hook 자체 실패는 절대 작업을 막지 않는다 (페일 오픈)
        return 0

    tool_name = payload.get("tool_name", "")
    if tool_name != "Bash":
        return 0

    command = payload.get("tool_input", {}).get("command", "")
    if not command:
        return 0

    matched = detect(command)
    if not matched:
        return 0

    reasons = "\n".join(f"  • {r.name}: {r.reason}" for r in matched)
    decision_reason = (
        f"위험 패턴 {len(matched)}건 감지 — 사용자 명시 승인 필요:\n{reasons}\n\n"
        f"이 명령이 의도된 것이 맞다면 승인하세요. "
        f"실수일 가능성이 있다면 거부 후 더 안전한 대안을 검토하세요."
    )

    output = {
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "ask",
            "permissionDecisionReason": decision_reason,
        }
    }
    print(json.dumps(output, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
