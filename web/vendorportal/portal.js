'use strict';
// Shared helpers for the vendor portal pages. Everything the page shows comes from /api/v1/vendorportal,
// which resolves the vendor from the signed-in account; no vendor or user id is ever sent from here.
var TOKEN = localStorage.getItem('token');
if (!TOKEN || localStorage.getItem('role') !== 'VENDOR') { window.location.replace('../index.html'); }

function call(func, body) {
  return fetch('/api/v1/vendorportal', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + TOKEN },
    body: JSON.stringify(Object.assign({ _func: func }, body || {}))
  }).then(function (r) {
    if (r.status === 401) { localStorage.clear(); window.location.replace('../index.html'); return null; }
    return r.json();
  }).then(function (d) {
    if (!d || !d.success) throw new Error((d && (d.message || d.error)) || 'Request failed');
    return d;
  });
}
function esc(s) { return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;'); }
function $(id) { return document.getElementById(id); }
function toast(m) { var t = $('toast'); t.textContent = m; t.style.display = 'block'; clearTimeout(t._h); t._h = setTimeout(function () { t.style.display = 'none'; }, 3200); }
function day(s) { return s ? esc(String(s).substring(0, 10)) : '-'; }
function fail(e) { toast(e && e.message ? e.message : String(e)); }

var STATUS = {
  SENT: ['Not started', 'info'], IN_PROGRESS: ['In progress', 'watch'], SUBMITTED: ['Submitted', 'good'],
  RETURNED: ['Returned: action needed', 'risk'], COMPLETED: ['Completed', 'neu']
};
function statusPill(s) { var x = STATUS[s] || [s, 'neu']; return '<span class="pill ' + x[1] + '">' + esc(x[0]) + '</span>'; }

// A full server-side logout (clears the session cookie and revokes the token), not just forgetting the token locally.
function signOut() {
  fetch('/api/v1/user', { method: 'POST', headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + TOKEN }, body: JSON.stringify({ _func: 'logout' }) })
    .catch(function () {}).then(function () { localStorage.clear(); window.location.replace('../index.html'); });
}
function topbar(name) {
  $('user-name').textContent = name || localStorage.getItem('username') || '';
}
