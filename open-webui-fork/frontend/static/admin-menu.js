/**
 * RagVault — 어드민 패널 메뉴 주입 (inject_admin_menu.py 로 index.html 에 삽입됨)
 *
 * 역할: Open WebUI 프로필 드롭다운에 "/admin" 링크를 추가한다.
 * 권한: /auth/verify 응답의 is_admin === true 일 때만 렌더 (SUPER_ADMIN / ADMIN 모두 해당)
 * 주입 방식: MutationObserver 로 드롭다운 등장을 감지 후 삽입
 */
(function () {
  'use strict';

  var ADMIN_URL  = window.location.protocol + '//' + window.location.hostname + ':18090';
  var ADMIN_TEXT = '관리자 패널';
  var BTN_ID     = 'ragvault-admin-btn';

  var _isAdmin = null;           // null = 미확인, true/false = 확인 완료
  var _pending = false;          // 중복 API 호출 방지

  /**
   * /auth/verify 로 is_admin 여부를 조회 (1회 캐시).
   * 인증 전 / 네트워크 오류 시 false 반환.
   */
  function checkAdmin(callback) {
    if (_isAdmin !== null) { callback(_isAdmin); return; }
    if (_pending)          { return; }
    _pending = true;
    fetch('/auth/verify', { credentials: 'include' })
      .then(function (res) { return res.ok ? res.json() : null; })
      .then(function (data) {
        _isAdmin = !!(data && data.is_admin === true);
        callback(_isAdmin);
      })
      .catch(function () {
        _isAdmin = false;
        callback(false);
      });
  }

  /**
   * 드롭다운 루트 요소 후보를 찾는다.
   * Open WebUI 프로필 메뉴는 "Sign out" 또는 "로그아웃" 텍스트를 포함한다.
   */
  function isProfileDropdown(node) {
    if (node.nodeType !== 1) return false;
    var text = node.textContent || '';
    return text.includes('Sign out') || text.includes('로그아웃');
  }

  /**
   * 드롭다운 안에 어드민 링크를 삽입한다.
   * "Sign out / 로그아웃" 버튼 바로 앞에 위치시킨다.
   */
  function injectAdminLink(container) {
    if (document.getElementById(BTN_ID)) return;  // 이미 삽입됨

    var items = Array.from(container.querySelectorAll('button, a'));
    var signOutEl = items.find(function (el) {
      var t = (el.textContent || '').trim().toLowerCase();
      return t === 'sign out' || t === '로그아웃';
    });

    if (!signOutEl) return;  // 드롭다운 구조 불일치 → 건너뜀

    var adminLink = document.createElement('a');
    adminLink.id        = BTN_ID;
    adminLink.href      = ADMIN_URL;
    adminLink.className = signOutEl.className;           // Open WebUI 스타일 그대로 사용
    adminLink.textContent = ADMIN_TEXT;

    signOutEl.parentNode.insertBefore(adminLink, signOutEl);
  }

  /**
   * 추가된 DOM 노드와 그 하위에서 프로필 드롭다운을 탐색한다.
   */
  function scanAdded(node) {
    if (isProfileDropdown(node)) {
      injectAdminLink(node);
      return;
    }
    // 자식 중 후보가 있는지 확인
    var children = node.querySelectorAll ? node.querySelectorAll('*') : [];
    for (var i = 0; i < children.length; i++) {
      if (isProfileDropdown(children[i])) {
        injectAdminLink(children[i]);
        return;
      }
    }
  }

  var observer = new MutationObserver(function (mutations) {
    checkAdmin(function (admin) {
      if (!admin) return;
      for (var m = 0; m < mutations.length; m++) {
        var added = mutations[m].addedNodes;
        for (var n = 0; n < added.length; n++) {
          scanAdded(added[n]);
        }
      }
    });
  });

  // DOM 준비 후 관찰 시작
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () {
      observer.observe(document.body, { childList: true, subtree: true });
    });
  } else {
    observer.observe(document.body, { childList: true, subtree: true });
  }
})();
