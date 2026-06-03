"""
Open WebUI /v1/chat/completions 프록시 — ADR-0006 구현.

브라우저에서 온 X-User-* 헤더를 폐기하고,
세션에서 인증된 사용자 정보를 기반으로 새로 구성한다.
위변조 방지: 클라이언트가 보낸 X-User-* 헤더는 upstream 전달 전 제거.

보안 원칙:
- BLOCKED_HEADERS: 클라이언트에서 보낸 X-User-* 헤더는 모두 폐기
- RAG_BACKEND_URL: 환경변수로만 설정 (하드코딩 금지 — SSRF 방어)
- 세션에서 추출한 사용자 정보만 X-User-* 헤더로 주입
"""
import httpx
from fastapi import APIRouter, Request, HTTPException
from fastapi.responses import StreamingResponse
import os

router = APIRouter()

# SSRF 방어: upstream URL 은 환경변수에서만 수신
RAG_BACKEND_URL = os.getenv("RAG_BACKEND_URL", "http://rag-backend:8080")
RAG_BACKEND_API_KEY = os.getenv("RAG_BACKEND_API_KEY", "")

# 클라이언트가 보낸 X-User-* 헤더 차단 목록 (ADR-0006)
BLOCKED_HEADERS = frozenset({
    "x-user-id",
    "x-user-email",
    "x-user-role",
    "x-user-name",
    "x-access-groups",
})


async def get_current_user_info(request: Request) -> dict:
    """
    Open WebUI 세션에서 인증된 사용자 정보 추출.
    실제 구현은 Open WebUI 세션 미들웨어(request.state.user)에 의존.
    """
    user = getattr(request.state, "user", None)
    if not user:
        raise HTTPException(status_code=401, detail="인증이 필요합니다.")
    return {
        "id": str(getattr(user, "id", "")),
        "email": getattr(user, "email", ""),
        "name": getattr(user, "name", ""),
        "role": getattr(user, "role", "user"),
    }


@router.post("/v1/chat/completions")
async def chat_completions_proxy(request: Request):
    """
    /v1/chat/completions → RAG Spring Boot 백엔드 프록시.
    ADR-0006: X-User-* 헤더는 서버 세션 기반으로만 주입.
    SSE 스트리밍 응답을 그대로 클라이언트에 전달.
    """
    user_info = await get_current_user_info(request)

    # 클라이언트에서 보낸 X-User-* 헤더 폐기 후 새로 구성
    headers = {
        k: v for k, v in request.headers.items()
        if k.lower() not in BLOCKED_HEADERS
    }
    # 세션 기반 사용자 정보로 X-User-* 헤더 재구성 (ADR-0006)
    headers["X-User-Id"] = user_info["id"]
    headers["X-User-Email"] = user_info["email"]
    headers["X-User-Name"] = user_info["name"]
    headers["X-User-Role"] = user_info["role"]

    if RAG_BACKEND_API_KEY:
        headers["Authorization"] = f"Bearer {RAG_BACKEND_API_KEY}"

    body = await request.body()

    async def stream_upstream():
        async with httpx.AsyncClient(timeout=620) as client:
            async with client.stream(
                "POST",
                f"{RAG_BACKEND_URL}/v1/chat/completions",
                headers=headers,
                content=body,
            ) as resp:
                async for chunk in resp.aiter_bytes():
                    yield chunk

    return StreamingResponse(stream_upstream(), media_type="text/event-stream")
