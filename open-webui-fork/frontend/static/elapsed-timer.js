/**
 * RAG 응답 소요 시간 표시 스크립트.
 *
 * Open WebUI 의 fetch 를 인터셉트하여 스트리밍 응답의 시작~완료 시간을 측정하고,
 * 메시지 하단 액션 바(.buttons)에 "⏱ N.Ns" 배지를 삽입한다.
 *
 * 의존성: 없음 (순수 JS, Open WebUI DOM 구조에만 의존)
 * DOM 타깃: class="buttons" — ResponseMessage.svelte 의 액션 버튼 컨테이너
 */
(function () {
  'use strict';

  const _fetch = window.fetch.bind(window);

  window.fetch = async function (input, init) {
    const url =
      typeof input === 'string'
        ? input
        : input instanceof URL
        ? input.href
        : input && input.url
        ? input.url
        : '';

    // 채팅 관련 요청만 추적 (Open WebUI → RAG 백엔드 프록시 경로)
    const looksLikeChat =
      url.includes('chat') || url.includes('completions') || url.includes('generate');

    if (!looksLikeChat) return _fetch(input, init);

    const startTime = performance.now();

    let res;
    try {
      res = await _fetch(input, init);
    } catch (e) {
      throw e;
    }

    const contentType = res.headers.get('content-type') || '';

    // SSE 스트리밍 응답이 아니면 그대로 반환
    if (!res.body || !contentType.includes('text/event-stream')) {
      return res;
    }

    // body 를 두 갈래로 분기 — Open WebUI 에는 body1, 종료 감지에는 body2
    const [body1, body2] = res.body.tee();

    (async () => {
      const reader = body2.getReader();
      try {
        while (true) {
          const { done } = await reader.read();
          if (done) break;
        }
      } catch (_) {
        // 스트림 중단 또는 에러 — 그래도 시간 표시
      } finally {
        const elapsedMs = performance.now() - startTime;
        onStreamComplete(elapsedMs);
      }
    })();

    return new Response(body1, {
      status: res.status,
      statusText: res.statusText,
      headers: res.headers,
    });
  };

  /**
   * 스트리밍 완료 시 호출 — Svelte 반응성 업데이트를 기다렸다가 배지 삽입.
   */
  function onStreamComplete(elapsedMs) {
    const seconds = (elapsedMs / 1000).toFixed(1);
    // Svelte DOM 업데이트 후 삽입 (300ms 여유)
    setTimeout(() => injectElapsed(seconds), 300);
  }

  /**
   * 마지막 .buttons 컨테이너에 소요 시간 배지를 삽입한다.
   * .buttons 는 Open WebUI ResponseMessage.svelte 의 액션 버튼 행 클래스명.
   */
  function injectElapsed(seconds) {
    const bars = document.querySelectorAll('.buttons');
    if (!bars.length) return;

    const target = bars[bars.length - 1];

    // 중복 삽입 방지
    const prev = target.querySelector('.rag-elapsed');
    if (prev) prev.remove();

    const badge = document.createElement('div');
    badge.className = 'rag-elapsed';
    badge.title = '응답 소요 시간 (전송 ~ 스트리밍 완료)';
    badge.style.cssText = [
      'display: inline-flex',
      'align-items: center',
      'gap: 3px',
      'font-size: 11px',
      'color: #9ca3af',
      'padding: 0 6px',
      'white-space: nowrap',
      'user-select: none',
      'flex-shrink: 0',
      'align-self: center',
    ].join('; ');
    badge.textContent = '⏱ ' + seconds + 's';

    target.appendChild(badge);
  }
})();
