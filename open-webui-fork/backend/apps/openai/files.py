"""
/v1/files → RAG Spring Boot /v1/files 프록시.
multipart/form-data 그대로 전달.

보안 원칙:
- RAG_BACKEND_URL: 환경변수로만 설정 (하드코딩 금지 — SSRF 방어)
- X-User-* 헤더는 세션에서 추출 (ADR-0006)
- 파일 크기 제한: 30MB (requirements/10)
"""
import httpx
import os
from fastapi import APIRouter, Request, UploadFile, File, HTTPException
from fastapi.responses import JSONResponse

router = APIRouter()

# SSRF 방어: upstream URL 은 환경변수에서만 수신
RAG_BACKEND_URL = os.getenv("RAG_BACKEND_URL", "http://rag-backend:8080")
RAG_BACKEND_API_KEY = os.getenv("RAG_BACKEND_API_KEY", "")

MAX_FILE_SIZE = 30 * 1024 * 1024  # 30MB (requirements/10)


@router.post("/v1/files")
async def upload_file_proxy(request: Request, file: UploadFile = File(...)):
    """
    첨부파일 → Spring Boot /v1/files 로 프록시.
    X-User-* 헤더 주입 (ADR-0006): 세션 기반으로만 구성.
    """
    user = getattr(request.state, "user", None)
    if not user:
        raise HTTPException(status_code=401, detail="인증이 필요합니다.")

    content = await file.read()
    if len(content) > MAX_FILE_SIZE:
        raise HTTPException(status_code=413, detail="파일 크기가 30MB를 초과합니다.")

    headers = {
        "X-User-Email": getattr(user, "email", ""),
        "X-User-Id": str(getattr(user, "id", "")),
        "X-User-Role": getattr(user, "role", "user"),
    }
    if RAG_BACKEND_API_KEY:
        headers["Authorization"] = f"Bearer {RAG_BACKEND_API_KEY}"

    async with httpx.AsyncClient(timeout=120) as client:
        resp = await client.post(
            f"{RAG_BACKEND_URL}/v1/files",
            headers=headers,
            files={"file": (file.filename, content, file.content_type)},
        )

    return JSONResponse(content=resp.json(), status_code=resp.status_code)


@router.delete("/v1/files/{file_id}")
async def delete_file_proxy(file_id: str, request: Request):
    """
    파일 삭제 프록시.
    X-User-* 헤더 주입 (ADR-0006): 세션 기반으로만 구성.
    """
    user = getattr(request.state, "user", None)
    if not user:
        raise HTTPException(status_code=401, detail="인증이 필요합니다.")

    headers = {
        "X-User-Email": getattr(user, "email", ""),
        "X-User-Id": str(getattr(user, "id", "")),
    }
    if RAG_BACKEND_API_KEY:
        headers["Authorization"] = f"Bearer {RAG_BACKEND_API_KEY}"

    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.delete(
            f"{RAG_BACKEND_URL}/v1/files/{file_id}",
            headers=headers,
        )
    return JSONResponse(content=resp.json(), status_code=resp.status_code)
