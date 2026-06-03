FROM ghcr.io/open-webui/open-webui:v0.6.5

# Fork 버전 레이블
LABEL fork.version="v0.6.5-ragvault1"
LABEL fork.description="RagVault 확장 — X-User-* 헤더, /v1/files 프록시, Admin /auth/verify, UX 컴포넌트"

# Backend 오버레이
COPY backend/ /app/backend/

# Frontend 오버레이
# Phase 0: Svelte 소스 배치만 (빌드는 CI/CD 파이프라인에서 수행)
COPY frontend/ /app/frontend/

# 응답 소요 시간 표시 스크립트 — 사전 빌드된 Open WebUI 에 직접 주입
# /static 경로는 /app/backend/open_webui/static/ 에서 제공됨 (Open WebUI env.py STATIC_DIR)
COPY frontend/static/elapsed-timer.js /app/backend/open_webui/static/elapsed-timer.js
RUN python3 -c "
content = open('/app/build/index.html').read()
snippet = '<script src=\"/static/elapsed-timer.js\" defer></script>'
if snippet not in content:
    content = content.replace(
        '<script src=\"/static/loader.js\" defer></script>',
        '<script src=\"/static/loader.js\" defer></script>\n\t\t' + snippet
    )
    open('/app/build/index.html', 'w').write(content)
"
