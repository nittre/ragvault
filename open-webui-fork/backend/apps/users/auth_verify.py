"""
/auth/verify — Admin Web UI 세션 검증용 엔드포인트.
ADR-0009: Admin SPA가 이 API를 호출해 사용자 role을 확인한다.

보안 원칙:
- 최소 권한: role 정보만 반환, 민감 정보 미포함
- 인증 미완료 시 401 반환
- admin 여부는 is_admin 필드로 명시 (role 문자열 직접 비교 금지)
"""
from fastapi import APIRouter, Request, HTTPException
from fastapi.responses import JSONResponse

router = APIRouter()


@router.get("/auth/verify")
async def verify_session(request: Request):
    """
    현재 세션 사용자 정보 + role 반환.
    ADR-0009: Admin SPA 인증 게이트 역할.
    인증되지 않은 요청은 401 반환.
    """
    user = getattr(request.state, "user", None)
    if not user:
        raise HTTPException(status_code=401, detail="인증이 필요합니다.")

    role = getattr(user, "role", "user")
    return JSONResponse({
        "id": str(getattr(user, "id", "")),
        "email": getattr(user, "email", ""),
        "name": getattr(user, "name", ""),
        "role": role,
        "is_admin": role == "admin",
    })
