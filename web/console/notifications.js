// Admin notification bell — runs on every authenticated console page (loaded
// next to rbac.js). Self-contained: injects its own markup/styles so no page
// needs a bell placeholder in its header, and pages can keep differing layouts.
(function () {
  var TOKEN = localStorage.getItem('token');
  if (!TOKEN) return; // rbac.js / page guards handle the unauthenticated redirect

  var POLL_MS = 30000;
  var state = { items: [], unread: 0, open: false, loaded: false };

  function api(func, body, cb) {
    var payload = Object.assign({ _func: func }, body || {});
    fetch('/api/v1/admin/notification', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + TOKEN },
      body: JSON.stringify(payload)
    })
      .then(function (res) {
        if (res.status === 401) return null;
        return res.json();
      })
      .then(function (data) { cb(data && data.success ? data : null); })
      .catch(function () { cb(null); });
  }

  function timeAgo(iso) {
    if (!iso) return '';
    var diffMs = Date.now() - new Date(iso).getTime();
    var mins = Math.round(diffMs / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return mins + 'm ago';
    var hrs = Math.round(mins / 60);
    if (hrs < 24) return hrs + 'h ago';
    return Math.round(hrs / 24) + 'd ago';
  }

  function esc(s) {
    if (s == null) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  var css = '' +
    '#nb-wrap{position:fixed;top:16px;right:22px;z-index:1000;font-family:Inter,ui-sans-serif,system-ui,sans-serif}' +
    '#nb-btn{position:relative;width:38px;height:38px;border-radius:50%;background:#fff;border:1px solid var(--line,#d9e2ec);cursor:pointer;display:grid;place-items:center;box-shadow:0 1px 3px rgba(0,0,0,.08)}' +
    '#nb-btn:hover{border-color:#b2d8d7}' +
    '#nb-badge{position:absolute;top:-4px;right:-4px;min-width:16px;height:16px;padding:0 3px;border-radius:8px;background:var(--red,#b42318);color:#fff;font-size:10px;font-weight:800;display:none;align-items:center;justify-content:center;line-height:1}' +
    '#nb-panel{position:absolute;top:46px;right:0;width:340px;max-height:420px;overflow-y:auto;background:#fff;border:1px solid var(--line,#d9e2ec);border-radius:10px;box-shadow:0 12px 32px rgba(0,0,0,.16);display:none}' +
    '#nb-panel.open{display:block}' +
    '#nb-head{display:flex;align-items:center;justify-content:space-between;padding:12px 14px;border-bottom:1px solid var(--line,#d9e2ec)}' +
    '#nb-head h3{margin:0;font-size:13px;font-weight:800;color:var(--ink,#172033)}' +
    '#nb-mark-all{font-size:11px;font-weight:700;color:var(--teal,#006a67);cursor:pointer;background:none;border:none;padding:0}' +
    '#nb-list{padding:4px}' +
    '.nb-item{display:block;padding:10px 12px;border-radius:6px;cursor:pointer;text-decoration:none;color:inherit}' +
    '.nb-item:hover{background:var(--wash,#f3f7f8)}' +
    '.nb-item.unread{background:#f0f9f8}' +
    '.nb-title{font-size:12.5px;font-weight:800;color:var(--ink,#172033);display:flex;align-items:center;gap:6px}' +
    '.nb-dot{width:6px;height:6px;border-radius:50%;background:var(--teal,#006a67);flex-shrink:0}' +
    '.nb-msg{font-size:12px;color:var(--muted,#607086);margin-top:2px;line-height:1.4}' +
    '.nb-time{font-size:10.5px;color:var(--muted,#607086);margin-top:4px}' +
    '#nb-empty{padding:28px 14px;text-align:center;font-size:12.5px;color:var(--muted,#607086)}';

  var styleEl = document.createElement('style');
  styleEl.textContent = css;
  document.head.appendChild(styleEl);

  var wrap = document.createElement('div');
  wrap.id = 'nb-wrap';
  wrap.innerHTML =
    '<button id="nb-btn" type="button" aria-label="Notifications">' +
    '<svg xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="#172033" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>' +
    '<span id="nb-badge"></span>' +
    '</button>' +
    '<div id="nb-panel">' +
    '<div id="nb-head"><h3>Notifications</h3><button id="nb-mark-all" type="button">Mark all read</button></div>' +
    '<div id="nb-list"></div>' +
    '</div>';

  function mount() {
    document.body.appendChild(wrap);
    document.getElementById('nb-btn').addEventListener('click', togglePanel);
    document.getElementById('nb-mark-all').addEventListener('click', markAllRead);
    document.addEventListener('click', function (e) {
      if (state.open && !wrap.contains(e.target)) closePanel();
    });
  }

  function render() {
    var badge = document.getElementById('nb-badge');
    if (state.unread > 0) {
      badge.textContent = state.unread > 99 ? '99+' : String(state.unread);
      badge.style.display = 'flex';
    } else {
      badge.style.display = 'none';
    }

    var list = document.getElementById('nb-list');
    if (!state.items.length) {
      list.innerHTML = '<div id="nb-empty">You\'re all caught up.</div>';
      return;
    }
    list.innerHTML = state.items.map(function (n) {
      return '<a class="nb-item' + (n.read ? '' : ' unread') + '" href="' + esc(n.link_url) + '" data-id="' + esc(n.id) + '">' +
        '<div class="nb-title">' + (n.read ? '' : '<span class="nb-dot"></span>') + esc(n.title) + '</div>' +
        '<div class="nb-msg">' + esc(n.message || '') + '</div>' +
        '<div class="nb-time">' + timeAgo(n.created_at) + '</div>' +
        '</a>';
    }).join('');

    Array.prototype.forEach.call(list.querySelectorAll('.nb-item'), function (el) {
      el.addEventListener('click', function (e) {
        var id = el.getAttribute('data-id');
        api('mark_notification_read', { id: id }, function () {});
      });
    });
  }

  function refresh(cb) {
    api('list_notifications', {}, function (data) {
      if (data) {
        state.items = data.notifications || [];
        state.unread = data.unread_count || 0;
        state.loaded = true;
        render();
      }
      if (cb) cb();
    });
  }

  function togglePanel() {
    if (state.open) { closePanel(); return; }
    state.open = true;
    document.getElementById('nb-panel').classList.add('open');
    refresh();
  }

  function closePanel() {
    state.open = false;
    document.getElementById('nb-panel').classList.remove('open');
  }

  function markAllRead() {
    api('mark_all_notifications_read', {}, function () {
      refresh();
    });
  }

  function start() {
    mount();
    refresh();
    setInterval(refresh, POLL_MS);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
