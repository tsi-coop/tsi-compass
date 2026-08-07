// Shared self-service topbar nav toggle — runs on every self-service page.
// Reveals the Supervisor-only "My Team" / "Approvals" nav links when the
// logged-in role is SUPERVISOR. Kept in one file (rather than duplicated
// inline per page) so every page shows the same nav consistently.
(function () {
  if (localStorage.getItem('role') !== 'SUPERVISOR') return;
  var team = document.getElementById('nav-team');
  var approvals = document.getElementById('nav-approvals');
  if (team) team.style.display = '';
  if (approvals) approvals.style.display = '';
}());
