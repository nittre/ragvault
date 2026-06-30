/*
 * RagVault Chatbot Widget — 임베드 로더
 *
 * 고객사 페이지에 한 줄로 삽입:
 *   <script src="https://widget.OURDOMAIN.com/loader.js" data-site-key="pk_live_xxx" async></script>
 *
 * 역할: 우하단 채팅 버블 버튼을 만들고, 클릭 시 우리 도메인의 chat.html 을 iframe 으로 토글한다.
 * iframe 격리 → 고객 페이지의 CSS/JS 와 충돌하지 않는다.
 */
(function () {
  'use strict';

  // 현재 스크립트 태그에서 설정 읽기.
  // 주의: async/defer 로 로드되면 document.currentScript 가 null 이므로 fallback 으로 찾는다.
  var script = document.currentScript
    || document.querySelector('script[data-site-key][src*="loader.js"]')
    || document.querySelector('script[data-site-key]');
  var siteKey = script && script.getAttribute('data-site-key');
  if (!siteKey) {
    console.error('[ragvault-widget] data-site-key 가 필요합니다.');
    return;
  }
  // loader.js 가 서빙되는 origin = 위젯 호스트 (chat.html 도 같은 곳)
  var widgetOrigin = new URL(script.src).origin;
  // (개발/데모) 백엔드가 위젯 호스트와 다른 origin 이면 data-api-base 로 주입
  var apiBase = script.getAttribute('data-api-base') || '';
  var chatUrl = widgetOrigin + '/chat.html?site=' + encodeURIComponent(siteKey)
    + (apiBase ? '&api=' + encodeURIComponent(apiBase) : '');

  var Z = 2147483000; // 거의 최상단

  // --- 버블 버튼 ---
  var bubble = document.createElement('button');
  bubble.setAttribute('aria-label', '채팅 열기');
  bubble.style.cssText = [
    'position:fixed', 'right:20px', 'bottom:20px', 'width:60px', 'height:60px',
    'border-radius:50%', 'border:none', 'cursor:pointer', 'z-index:' + Z,
    'background:#2563eb', 'color:#fff', 'font-size:26px', 'line-height:60px',
    'box-shadow:0 4px 16px rgba(0,0,0,.25)', 'transition:transform .15s ease'
  ].join(';');
  bubble.textContent = '💬';
  bubble.onmouseenter = function () { bubble.style.transform = 'scale(1.06)'; };
  bubble.onmouseleave = function () { bubble.style.transform = 'scale(1)'; };

  // --- iframe (채팅창) — 처음엔 숨김 ---
  var frame = document.createElement('iframe');
  frame.src = chatUrl;
  frame.title = 'RagVault 챗봇';
  frame.style.cssText = [
    'position:fixed', 'right:20px', 'bottom:92px', 'width:380px', 'height:560px',
    'max-width:calc(100vw - 40px)', 'max-height:calc(100vh - 120px)',
    'border:none', 'border-radius:16px', 'z-index:' + Z,
    'box-shadow:0 8px 32px rgba(0,0,0,.28)', 'display:none', 'background:#fff'
  ].join(';');

  var open = false;
  function toggle() {
    open = !open;
    frame.style.display = open ? 'block' : 'none';
    bubble.textContent = open ? '✕' : '💬';
  }
  bubble.addEventListener('click', toggle);

  // chat.html 이 자신을 닫아달라고 요청할 수 있음 (postMessage)
  window.addEventListener('message', function (e) {
    if (e.origin !== widgetOrigin) return;            // 보안: 우리 origin 만 신뢰
    if (e.data && e.data.type === 'ragvault:close' && open) toggle();
  });

  document.body.appendChild(frame);
  document.body.appendChild(bubble);
})();
