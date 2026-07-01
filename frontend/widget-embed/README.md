# frontend/widget-embed (위젯 임베드 자산)

**위젯 서비스**를 고객사 웹페이지에 삽입하기 위한 **임베드 자산**입니다. 빌드 과정이 없는 정적 파일 묶음으로, Nginx(`nginx:alpine`) 컨테이너가 그대로 서빙합니다(로컬 데모 포트 `8080`).

- 백엔드: [app-widget](../../app-widget/README.md) (`:8081`)
- 서빙: `infra/compose.*` 의 `widget-demo` 서비스 (Nginx + [`widget-nginx.conf`](../../infra/widget/nginx/widget-nginx.conf) — API 요청을 백엔드로 프록시)

---

## 구성 파일

| 파일 | 역할 |
|------|------|
| `loader.js` | 고객사 페이지에 삽입되는 **로더 스크립트**. 우하단 채팅 버블 버튼을 만들고, 클릭 시 `chat.html` 을 **iframe** 으로 토글 |
| `chat.html` | 실제 채팅 UI. iframe 안에서 로드되어 고객 페이지의 CSS/JS 와 격리됨 |
| `demo.html` | 임베드 동작을 확인하는 데모 페이지 |

---

## 사용 방법 (고객사)

고객사는 페이지에 아래 한 줄만 추가하면 됩니다.

```html
<script src="https://widget.OURDOMAIN.com/loader.js"
        data-site-key="pk_live_xxx" async></script>
```

- `data-site-key` (필수) — 발급받은 Site-Key. 백엔드가 `X-Site-Key` 로 검증
- `data-api-base` (선택) — 백엔드 origin 이 위젯 호스트와 다를 때 지정(개발/데모용)

### 동작 원리
```
loader.js
  ├─ data-site-key 읽기 (async/defer 대비 currentScript fallback)
  ├─ 우하단 채팅 버블 버튼(💬) 생성 — z-index 최상단
  └─ 클릭 → chat.html?site=<siteKey>[&api=<apiBase>] 를 iframe 으로 표시/숨김
       (iframe 격리로 고객 페이지 스타일과 충돌 방지)
```

---

## 기술 스택

- 순수 **Vanilla JavaScript** + HTML (빌드 도구·프레임워크 없음)
- Nginx 정적 서빙 + API 리버스 프록시

> 별도 빌드가 없으므로 `loader.js` / `chat.html` 을 수정하면 컨테이너 재기동(또는 볼륨 마운트 새로고침)만으로 반영됩니다. 배포는 `infra/compose` 의 `widget-demo`(Nginx) 및 Jenkins `widget-frontend` 파이프라인을 통해 이뤄집니다.
