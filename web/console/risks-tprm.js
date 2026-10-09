'use strict';
// Third-Party Risk console (risks-tprm.html). All data comes from /api/v1/tprm; see docs/guides/third-party-risk-guide.md and docs/architecture/system-design.md.
var TOKEN = localStorage.getItem('token');
if (!TOKEN) window.location.href = '../index.html';
var IS_ADMIN = (localStorage.getItem('role') === 'ADMIN');

var VENDOR_TYPES = [['SAAS', 'SaaS'], ['CLOUD_INFRA', 'Cloud / infrastructure'], ['SERVICE_PROVIDER', 'Service provider'], ['CONSULTANT', 'Consultant'], ['HARDWARE', 'Hardware'], ['OTHER', 'Other']];
var TIERS = [['CRITICAL', 'Critical'], ['HIGH', 'High'], ['MEDIUM', 'Medium'], ['LOW', 'Low']];
var LIFECYCLE = [['PROSPECT', 'Prospect'], ['ONBOARDING', 'Onboarding'], ['ACTIVE', 'Active'], ['OFFBOARDING', 'Offboarding'], ['TERMINATED', 'Terminated']];
var DOC_TYPES = [['CERTIFICATE', 'Certificate'], ['SOC_REPORT', 'SOC report'], ['PENTEST_SUMMARY', 'Pen-test summary'], ['POLICY', 'Policy'], ['CONTRACT', 'Contract'], ['OTHER', 'Other']];
var OFFBOARDING_ITEMS = [['revoke_access', 'Revoke all access (accounts, VPN, API keys)'], ['return_delete_data', 'Data returned or deleted, with written confirmation'], ['retrieve_assets', 'Retrieve assets and credentials'], ['close_findings', 'Close or accept open findings'], ['final_invoice', 'Final invoice settled']];

var S = { owners: null, vendors: [], vendor: null, vendorTab: 'profile', assess: null, tab: 'overview' };

// ---------- plumbing ----------
function call(func, body) {
  return fetch('/api/v1/tprm', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + TOKEN },
    body: JSON.stringify(Object.assign({ _func: func }, body || {}))
  }).then(function (r) {
    if (r.status === 401) { localStorage.removeItem('token'); window.location.href = '../index.html'; return null; }
    return r.json();
  }).then(function (d) {
    if (!d || !d.success) throw new Error((d && (d.message || d.error)) || 'Request failed');
    return d;
  });
}
function esc(s) { return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'); }
function $(id) { return document.getElementById(id); }
function toast(m) { var t = $('toast'); t.textContent = m; t.style.display = 'block'; setTimeout(function () { t.style.display = 'none'; }, 3400); }
function fail(e) { alert('Error: ' + (e && e.message ? e.message : e)); }
function nice(s) { s = String(s == null ? '' : s).replace(/_/g, ' ').toLowerCase(); return s.charAt(0).toUpperCase() + s.slice(1); }
function badge(v, text) { return v ? '<span class="badge b-' + esc(v) + '">' + esc(text || nice(v)) + '</span>' : '<span style="color:var(--muted)">-</span>'; }
function day(s) { return s ? esc(String(s).substring(0, 10)) : '-'; }
function opts(list, sel, blank) {
  return (blank != null ? '<option value="">' + esc(blank) + '</option>' : '') + list.map(function (o) {
    return '<option value="' + esc(o[0]) + '"' + (o[0] === sel ? ' selected' : '') + '>' + esc(o[1]) + '</option>';
  }).join('');
}
function ownerOptions(sel) {
  return opts((S.owners || []).map(function (u) { return [u.id, u.username]; }), sel, '- none -');
}
function ensureOwners() {
  if (S.owners) return Promise.resolve();
  return call('list_owners').then(function (d) { S.owners = d.users || []; });
}
function show(id, on) { $(id).style.display = on ? '' : 'none'; }

// ---------- generic form modal ----------
// fields: {key,label,type,value,options,full,required,hint,placeholder}
function openForm(o) {
  var html = '<h3>' + esc(o.title) + '</h3>' + (o.intro ? '<p class="sub">' + o.intro + '</p>' : '') + '<div class="fgrid' + (o.three ? ' three' : '') + '">';
  o.fields.forEach(function (f) {
    var id = 'ff-' + f.key, v = f.value == null ? '' : f.value, lab = '<label' + (f.type === 'checkbox' ? '' : ' for="' + id + '"') + '>' + esc(f.label) + (f.required ? ' *' : '') + '</label>';
    var cls = f.full ? ' class="full"' : '', ctl;
    if (f.type === 'textarea') ctl = lab + '<textarea id="' + id + '" placeholder="' + esc(f.placeholder || '') + '">' + esc(v) + '</textarea>';
    else if (f.type === 'select') ctl = lab + '<select id="' + id + '">' + f.optionsHtml + '</select>';
    else if (f.type === 'checkbox') ctl = '<label class="chk"><input type="checkbox" id="' + id + '"' + (v ? ' checked' : '') + '> ' + esc(f.label) + '</label>';
    else if (f.type === 'file') ctl = lab + '<input type="file" id="' + id + '">';
    else ctl = lab + '<input type="' + (f.type || 'text') + '" id="' + id + '" value="' + esc(v) + '" placeholder="' + esc(f.placeholder || '') + '"' + (f.type === 'number' ? ' step="any"' : '') + '>';
    html += '<div' + cls + '>' + ctl + (f.hint ? '<div class="hint">' + f.hint + '</div>' : '') + '</div>';
  });
  html += '</div><div class="modal-foot">' + (o.extraButtons || '') + '<button class="btn" onclick="closeForm()">Cancel</button><button class="btn primary" id="ff-go">' + esc(o.submit || 'Save') + '</button></div>';
  $('form-box').innerHTML = html;
  $('m-form').style.display = 'flex';
  $('ff-go').onclick = function () {
    var vals = {};
    for (var i = 0; i < o.fields.length; i++) {
      var f = o.fields[i], el = $('ff-' + f.key);
      if (f.type === 'checkbox') vals[f.key] = el.checked;
      else if (f.type === 'file') { /* read below, once the other fields are validated */ }
      else if (f.type === 'number') vals[f.key] = el.value === '' ? null : Number(el.value);
      else vals[f.key] = el.value.trim();
      if (f.required && (vals[f.key] === '' || vals[f.key] == null) && f.type !== 'file') { alert(f.label + ' is required.'); el.focus(); return; }
    }
    var btn = $('ff-go'); btn.disabled = true;
    Promise.all(o.fields.filter(function (f) { return f.type === 'file' && $('ff-' + f.key).files[0]; }).map(function (f) {
      return readFile($('ff-' + f.key).files[0]).then(function (d) { vals[f.key] = d; });
    })).then(function () {
      for (var j = 0; j < o.fields.length; j++) if (o.fields[j].required && o.fields[j].type === 'file' && !vals[o.fields[j].key]) throw new Error(o.fields[j].label + ' is required.');
      return o.onSubmit(vals);
    }).then(function () { closeForm(); }).catch(function (e) { btn.disabled = false; fail(e); });
  };
}
function closeForm() { $('m-form').style.display = 'none'; }
function readFile(file) {
  return new Promise(function (res, rej) {
    var r = new FileReader();
    r.onload = function () { res({ name: file.name, data: String(r.result).split(',')[1] || '' }); };
    r.onerror = function () { rej(new Error('Could not read the file')); };
    r.readAsDataURL(file);
  });
}

// ---------- tabs ----------
function showTab(t) {
  S.tab = t;
  ['overview', 'vendors', 'assessments', 'findings', 'questionnaires'].forEach(function (k) {
    show('pane-' + k, k === t); $('tab-' + k).className = 'tab' + (k === t ? ' active' : '');
  });
  if (t === 'overview') loadOverview();
  if (t === 'vendors') loadVendors();
  if (t === 'assessments') loadAssessments();
  if (t === 'findings') loadFindings();
  if (t === 'questionnaires') loadQuestionnaires();
}
function refreshAll() { showTab(S.tab); if (S.vendor && $('drawer-bg').style.display === 'flex') openVendor(S.vendor.id, S.vendorTab, true); }

// ---------- overview ----------
function loadOverview() {
  $('pane-overview').innerHTML = '<div class="empty">Loading…</div>';
  Promise.all([call('get_tprm_metrics'), call('get_review_calendar', { days: 90 })]).then(function (r) {
    var m = r[0], cal = r[1].items || [];
    var tiles = (m.portal_enabled ? '' : '<div class="banner" style="background:#eef6ff;border-color:#c9dcf5;color:#2b4a7a">The vendor portal is switched off. Set <code>TSI_VENDOR_PORTAL_ENABLED=true</code> and restart to let vendors answer questionnaires online.</div>') + '<div class="cards six">' +
      tile('Vendors in scope', m.vendors_total, '', m.archived_count ? m.archived_count + ' archived' : '') +
      tile('Critical + high tier', (m.tier_critical || 0) + (m.tier_high || 0), 'red', 'Critical ' + m.tier_critical + ', High ' + m.tier_high + ', Medium ' + m.tier_medium + ', Low ' + m.tier_low) +
      tile('Reviews overdue', m.reviews_overdue, m.reviews_overdue ? 'red' : 'green', m.reviews_due_30 + ' more due within 30 days') +
      tile('Open findings', m.open_findings, m.overdue_findings ? 'amber' : '', m.overdue_findings + ' overdue, ' + m.high_findings + ' critical/high') +
      tile('Expiring in 60 days', (m.expiring_documents || 0) + (m.contracts_renewing || 0), 'amber', m.expiring_documents + ' documents, ' + m.contracts_renewing + ' contract renewals') +
      tile('Vendors with gaps', m.vendors_with_gaps, m.vendors_with_gaps ? 'red' : 'green', m.gap_total + ' gaps in total') +
      tile('Awaiting vendor response', m.awaiting_vendor, m.awaiting_vendor ? 'amber' : '', m.vendor_users + ' active portal user(s)') +
      tile('Submitted, ready for review', m.submitted_for_review, m.submitted_for_review ? 'red' : 'green', 'Vendor questionnaires to review') + '</div>';
    var gaps = (m.gaps || []).length ? '<table><thead><tr><th>Vendor</th><th>Gap</th><th>Reference</th><th>Severity</th><th></th></tr></thead><tbody>' +
      m.gaps.map(function (g) {
        return '<tr><td><strong>' + esc(g.vendor_name) + '</strong></td><td>' + esc(g.label) + '</td><td>' + esc(g.reference) + '</td><td>' + badge(g.severity) + '</td>' +
          '<td><button class="btn sm" onclick="openVendor(\'' + esc(g.vendor_id) + '\')">Open</button></td></tr>';
      }).join('') + '</tbody></table>' : '<div class="empty">No gaps found. Gaps appear when personal data is shared without a DPA, reviews lapse, certificates expire, or serious findings pass their due date.</div>';
    var calHtml = cal.length ? '<table><thead><tr><th>Due</th><th>Type</th><th>Vendor</th><th>Item</th></tr></thead><tbody>' + cal.slice(0, 40).map(function (i) {
      return '<tr><td class="' + (i.overdue ? 'cal-OVERDUE' : '') + '">' + day(i.due) + (i.overdue ? ' (overdue)' : '') + '</td><td>' + badge('OPEN', nice(i.kind)) + '</td><td>' +
        '<a href="#" onclick="openVendor(\'' + esc(i.vendor_id) + '\');return false" style="color:var(--teal);font-weight:700">' + esc(i.vendor_name) + '</a></td><td>' + esc(i.label) + '</td></tr>';
    }).join('') + '</tbody></table>' : '<div class="empty">Nothing due in the next 90 days.</div>';
    $('pane-overview').innerHTML = tiles +
      '<div class="section-card"><div style="padding:14px 20px;font-weight:800;font-size:13px">Gaps needing attention</div><div class="table-scroll">' + gaps + '</div></div>' +
      '<div class="section-card"><div style="padding:14px 20px;font-weight:800;font-size:13px">Review calendar (next 90 days, plus anything overdue)</div><div class="table-scroll">' + calHtml + '</div></div>';
  }).catch(function (e) { $('pane-overview').innerHTML = '<div class="empty">' + esc(e.message) + '</div>'; });
}
function tile(label, val, cls, note) {
  return '<div class="mcard ' + (cls || '') + '"><div class="mlabel">' + esc(label) + '</div><div class="mval">' + esc(val == null ? '-' : val) + '</div>' +
    (note ? '<div style="font-size:11.5px;color:var(--muted);margin-top:2px">' + esc(note) + '</div>' : '') + '</div>';
}

// ---------- vendors ----------
function loadVendors() {
  var p = $('pane-vendors');
  if (!p.dataset.ready) {
    p.dataset.ready = '1';
    p.innerHTML = '<div class="section-card"><div class="filter-bar">' +
      '<div><label>Search</label><input type="text" id="fv-search" placeholder="Vendor name" oninput="debounceVendors()" style="height:34px;border:1px solid var(--line);border-radius:6px;padding:0 10px;min-width:200px"></div>' +
      '<div><label>Tier</label><select id="fv-tier" onchange="loadVendors()" style="min-width:140px">' + opts(TIERS, '', 'All') + '</select></div>' +
      '<div><label>Lifecycle</label><select id="fv-life" onchange="loadVendors()" style="min-width:150px">' + opts(LIFECYCLE, '', 'All') + '</select></div>' +
      '<div><label class="chk" style="display:flex;gap:6px;align-items:center;text-transform:none;letter-spacing:0"><input type="checkbox" id="fv-gaps" onchange="loadVendors()"> With gaps only</label></div>' +
      '<div><label class="chk" style="display:flex;gap:6px;align-items:center;text-transform:none;letter-spacing:0"><input type="checkbox" id="fv-arch" onchange="loadVendors()"> Show archived</label></div>' +
      '<div style="margin-left:auto"><button class="btn" onclick="recalcAll()">Recalculate all tiers</button></div></div></div>' +
      '<div class="section-card"><div class="table-scroll"><table><thead><tr><th>Vendor</th><th>Type</th><th>Criticality</th><th>Inherent</th><th>Residual</th><th>Lifecycle</th><th>Next review</th><th>Gaps</th><th>Open findings</th><th></th></tr></thead>' +
      '<tbody id="vendor-body"><tr><td colspan="10" class="empty">Loading…</td></tr></tbody></table></div></div>';
  }
  var body = { show_archived: $('fv-arch').checked, with_gaps: $('fv-gaps').checked };
  if ($('fv-tier').value) body.tier = $('fv-tier').value;
  if ($('fv-life').value) body.lifecycle_status = $('fv-life').value;
  if ($('fv-search').value.trim()) body.search = $('fv-search').value.trim();
  call('list_vendors_tprm', body).then(function (d) {
    S.vendors = d.vendors || [];
    $('vendor-body').innerHTML = S.vendors.length ? S.vendors.map(function (v) {
      var rs = v.review_status === 'OK' ? '' : ' ' + badge(v.review_status, v.review_status === 'NEVER' ? 'Never assessed' : nice(v.review_status));
      return '<tr><td><strong>' + esc(v.name) + '</strong>' + (v.archived_at ? ' <span class="badge b-TERMINATED">Archived</span>' : '') + (v.override_active ? ' <span class="chip" title="' + esc(v.inherent_override_reason) + '">override</span>' : '') + '</td>' +
        '<td>' + esc(nice(v.vendor_type) || '-') + '</td><td>' + badge(v.criticality) + '</td><td>' + badge(v.inherent_risk) + '</td><td>' + badge(v.residual_risk) + '</td>' +
        '<td>' + badge(v.lifecycle_status) + '</td><td>' + day(v.next_review_due) + rs + '</td>' +
        '<td>' + (v.gap_count ? '<span class="badge b-HIGH">' + v.gap_count + '</span>' : '0') + '</td><td>' + (v.open_findings || 0) + '</td>' +
        '<td><button class="btn sm primary" onclick="openVendor(\'' + esc(v.id) + '\')">View</button></td></tr>';
    }).join('') : '<tr><td colspan="10" class="empty">No vendors match. Use <strong>+ Add vendor</strong>, or add them on the IT Operations Vendor Register.</td></tr>';
  }).catch(function (e) { $('vendor-body').innerHTML = '<tr><td colspan="10" class="empty">' + esc(e.message) + '</td></tr>'; });
}
var _vt; function debounceVendors() { clearTimeout(_vt); _vt = setTimeout(loadVendors, 250); }
function recalcAll() {
  call('recalculate_inherent_risk', {}).then(function (d) { toast('Recalculated ' + d.recalculated + ' vendor(s)'); loadVendors(); }).catch(fail);
}

function vendorFields(v) {
  v = v || {};
  return [
    { key: 'name', label: 'Vendor name', required: true, value: v.name, full: true },
    { key: 'vendor_type', label: 'Type', type: 'select', optionsHtml: opts(VENDOR_TYPES, v.vendor_type, '- select -') },
    { key: 'criticality', label: 'Business criticality', type: 'select', optionsHtml: opts(TIERS, v.criticality, '- select -'), hint: 'How badly would losing this vendor hurt the business?' },
    { key: 'business_owner_id', label: 'Business owner', type: 'select', optionsHtml: ownerOptions(v.business_owner_id) },
    { key: 'review_cycle_months', label: 'Review cycle (months)', type: 'number', value: v.review_cycle_months, placeholder: v.effective_review_cycle_months ? 'Default ' + v.effective_review_cycle_months : 'By tier' },
    { key: 'primary_contact_name', label: 'Primary contact', value: v.primary_contact_name },
    { key: 'primary_contact_email', label: 'Contact email', value: v.primary_contact_email },
    { key: 'website', label: 'Website', value: v.website, full: true },
    { key: 'description', label: 'What the vendor does for us', type: 'textarea', value: v.description, full: true },
    { key: 'processes_personal_data', label: 'Processes personal data on our behalf', type: 'checkbox', value: v.processes_personal_data },
    { key: 'stores_data_outside_india', label: 'Stores or processes data outside India', type: 'checkbox', value: v.stores_data_outside_india },
    { key: 'has_network_access', label: 'Has network or system access', type: 'checkbox', value: v.has_network_access },
    { key: 'is_fourth_party_dependent', label: 'Relies on fourth parties (sub-processors)', type: 'checkbox', value: v.is_fourth_party_dependent }
  ];
}
function cleanProfile(vals) {
  var o = Object.assign({}, vals);
  if (o.review_cycle_months == null) o.review_cycle_months = ''; // blank clears the override and falls back to the tier default
  return o;
}
function addVendor() {
  ensureOwners().then(function () {
    openForm({
      title: 'Add vendor', submit: 'Add vendor', fields: vendorFields(),
      intro: 'The risk tier is calculated from these answers and from the Data Governance register (data assets that list this vendor as a recipient).',
      onSubmit: function (v) { return call('create_vendor', cleanProfile(v)).then(function (d) { toast('Added. Inherent risk: ' + nice(d.inherent_risk)); S.tab === 'vendors' ? loadVendors() : showTab('vendors'); openVendor(d.id); }); }
    });
  }).catch(fail);
}

// ---------- vendor drawer ----------
function openVendor(id, tab, quiet) {
  S.vendorTab = tab || (quiet ? S.vendorTab : 'profile') || 'profile';
  ensureOwners().then(function () { return call('get_vendor_profile', { id: id }); }).then(function (d) {
    if (quiet && $('drawer-bg').style.display !== 'flex') return; // closed while the refresh was in flight
    S.vendor = d.vendor;
    $('drawer-bg').style.display = 'flex';
    renderDrawer();
  }).catch(fail);
}
function closeDrawer() { $('drawer-bg').style.display = 'none'; }
$('drawer-bg').addEventListener('mousedown', function (e) { if (e.target === this) closeDrawer(); });

function renderDrawer() {
  var v = S.vendor, ro = !!v.archived_at;
  var tabs = [['profile', 'Profile'], ['assessments', 'Assessments'], ['findings', 'Findings'], ['contracts', 'Contracts & documents'], ['portal', 'Portal users'], ['linked', 'Linked data'], ['lifecycle', 'Lifecycle']];
  $('drawer').innerHTML =
    '<div style="display:flex;justify-content:space-between;gap:12px;padding-right:56px"><div><h2>' + esc(v.name) + '</h2><div>' +
    badge(v.inherent_risk, 'Inherent ' + nice(v.inherent_risk)) + ' ' + (v.residual_risk ? badge(v.residual_risk, 'Residual ' + nice(v.residual_risk)) + ' ' : '') +
    badge(v.lifecycle_status) + (ro ? ' <span class="badge b-TERMINATED">Archived - read only</span>' : '') +
    (v.gap_count ? ' <span class="badge b-HIGH">' + v.gap_count + ' gap' + (v.gap_count === 1 ? '' : 's') + '</span>' : '') + '</div></div>' +
    '<div class="list-actions" style="align-items:flex-start">' +
    (ro ? '<button class="btn primary" onclick="restoreVendor()">Restore</button>' : '<button class="btn primary" onclick="startAssessment()">Start assessment</button><button class="btn danger" onclick="archiveVendor()">Archive</button>') +
    '<button class="btn" onclick="closeDrawer()">Close</button></div></div>' +
    '<div class="subtabs">' + tabs.map(function (t) { return '<button class="subtab' + (S.vendorTab === t[0] ? ' active' : '') + '" onclick="vendorTab(\'' + t[0] + '\')">' + t[1] + '</button>'; }).join('') + '</div>' +
    '<div id="vtab-body"></div>';
  vendorTab(S.vendorTab);
}
function vendorTab(t) {
  S.vendorTab = t;
  Array.prototype.forEach.call(document.querySelectorAll('.subtab'), function (b, i) {
    b.className = 'subtab' + (['profile', 'assessments', 'findings', 'contracts', 'portal', 'linked', 'lifecycle'][i] === t ? ' active' : '');
  });
  var f = { profile: tabProfile, assessments: tabAssessments, findings: tabFindings, contracts: tabContracts, portal: tabPortal, linked: tabLinked, lifecycle: tabLifecycle }[t];
  $('vtab-body').innerHTML = '<div class="empty">Loading…</div>';
  f();
}
function archiveVendor() {
  if (!confirm('Archive "' + S.vendor.name + '"?\n\nIt is hidden from lists and counts and becomes read-only. Assessments, findings, contracts and documents are kept, and you can restore it later.')) return;
  call('archive_vendor', { id: S.vendor.id }).then(function () { toast('Vendor archived'); closeDrawer(); refreshAll(); }).catch(fail);
}
function restoreVendor() {
  call('restore_vendor', { id: S.vendor.id }).then(function () { toast('Vendor restored'); openVendor(S.vendor.id, 'profile'); refreshAfterVendorChange(); }).catch(fail);
}

function tabProfile() {
  var v = S.vendor, ro = !!v.archived_at;
  var factors = (v.risk_factors || []).map(function (f) { return '<li>' + esc(f.label) + ' <small>(' + esc(f.reference) + ', +' + f.points + ')</small></li>'; }).join('') || '<li>No risk factors recorded yet. Set the criticality and the data flags.</li>';
  var gaps = (v.gaps || []).map(function (g) { return '<li class="gap-' + esc(g.severity) + '">' + esc(g.label) + '<small>' + esc(g.reference) + '</small></li>'; }).join('') || '<li>No gaps. </li>';
  $('vtab-body').innerHTML =
    '<div class="kv"><div><label>Type</label><span>' + esc(nice(v.vendor_type) || '-') + '</span></div><div><label>Criticality</label><span>' + badge(v.criticality) + '</span></div><div><label>Business owner</label><span>' + esc(v.business_owner_name || '-') + '</span></div>' +
    '<div><label>Primary contact</label><span>' + esc(v.primary_contact_name || '-') + (v.primary_contact_email ? ' &lt;' + esc(v.primary_contact_email) + '&gt;' : '') + '</span></div><div><label>Website</label><span>' + esc(v.website || '-') + '</span></div><div><label>Review cycle</label><span>' + esc(v.effective_review_cycle_months) + ' months</span></div>' +
    '<div><label>Last assessed</label><span>' + day(v.last_assessed_at) + '</span></div><div><label>Next review due</label><span>' + day(v.next_review_due) + ' ' + (v.review_status === 'OK' ? '' : badge(v.review_status, v.review_status === 'NEVER' ? 'Never assessed' : nice(v.review_status))) + '</span></div><div><label>Legacy licence / agreement expiry</label><span>' + day(v.license_expiry) + '</span></div></div>' +
    (v.description ? '<p style="font-size:13px;line-height:1.6">' + esc(v.description) + '</p>' : '') +
    (ro ? '' : '<div class="list-actions" style="margin-bottom:16px"><button class="btn" onclick="editProfile()">Edit profile</button><button class="btn" onclick="overrideTier()">' + (v.override_active ? 'Change tier override' : 'Override tier') + '</button><button class="btn" onclick="recalcOne()">Recalculate tier</button></div>') +
    '<div class="dgrid"><div class="dsec"><h4>Inherent risk: ' + esc(nice(v.inherent_risk)) + ' (score ' + v.inherent_score + ')</h4><ul>' + factors + '</ul>' +
    (v.override_active ? '<div class="banner" style="margin-top:10px">Tier overridden from <strong>' + esc(nice(v.inherent_derived)) + '</strong> to <strong>' + esc(nice(v.inherent_risk)) + '</strong>. Reason: ' + esc(v.inherent_override_reason) + '</div>' : '') +
    '<div class="hint" style="margin-top:8px">Score 8+ is Critical, 5-7 High, 3-4 Medium, 2 or less Low. Personal data and sensitive-data signals also come from Data Governance links.</div></div>' +
    '<div class="dsec"><h4>Gaps</h4><ul class="gaplist">' + gaps + '</ul></div></div>';
}
function editProfile() {
  openForm({
    title: 'Edit vendor profile', submit: 'Save', fields: vendorFields(S.vendor),
    onSubmit: function (vals) { return call('update_vendor_profile', Object.assign({ id: S.vendor.id }, cleanProfile(vals))).then(function () { toast('Saved'); openVendor(S.vendor.id, 'profile'); refreshAfterVendorChange(); }); }
  });
}
function refreshAfterVendorChange() { if (S.tab === 'vendors') loadVendors(); if (S.tab === 'overview') loadOverview(); }
function overrideTier() {
  var v = S.vendor;
  openForm({
    title: 'Override risk tier', submit: 'Apply override',
    intro: 'The calculated tier is <strong>' + esc(nice(v.inherent_derived)) + '</strong>. An override needs a reason, which stays on record.',
    extraButtons: v.override_active ? '<button class="btn danger" onclick="clearOverride()" style="margin-right:auto">Clear override</button>' : '',
    fields: [
      { key: 'inherent_override', label: 'Tier', type: 'select', optionsHtml: opts(TIERS, v.inherent_override || v.inherent_risk), required: true },
      { key: 'inherent_override_reason', label: 'Reason', type: 'textarea', value: v.inherent_override_reason, required: true, full: true }
    ],
    onSubmit: function (vals) { return call('update_vendor_profile', { id: v.id, inherent_override: vals.inherent_override, inherent_override_reason: vals.inherent_override_reason }).then(function () { toast('Override applied'); openVendor(v.id, 'profile'); refreshAfterVendorChange(); }); }
  });
}
function clearOverride() {
  call('update_vendor_profile', { id: S.vendor.id, inherent_override: '' }).then(function () { closeForm(); toast('Override cleared'); openVendor(S.vendor.id, 'profile'); refreshAfterVendorChange(); }).catch(fail);
}
function recalcOne() { call('recalculate_inherent_risk', { id: S.vendor.id }).then(function () { toast('Recalculated'); openVendor(S.vendor.id, 'profile'); refreshAfterVendorChange(); }).catch(fail); }

// ---------- vendor portal ----------
function portalBadge(ps) {
  var m = { NOT_SENT: null, SENT: ['With vendor', 'IN_REVIEW'], IN_PROGRESS: ['Vendor answering', 'DUE_SOON'], SUBMITTED: ['Submitted by vendor', 'REMEDIATED'], RETURNED: ['Returned to vendor', 'OVERDUE'], COMPLETED: ['Closed', 'DRAFT'] }[ps];
  return m ? badge(m[1], m[0]) : '<span style="color:var(--muted)">-</span>';
}
function showSecret(title, intro, secret) {
  $('form-box').innerHTML = '<h3>' + esc(title) + '</h3><p class="sub">' + intro + '</p><div style="font-family:ui-monospace,monospace;font-size:17px;background:#f3f7f8;border:1px solid var(--line);border-radius:6px;padding:12px 14px;user-select:all;letter-spacing:.04em">' + esc(secret) + '</div>' +
    '<p class="hint">This is shown once and is not stored in readable form. Share it with the vendor through a secure channel, never in the same message as the sign-in address.</p><div class="modal-foot"><button class="btn" onclick="navigator.clipboard&&navigator.clipboard.writeText(' + JSON.stringify(secret).replace(/"/g, '&quot;') + ');toast(\'Copied\')">Copy</button><button class="btn primary" onclick="closeForm()">Done</button></div>';
  $('m-form').style.display = 'flex';
}
function tabPortal() {
  var v = S.vendor, ro = !!v.archived_at;
  call('list_vendor_users', { vendor_id: v.id }).then(function (d) {
    var rows = d.users.length ? '<table><thead><tr><th>Name</th><th>Email</th><th>Status</th><th>Created</th><th></th></tr></thead><tbody>' + d.users.map(function (u) {
      return '<tr><td><strong>' + esc(u.username) + '</strong></td><td>' + esc(u.email) + '</td><td>' + badge(u.status === 'ACTIVE' ? 'ACTIVE' : 'TERMINATED', u.status === 'ACTIVE' ? 'Active' : 'Disabled') + '</td><td>' + day(u.created_at) + '</td>' +
        '<td><div class="list-actions"><button class="btn sm" onclick="resetVendorUser(\'' + esc(u.id) + '\',\'' + esc(u.email) + '\')">Reset password</button>' +
        (u.status === 'ACTIVE' ? '<button class="btn sm danger" onclick="toggleVendorUser(\'' + esc(u.id) + '\',false)">Disable</button>' : '<button class="btn sm" onclick="toggleVendorUser(\'' + esc(u.id) + '\',true)">Enable</button>') + '</div></td></tr>';
    }).join('') + '</tbody></table>' : '<div class="empty">No portal users yet. Add the contacts at this vendor who should answer questionnaires online.</div>';
    $('vtab-body').innerHTML = (d.portal_enabled ? '' : '<div class="banner" style="background:#eef6ff;border-color:#c9dcf5;color:#2b4a7a">The vendor portal is switched off on this server. Accounts can be prepared, but vendors cannot sign in until <code>TSI_VENDOR_PORTAL_ENABLED=true</code> is set and the server restarted.</div>') +
      '<p class="sub" style="margin-top:0">Vendor contacts sign in at the normal sign-in page and are taken to the vendor portal. They see only the questionnaires you send to this vendor, never scores, findings or ratings. Accounts are created here, and a temporary password is shown once for you to share.</p>' +
      (ro ? '' : '<div style="margin-bottom:12px"><button class="btn primary" onclick="addPortalUser()">+ Add portal user</button></div>') + '<div class="table-scroll">' + rows + '</div>';
  }).catch(fail);
}
function addPortalUser() {
  openForm({
    title: 'Add portal user', submit: 'Create account', intro: 'A temporary password is generated and shown once.',
    fields: [{ key: 'username', label: 'Name', required: true }, { key: 'email', label: 'Email (used to sign in)', type: 'email', required: true }],
    onSubmit: function (v) {
      return call('create_vendor_user', { vendor_id: S.vendor.id, username: v.username, email: v.email }).then(function (d) {
        tabPortal(); setTimeout(function () { showSecret('Account created', 'Sign-in email: <strong>' + esc(d.email) + '</strong><br>Temporary password:', d.temporary_password); }, 50);
      });
    }
  });
}
function toggleVendorUser(id, enable) { call(enable ? 'enable_vendor_user' : 'disable_vendor_user', { id: id }).then(function () { toast(enable ? 'Account enabled' : 'Account disabled'); tabPortal(); }).catch(fail); }
function resetVendorUser(id, email) {
  if (!confirm('Reset the password for ' + email + '? Their current password stops working.')) return;
  call('reset_vendor_user_password', { id: id }).then(function (d) { showSecret('Password reset', 'Sign-in email: <strong>' + esc(email) + '</strong><br>New temporary password:', d.temporary_password); }).catch(fail);
}

// ---------- assessments ----------
function assessmentTable(list, showVendor) {
  if (!list.length) return '<div class="empty">No assessments yet.</div>';
  return '<table><thead><tr>' + (showVendor ? '<th>Vendor</th>' : '') + '<th>Started</th><th>Type</th><th>Questionnaire</th><th>Status</th><th>Vendor</th><th>Progress</th><th>Score</th><th>Residual</th><th>Conclusion</th><th></th></tr></thead><tbody>' +
    list.map(function (a) {
      return '<tr>' + (showVendor ? '<td><strong>' + esc(a.vendor_name) + '</strong>' + (a.vendor_archived ? ' <span class="badge b-TERMINATED">Archived</span>' : '') + '</td>' : '') +
        '<td>' + day(a.created_at) + '</td><td>' + esc(nice(a.assessment_type)) + '</td><td>' + esc(a.questionnaire_name) + '</td><td>' + badge(a.status) + '</td><td>' + portalBadge(a.portal_status) + '</td>' +
        '<td>' + a.answered + ' / ' + a.total_questions + '</td><td>' + (a.score_pct == null ? '-' : a.score_pct + '%') + '</td><td>' + badge(a.residual_risk) + '</td><td>' + esc(a.conclusion ? nice(a.conclusion) : '-') + '</td>' +
        '<td><button class="btn sm primary" onclick="openAssessment(\'' + esc(a.id) + '\')">' + (a.status === 'COMPLETED' || a.status === 'CANCELLED' ? 'View' : 'Continue') + '</button></td></tr>';
    }).join('') + '</tbody></table>';
}
function tabAssessments() {
  call('list_assessments', { vendor_id: S.vendor.id }).then(function (d) {
    $('vtab-body').innerHTML = (S.vendor.archived_at ? '' : '<div style="margin-bottom:12px"><button class="btn primary" onclick="startAssessment()">Start assessment</button></div>') +
      '<div class="table-scroll">' + assessmentTable(d.assessments, false) + '</div>';
  }).catch(fail);
}
function startAssessment() {
  var v = S.vendor;
  // One open assessment per vendor: take the user back to it instead of failing on the server.
  call('list_assessments', { vendor_id: v.id }).then(function (a) {
    var open = (a.assessments || []).filter(function (x) { return x.status === 'DRAFT' || x.status === 'SENT' || x.status === 'IN_REVIEW'; })[0];
    if (open) {
      if (confirm(v.name + ' already has an assessment in progress (' + nice(open.status) + ', started ' + day(open.created_at) + ').\n\nOpen it now? Complete or cancel it before starting another.')) openAssessment(open.id);
      return null;
    }
    return call('list_questionnaires');
  }).then(function (d) {
    if (!d) return;
    var qs = (d.questionnaires || []).filter(function (q) { return q.is_active; });
    if (!qs.length) { alert('No active questionnaire. An administrator can activate one on the Questionnaires tab.'); return; }
    var def = v.inherent_risk === 'LOW' ? 'a lighter-touch review' : 'a full review';
    openForm({
      title: 'Start assessment: ' + v.name, submit: 'Start',
      intro: 'Compass tiers this vendor as <strong>' + esc(nice(v.inherent_risk)) + '</strong>, so ' + def + ' is appropriate. You fill in the answers from the documents the vendor sends you (certificates, SOC reports, their completed questionnaire).',
      fields: [
        { key: 'questionnaire_id', label: 'Questionnaire', type: 'select', optionsHtml: opts(qs.map(function (q) { return [q.id, q.name + ' v' + q.version + ' (' + q.question_count + ' questions)']; }), qs[0].id), full: true },
        { key: 'assessment_type', label: 'Type', type: 'select', optionsHtml: opts([['ONBOARDING', 'Onboarding'], ['PERIODIC', 'Periodic review'], ['EVENT_DRIVEN', 'Event-driven (after an incident or change)']], v.last_assessed_at ? 'PERIODIC' : 'ONBOARDING') },
        { key: 'due_date', label: 'Due date', type: 'date' }
      ],
      onSubmit: function (vals) {
        return call('start_assessment', { vendor_id: v.id, questionnaire_id: vals.questionnaire_id, assessment_type: vals.assessment_type, due_date: vals.due_date }).then(function (r) {
          toast('Assessment started'); if (S.vendorTab === 'assessments') vendorTab('assessments'); refreshAfterVendorChange(); openAssessment(r.id);
        });
      }
    });
  }).catch(fail);
}
function loadAssessments() {
  var p = $('pane-assessments');
  if (!p.dataset.ready) {
    p.dataset.ready = '1';
    p.innerHTML = '<div class="section-card"><div class="filter-bar"><div><label>Status</label><select id="fa-status" onchange="loadAssessments()" style="min-width:160px">' +
      opts([['DRAFT', 'Draft'], ['SENT', 'Sent'], ['IN_REVIEW', 'In review'], ['COMPLETED', 'Completed'], ['CANCELLED', 'Cancelled']], '', 'All') + '</select></div></div>' +
      '<div class="table-scroll" id="assess-list"></div></div>';
  }
  var body = {}; if ($('fa-status').value) body.status = $('fa-status').value;
  call('list_assessments', body).then(function (d) { $('assess-list').innerHTML = assessmentTable(d.assessments, true); }).catch(function (e) { $('assess-list').innerHTML = '<div class="empty">' + esc(e.message) + '</div>'; });
}

function openAssessment(id) {
  call('get_assessment', { id: id }).then(function (d) { S.assess = d.assessment; renderAssessment(); $('m-assess').style.display = 'flex'; }).catch(fail);
}
function closeAssessment() { $('m-assess').style.display = 'none'; $('assess-box').innerHTML = ''; S.assess = null; refreshAll(); }
$('m-assess').addEventListener('mousedown', function (e) { if (e.target === this) closeAssessment(); });

function renderAssessment() {
  var a = S.assess, ps = a.portal_status, pr = a.progress;
  var withVendor = ['SENT', 'IN_PROGRESS', 'RETURNED'].indexOf(ps) >= 0;
  var live = ['DRAFT', 'SENT', 'IN_REVIEW'].indexOf(a.status) >= 0 && !a.vendor_archived;
  var open = live && !withVendor; // the assessor can edit only while the vendor does not hold the questionnaire
  var scored = pr.scored_questions, done = scored - pr.unanswered;
  var html = '<div style="display:flex;justify-content:space-between;gap:10px"><div><h3 style="margin-bottom:2px">' + esc(a.vendor_name) + '</h3><div class="sub" style="margin:0">' + esc(a.questionnaire_name) + ' &middot; ' + esc(nice(a.assessment_type)) + ' &middot; assessor ' + esc(a.assessor_name || '-') + '</div></div><div>' + badge(a.status) + ' ' + (ps !== 'NOT_SENT' ? portalBadge(ps) : '') + '</div></div>' +
    '<div style="margin:14px 0"><div style="display:flex;justify-content:space-between;font-size:12px;color:var(--muted);margin-bottom:4px"><span id="as-prog-txt">' + done + ' of ' + scored + ' scored questions answered</span><span>Live score: <strong id="as-score">' + (done ? pr.score_pct + '%' : '-') + '</strong></span></div><div class="bar"><i id="as-bar" style="width:' + (scored ? Math.round(100 * done / scored) : 0) + '%"></i></div></div>';
  if (withVendor) html += '<div class="banner" style="background:#fff8e6;border-color:#f3dca0;color:#7a5a12"><strong>With the vendor' + (a.portal_due_date ? ' (due ' + esc(a.portal_due_date.substring(0, 10)) + ')' : '') + '.</strong> Sent ' + day(a.sent_at) + '. Answers are read-only here until the vendor submits. You can withdraw it from the vendor to edit it yourself.</div>';
  if (ps === 'SUBMITTED' && a.status !== 'COMPLETED') html += '<div class="banner" style="background:#eef6f2;border-color:#bfe0cc;color:#1f5a3c"><strong>Submitted by ' + esc(a.submitted_by_name || 'the vendor') + ' on ' + day(a.submitted_at) + '.</strong> Review the vendor\'s answers. You can correct an answer (the vendor\'s original is kept) or tick questions and return them to the vendor for clarification.</div>';
  if (a.status === 'COMPLETED') {
    html += '<div class="banner" style="background:#eef6f2;border-color:#bfe0cc;color:#1f5a3c"><strong>Completed ' + day(a.completed_at) + '.</strong> Score ' + a.score_pct + '%, inherent ' + esc(nice(a.inherent_risk)) + ' &rarr; residual <strong>' + esc(nice(a.residual_risk)) + '</strong>. Conclusion: <strong>' + esc(nice(a.conclusion)) + '</strong>.' +
      (a.summary ? '<br>' + esc(a.summary) : '') + (a.findings.length ? '<br>' + a.findings.length + ' finding(s) raised: ' + a.findings.map(function (f) { return esc(f.title); }).join('; ') : '') + '</div>';
  } else if (a.vendor_archived) html += '<div class="banner">This vendor is archived, so the assessment is read-only.</div>';
  var canReturn = ps === 'SUBMITTED' && a.status !== 'COMPLETED' && !a.vendor_archived;
  var sec = null;
  a.questions.forEach(function (q) {
    if (q.section !== sec) { sec = q.section; html += '<div class="sechead">' + esc(sec) + '</div>'; }
    var dis = open ? '' : ' disabled';
    var vendorTag = '';
    if (q.source === 'VENDOR' && (q.answer || q.text_answer)) vendorTag = ' <span class="badge b-IN_REVIEW">Vendor-provided</span>';
    else if (q.source === 'ASSESSOR' && q.original_answer) vendorTag = ' <span class="badge b-DUE_SOON">Edited by assessor</span> <span style="font-size:11.5px;color:var(--muted)">Vendor said: <strong>' + esc(nice(q.original_answer)) + '</strong>' + (q.original_comment ? ' - ' + esc(q.original_comment) : '') + '</span>';
    if (q.returned) vendorTag += ' <span class="badge b-OVERDUE">Returned</span>' + (q.return_note ? ' <span style="font-size:11.5px;color:var(--muted)">' + esc(q.return_note) + '</span>' : '');
    var retCtl = canReturn ? '<div style="margin-top:8px;display:flex;gap:8px;align-items:center"><label style="font-size:12px;white-space:nowrap"><input type="checkbox" class="ret-chk" data-q="' + esc(q.id) + '"> Return to vendor</label><input type="text" class="ret-note" data-q="' + esc(q.id) + '" placeholder="What do you need from them?" maxlength="1000" style="flex:1"></div>' : '';
    html += '<div class="qblock' + (q.is_critical ? ' crit' : '') + '" id="qb-' + esc(q.id) + '"><div class="qtext">' + (q.position) + '. ' + esc(q.text) + (q.is_critical ? ' <span class="badge b-HIGH">Critical</span>' : '') + vendorTag + '</div>' +
      '<div class="qmeta">' + (q.guidance ? esc(q.guidance) + ' ' : '') + (q.maps_to ? '<em>Ref: ' + esc(q.maps_to) + '</em> ' : '') + (q.weight ? '(weight ' + q.weight + ')' : '(not scored)') + '</div>';
    if (q.answer_type === 'TEXT') {
      html += '<textarea id="qt-' + esc(q.id) + '" rows="2"' + dis + ' onblur="saveText(\'' + esc(q.id) + '\')">' + esc(q.text_answer || '') + '</textarea>';
    } else {
      var choices = q.answer_type === 'YES_NO_NA' ? ['YES', 'NO', 'NA'] : ['YES', 'NO'];
      html += '<div class="qans">' + choices.map(function (c) { return '<button class="opt ' + c + (q.answer === c ? ' on' : '') + '" ' + dis + ' onclick="setAnswer(\'' + esc(q.id) + '\',\'' + c + '\')">' + (c === 'NA' ? 'N/A' : nice(c)) + '</button>'; }).join('') + '</div>' +
        '<input type="text" id="qc-' + esc(q.id) + '" placeholder="Comment" value="' + esc(q.comment || '') + '"' + dis + ' onblur="saveMeta(\'' + esc(q.id) + '\')">' +
        '<input type="text" id="qe-' + esc(q.id) + '" placeholder="Evidence reference (document name, section, link)" value="' + esc(q.evidence_ref || '') + '"' + dis + ' onblur="saveMeta(\'' + esc(q.id) + '\')">';
    }
    var qev = (a.vendor_evidence || []).filter(function (e) { return e.question_id === q.id; });
    if (qev.length) html += '<div style="margin-top:8px;font-size:12px">' + qev.map(vendorFileRow).join('') + '</div>';
    html += retCtl + '</div>';
  });
  var gen = (a.vendor_evidence || []).filter(function (e) { return !e.question_id; });
  if (gen.length) html += '<div class="sechead">Vendor evidence (not tied to a question)</div><div style="font-size:12px">' + gen.map(vendorFileRow).join('') + '</div>';
  if ((a.vendor_evidence || []).length) html += '<div class="hint" style="margin-top:8px">Files uploaded by the vendor are checked for type and size but are <strong>not virus scanned</strong>. Open them with care.</div>';
  html += '<div class="modal-foot">';
  if (open || withVendor) html += '<button class="btn danger" onclick="cancelAssessmentUi()" style="margin-right:auto">Cancel assessment</button>';
  if (canReturn) html += '<button class="btn" onclick="returnUi()">Return ticked questions to vendor</button>';
  if (withVendor || (ps === 'SUBMITTED' && a.status !== 'COMPLETED')) html += '<button class="btn" onclick="withdrawUi()">Withdraw from vendor</button>';
  if (open && ps === 'NOT_SENT') html += '<button class="btn" onclick="sendVendorUi()">Send to vendor portal</button>';
  if (open) {
    html += (a.status === 'DRAFT' ? '<button class="btn" onclick="markSentUi()">Mark as sent (outside the portal)</button>' : '') +
      '<button class="btn primary" onclick="completeUi()">Complete assessment</button>';
  }
  html += '<button class="btn" onclick="closeAssessment()">Close</button></div>';
  var box = $('assess-box'), top = box.scrollTop;
  box.innerHTML = html; box.scrollTop = top;
}
function vendorFileRow(f) {
  var kb = f.file_size ? Math.max(1, Math.round(f.file_size / 1024)) + ' KB' : '';
  return '<div style="padding:2px 0">&#128206; <strong>' + esc(f.file_name) + '</strong> <span style="color:var(--muted)">' + esc(nice(f.doc_type)) + (f.valid_until ? ', valid until ' + day(f.valid_until) : '') + (kb ? ', ' + kb : '') + ', by ' + esc(f.uploaded_by_name || '-') + '</span> ' +
    '<button class="btn sm" onclick="downloadDoc(\'' + esc(f.id) + '\')">Download</button></div>';
}
function sendVendorUi() {
  openForm({
    title: 'Send to vendor portal', submit: 'Send',
    intro: 'The questionnaire becomes visible to the portal users of <strong>' + esc(S.assess.vendor_name) + '</strong>. You will not be able to edit the answers until they submit or you withdraw it.',
    fields: [{ key: 'due_date', label: 'Respond by', type: 'date', value: S.assess.due_date ? S.assess.due_date.substring(0, 10) : '' }],
    onSubmit: function (v) { return call('send_to_vendor', { id: S.assess.id, due_date: v.due_date }).then(function () { toast('Sent to the vendor'); openAssessment(S.assess.id); }); }
  });
}
function withdrawUi() {
  if (!confirm('Withdraw this questionnaire from the vendor? It disappears from their portal. Answers they gave are kept.')) return;
  call('withdraw_from_vendor', { id: S.assess.id }).then(function () { toast('Withdrawn'); openAssessment(S.assess.id); }).catch(fail);
}
function returnUi() {
  var items = [];
  Array.prototype.forEach.call(document.querySelectorAll('.ret-chk'), function (c) {
    if (c.checked) { var n = document.querySelector('.ret-note[data-q="' + c.getAttribute('data-q') + '"]'); items.push({ question_id: c.getAttribute('data-q'), note: n ? n.value.trim() : '' }); }
  });
  if (!items.length) { alert('Tick the questions you want to return, and say what you need.'); return; }
  if (items.some(function (i) { return !i.note; }) && !confirm('Some returned questions have no note. Return them anyway?')) return;
  call('return_questions', { id: S.assess.id, items: items }).then(function (d) { toast('Returned ' + d.returned + ' question(s) to the vendor'); openAssessment(S.assess.id); }).catch(fail);
}
function findQ(id) { return S.assess.questions.filter(function (q) { return q.id === id; })[0]; }
function persistAnswer(q) {
  return call('save_answer', {
    assessment_id: S.assess.id, question_id: q.id, answer: q.answer || '', text_answer: q.text_answer || '', comment: q.comment || '', evidence_ref: q.evidence_ref || ''
  }).then(function (d) {
    if (S.assess.status === 'DRAFT' || S.assess.status === 'SENT') S.assess.status = 'IN_REVIEW';
    S.assess.progress = d.progress; updateProgress();
  });
}
function updateProgress() {
  var pr = S.assess.progress, scored = pr.scored_questions, done = scored - pr.unanswered;
  $('as-prog-txt').textContent = done + ' of ' + scored + ' scored questions answered';
  $('as-score').textContent = done ? pr.score_pct + '%' : '-'; $('as-bar').style.width = (scored ? Math.round(100 * done / scored) : 0) + '%';
}
function setAnswer(qid, ans) {
  var q = findQ(qid); q.comment = $('qc-' + qid).value.trim(); q.evidence_ref = $('qe-' + qid).value.trim();
  var prev = q.answer; q.answer = (q.answer === ans) ? null : ans;
  persistAnswer(q).then(function () {
    Array.prototype.forEach.call(document.querySelectorAll('#qb-' + qid + ' .opt'), function (b) { b.className = 'opt ' + b.textContent.toUpperCase().replace('/', '') + (q.answer && b.textContent.toUpperCase().replace('/', '') === q.answer ? ' on' : ''); });
  }).catch(function (e) { q.answer = prev; fail(e); });
}
function saveMeta(qid) { var q = findQ(qid); var c = $('qc-' + qid).value.trim(), e = $('qe-' + qid).value.trim(); if (c === (q.comment || '') && e === (q.evidence_ref || '')) return; q.comment = c; q.evidence_ref = e; persistAnswer(q).catch(fail); }
function saveText(qid) { var q = findQ(qid); var t = $('qt-' + qid).value.trim(); if (t === (q.text_answer || '')) return; q.text_answer = t; persistAnswer(q).catch(fail); }
function markSentUi() { call('mark_assessment_sent', { id: S.assess.id }).then(function () { toast('Marked as sent'); openAssessment(S.assess.id); }).catch(fail); }
function cancelAssessmentUi() {
  if (!confirm('Cancel this assessment? Answers are kept but it can no longer be completed.')) return;
  call('cancel_assessment', { id: S.assess.id }).then(function () { toast('Assessment cancelled'); openAssessment(S.assess.id); }).catch(fail);
}
function completeUi() {
  var pr = S.assess.progress;
  if (pr.unanswered > 0) { alert(pr.unanswered + ' scored question(s) are still unanswered. Answer them (use N/A where it does not apply) before completing.'); return; }
  openForm({
    title: 'Complete assessment', submit: 'Complete',
    intro: 'Score <strong>' + pr.score_pct + '%</strong>. Completing locks the answers, sets the vendor\'s residual risk and next review date, and raises a finding for every <em>No</em> on a critical question.',
    fields: [
      { key: 'conclusion', label: 'Conclusion', type: 'select', required: true, optionsHtml: opts([['APPROVED', 'Approved'], ['APPROVED_WITH_CONDITIONS', 'Approved with conditions'], ['REJECTED', 'Rejected']], 'APPROVED') },
      { key: 'summary', label: 'Summary', type: 'textarea', full: true, placeholder: 'Key points, conditions, follow-ups' }
    ],
    onSubmit: function (vals) {
      return call('complete_assessment', { id: S.assess.id, conclusion: vals.conclusion, summary: vals.summary }).then(function (d) {
        toast('Completed: residual ' + nice(d.residual_risk) + ', ' + d.findings_raised + ' finding(s) raised, next review ' + d.next_review_due);
        openAssessment(S.assess.id);
      });
    }
  });
}

// ---------- findings ----------
var FINDING_ACTIONS = {
  OPEN: [['IN_PROGRESS', 'Start work'], ['CLOSED', 'Close']],
  IN_PROGRESS: [['REMEDIATED', 'Mark remediated'], ['OPEN', 'Back to open'], ['CLOSED', 'Close']],
  REMEDIATED: [['CLOSED', 'Close'], ['IN_PROGRESS', 'Reopen work']],
  ACCEPTED: [['CLOSED', 'Close'], ['OPEN', 'Revoke acceptance']],
  CLOSED: [['OPEN', 'Reopen']]
};
function findingsTable(list, showVendor) {
  if (!list.length) return '<div class="empty">No findings.</div>';
  return '<table><thead><tr>' + (showVendor ? '<th>Vendor</th>' : '') + '<th>Finding</th><th>Severity</th><th>Status</th><th>Owner</th><th>Due</th><th>Risk</th><th></th></tr></thead><tbody>' +
    list.map(function (f) {
      var ro = f.vendor_archived || (S.vendor && S.vendor.archived_at && !showVendor);
      var acts = '';
      if (!ro) {
        acts = (FINDING_ACTIONS[f.status] || []).map(function (a) { return '<button class="btn sm" onclick="findingStatus(\'' + esc(f.id) + '\',\'' + a[0] + '\')">' + a[1] + '</button>'; }).join('');
        if (f.status === 'OPEN' || f.status === 'IN_PROGRESS') acts += '<button class="btn sm" onclick="acceptFindingUi(\'' + esc(f.id) + '\')">Accept risk</button>';
        if (!f.linked_risk_id) acts += '<button class="btn sm" onclick="escalateUi(\'' + esc(f.id) + '\')">Escalate to Risk Register</button>';
        acts += '<button class="btn sm" onclick="editFinding(\'' + esc(f.id) + '\')">Edit</button>';
      }
      return '<tr>' + (showVendor ? '<td><strong>' + esc(f.vendor_name) + '</strong>' + (f.vendor_archived ? ' <span class="badge b-TERMINATED">Archived</span>' : '') + '</td>' : '') +
        '<td><strong>' + esc(f.title) + '</strong>' + (f.source === 'ASSESSMENT' ? ' <span class="chip">from assessment</span>' : '') +
        (f.description ? '<div style="font-size:11.5px;color:var(--muted);max-width:340px;white-space:pre-line">' + esc(f.description) + '</div>' : '') +
        (f.remediation_plan ? '<div style="font-size:11.5px;margin-top:3px"><em>Plan:</em> ' + esc(f.remediation_plan) + '</div>' : '') +
        (f.status === 'ACCEPTED' ? '<div style="font-size:11.5px;margin-top:3px"><em>Accepted by ' + esc(f.accepted_by_name || '-') + ':</em> ' + esc(f.acceptance_reason) + '</div>' : '') + '</td>' +
        '<td>' + badge(f.severity) + '</td><td>' + badge(f.status) + '</td><td>' + esc(f.owner_name || '-') + '</td>' +
        '<td class="' + (f.overdue ? 'cal-OVERDUE' : '') + '">' + day(f.due_date) + (f.overdue ? ' (overdue)' : '') + '</td>' +
        '<td>' + (f.risk_code ? '<a href="risks-register.html" style="color:var(--teal);font-weight:700">' + esc(f.risk_code) + '</a>' : '-') + '</td><td><div class="list-actions">' + acts + '</div></td></tr>';
    }).join('') + '</tbody></table>';
}
function tabFindings() {
  call('list_findings', { vendor_id: S.vendor.id }).then(function (d) {
    S.findings = d.findings;
    $('vtab-body').innerHTML = (S.vendor.archived_at ? '' : '<div style="margin-bottom:12px"><button class="btn primary" onclick="addFinding()">+ Add finding</button></div>') +
      '<div class="table-scroll">' + findingsTable(d.findings, false) + '</div>';
  }).catch(fail);
}
function loadFindings() {
  var p = $('pane-findings');
  if (!p.dataset.ready) {
    p.dataset.ready = '1';
    p.innerHTML = '<div class="section-card"><div class="filter-bar">' +
      '<div><label>Status</label><select id="ff-st" onchange="loadFindings()" style="min-width:150px">' + opts([['OPEN', 'Open'], ['IN_PROGRESS', 'In progress'], ['REMEDIATED', 'Remediated'], ['ACCEPTED', 'Accepted'], ['CLOSED', 'Closed']], '', 'All') + '</select></div>' +
      '<div><label>Severity</label><select id="ff-sev" onchange="loadFindings()" style="min-width:140px">' + opts(TIERS, '', 'All') + '</select></div>' +
      '<div><label class="chk" style="display:flex;gap:6px;align-items:center;text-transform:none;letter-spacing:0"><input type="checkbox" id="ff-open" onchange="loadFindings()"> Open and in progress only</label></div>' +
      '<div><label class="chk" style="display:flex;gap:6px;align-items:center;text-transform:none;letter-spacing:0"><input type="checkbox" id="ff-over" onchange="loadFindings()"> Overdue only</label></div></div>' +
      '<div class="table-scroll" id="find-list"></div></div>';
  }
  var body = { open_only: $('ff-open').checked, overdue_only: $('ff-over').checked };
  if ($('ff-st').value) body.status = $('ff-st').value;
  if ($('ff-sev').value) body.severity = $('ff-sev').value;
  call('list_findings', body).then(function (d) { S.findings = d.findings; $('find-list').innerHTML = findingsTable(d.findings, true); }).catch(function (e) { $('find-list').innerHTML = '<div class="empty">' + esc(e.message) + '</div>'; });
}
function afterFinding() { if (S.tab === 'findings') loadFindings(); if (S.vendor && $('drawer-bg').style.display === 'flex' && S.vendorTab === 'findings') tabFindings(); if (S.tab === 'overview') loadOverview(); }
function findingFields(f) {
  f = f || {};
  return [
    { key: 'title', label: 'Title', required: true, value: f.title, full: true },
    { key: 'severity', label: 'Severity', type: 'select', optionsHtml: opts(TIERS, f.severity || 'MEDIUM') },
    { key: 'due_date', label: 'Due date', type: 'date', value: f.due_date ? f.due_date.substring(0, 10) : '' },
    { key: 'owner_id', label: 'Owner', type: 'select', optionsHtml: ownerOptions(f.owner_id) },
    { key: 'description', label: 'Description', type: 'textarea', value: f.description, full: true },
    { key: 'remediation_plan', label: 'Remediation plan', type: 'textarea', value: f.remediation_plan, full: true }
  ];
}
function addFinding() {
  ensureOwners().then(function () {
    openForm({
      title: 'Add finding: ' + S.vendor.name, submit: 'Add', fields: findingFields(),
      onSubmit: function (v) { v.vendor_id = S.vendor.id; return call('add_finding', v).then(function () { toast('Finding added'); afterFinding(); }); }
    });
  }).catch(fail);
}
function editFinding(id) {
  var f = (S.findings || []).filter(function (x) { return x.id === id; })[0];
  ensureOwners().then(function () {
    openForm({ title: 'Edit finding', submit: 'Save', fields: findingFields(f), onSubmit: function (v) { v.id = id; return call('update_finding', v).then(function () { toast('Saved'); afterFinding(); }); } });
  }).catch(fail);
}
function findingStatus(id, st) { call('update_finding', { id: id, status: st }).then(function () { toast('Status: ' + nice(st)); afterFinding(); }).catch(fail); }
function acceptFindingUi(id) {
  openForm({
    title: 'Accept risk', submit: 'Accept', intro: 'Accepting means the business knowingly lives with this finding. Your name and the reason are recorded.',
    fields: [{ key: 'reason', label: 'Reason for acceptance', type: 'textarea', required: true, full: true }],
    onSubmit: function (v) { return call('accept_finding', { id: id, reason: v.reason }).then(function () { toast('Finding accepted'); afterFinding(); }); }
  });
}
function escalateUi(id) {
  if (!confirm('Create a Risk Register entry from this finding? This can be done once per finding.')) return;
  call('escalate_finding_to_risk', { id: id }).then(function (d) { toast('Escalated as ' + d.risk_code); afterFinding(); }).catch(fail);
}

// ---------- contracts & documents ----------
function tabContracts() {
  var ro = !!S.vendor.archived_at;
  Promise.all([call('list_contracts', { vendor_id: S.vendor.id }), call('list_documents', { vendor_id: S.vendor.id })]).then(function (r) {
    S.contracts = r[0].contracts; var docs = r[1].documents;
    var ct = S.contracts.length ? '<table><thead><tr><th>Contract</th><th>Term</th><th>Renewal decision by</th><th>Clauses</th><th></th></tr></thead><tbody>' + S.contracts.map(function (c) {
      var cl = [['has_security_clauses', 'Security'], ['has_breach_notification_clause', 'Breach notice'], ['has_audit_right', 'Audit right'], ['has_data_return_deletion_clause', 'Data return/deletion']].map(function (x) { return '<span class="chip" style="' + (c[x[0]] ? 'background:#e6f4ec' : 'opacity:.5;text-decoration:line-through') + '">' + x[1] + '</span>'; }).join('');
      return '<tr><td><strong>' + esc(c.title) + '</strong><div style="font-size:11.5px;color:var(--muted)">' + esc(c.contract_type) + (c.value_inr != null ? ' &middot; INR ' + Number(c.value_inr).toLocaleString('en-IN') : '') + (c.auto_renews ? ' &middot; auto-renews' : '') + '</div></td>' +
        '<td>' + day(c.start_date) + ' to ' + day(c.end_date) + (c.expired ? ' <span class="badge b-OVERDUE">Expired</span>' : '') + '</td><td>' + day(c.renewal_due) + (c.renewal_soon ? ' <span class="badge b-DUE_SOON">Soon</span>' : '') + '</td><td>' + cl + '</td>' +
        '<td><div class="list-actions">' + (ro ? '' : '<button class="btn sm" onclick="editContract(\'' + esc(c.id) + '\')">Edit</button>' + (IS_ADMIN ? '<button class="btn sm danger" onclick="delContract(\'' + esc(c.id) + '\')">Delete</button>' : '')) + '</div></td></tr>';
    }).join('') + '</tbody></table>' : '<div class="empty">No contracts recorded. Personal-data vendors need a DPA (DPDP Sec 8(2)).</div>';
    var dt = docs.length ? '<table><thead><tr><th>Document</th><th>Type</th><th>Valid until</th><th>Uploaded</th><th>SHA-256</th><th></th></tr></thead><tbody>' + docs.map(function (d) {
      return '<tr><td><strong>' + esc(d.file_name) + '</strong>' + (d.uploaded_by_vendor ? '<div style="font-size:11px;color:var(--amber)">Vendor-uploaded, not virus scanned</div>' : '') + '</td><td>' + esc(nice(d.doc_type)) + '</td><td>' + day(d.valid_until) + (d.expired ? ' <span class="badge b-OVERDUE">Expired</span>' : d.expiring_soon ? ' <span class="badge b-DUE_SOON">Expiring</span>' : '') + '</td>' +
        '<td>' + day(d.uploaded_at) + ' by ' + esc(d.uploaded_by_name || '-') + '</td><td><code style="font-size:10.5px">' + esc((d.sha256_checksum || '').substring(0, 12)) + '…</code></td>' +
        '<td><div class="list-actions"><button class="btn sm" onclick="downloadDoc(\'' + esc(d.id) + '\')">Download</button>' + (!ro && IS_ADMIN ? '<button class="btn sm danger" onclick="delDoc(\'' + esc(d.id) + '\')">Delete</button>' : '') + '</div></td></tr>';
    }).join('') + '</tbody></table>' : '<div class="empty">No documents. Upload certificates and SOC reports with their expiry dates so renewals surface here.</div>';
    $('vtab-body').innerHTML =
      '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px"><strong>Contracts</strong>' + (ro ? '' : '<button class="btn primary" onclick="addContract()">+ Add contract</button>') + '</div><div class="table-scroll">' + ct + '</div>' +
      '<div style="display:flex;justify-content:space-between;align-items:center;margin:22px 0 8px"><strong>Documents and certificates</strong>' + (ro ? '' : '<button class="btn primary" onclick="uploadDoc()">+ Upload document</button>') + '</div><div class="table-scroll">' + dt + '</div>';
  }).catch(fail);
}
function contractFields(c) {
  c = c || {};
  return [
    { key: 'title', label: 'Title', required: true, value: c.title, full: true },
    { key: 'contract_type', label: 'Type', type: 'select', optionsHtml: opts([['MSA', 'MSA'], ['SOW', 'SOW'], ['DPA', 'DPA (data processing agreement)'], ['NDA', 'NDA'], ['SLA', 'SLA'], ['OTHER', 'Other']], c.contract_type || 'MSA') },
    { key: 'notice_period_days', label: 'Notice period (days)', type: 'number', value: c.notice_period_days == null ? 0 : c.notice_period_days },
    { key: 'start_date', label: 'Start date', type: 'date', value: c.start_date ? c.start_date.substring(0, 10) : '' },
    { key: 'end_date', label: 'End date', type: 'date', value: c.end_date ? c.end_date.substring(0, 10) : '' },
    { key: 'value_inr', label: 'Value (INR)', type: 'number', value: c.value_inr },
    { key: 'auto_renews', label: 'Renews automatically', type: 'checkbox', value: c.auto_renews },
    { key: 'has_security_clauses', label: 'Has security clauses', type: 'checkbox', value: c.has_security_clauses },
    { key: 'has_breach_notification_clause', label: 'Has breach notification clause', type: 'checkbox', value: c.has_breach_notification_clause },
    { key: 'has_audit_right', label: 'Gives us an audit right', type: 'checkbox', value: c.has_audit_right },
    { key: 'has_data_return_deletion_clause', label: 'Has data return / deletion clause', type: 'checkbox', value: c.has_data_return_deletion_clause },
    { key: 'notes', label: 'Notes', type: 'textarea', value: c.notes, full: true }
  ];
}
function contractPayload(v) { var o = Object.assign({}, v); if (o.notice_period_days == null) o.notice_period_days = 0; if (o.value_inr == null) o.value_inr = ''; return o; }
function addContract() {
  openForm({ title: 'Add contract', submit: 'Add', three: false, fields: contractFields(), onSubmit: function (v) { return call('add_contract', Object.assign({ vendor_id: S.vendor.id }, contractPayload(v))).then(function () { toast('Contract added'); openVendor(S.vendor.id, 'contracts'); refreshAfterVendorChange(); }); } });
}
function editContract(id) {
  var c = S.contracts.filter(function (x) { return x.id === id; })[0];
  openForm({ title: 'Edit contract', submit: 'Save', fields: contractFields(c), onSubmit: function (v) { return call('update_contract', Object.assign({ id: id }, contractPayload(v))).then(function () { toast('Saved'); openVendor(S.vendor.id, 'contracts'); refreshAfterVendorChange(); }); } });
}
function delContract(id) { if (!confirm('Delete this contract?')) return; call('delete_contract', { id: id }).then(function () { toast('Deleted'); openVendor(S.vendor.id, 'contracts'); refreshAfterVendorChange(); }).catch(fail); }
function uploadDoc() {
  openForm({
    title: 'Upload document', submit: 'Upload', intro: 'PDF, Office, image, zip, txt or csv, up to 10 MB. Set the expiry date for certificates and reports so renewals are flagged.',
    fields: [
      { key: 'file', label: 'File', type: 'file', required: true, full: true },
      { key: 'doc_type', label: 'Type', type: 'select', optionsHtml: opts(DOC_TYPES, 'CERTIFICATE') },
      { key: 'valid_until', label: 'Valid until', type: 'date' }
    ],
    onSubmit: function (v) { return call('upload_document', { vendor_id: S.vendor.id, doc_type: v.doc_type, valid_until: v.valid_until, file_name: v.file.name, file_data: v.file.data }).then(function () { toast('Uploaded'); openVendor(S.vendor.id, 'contracts'); refreshAfterVendorChange(); }); }
  });
}
function downloadDoc(id) {
  call('get_document', { id: id }).then(function (d) {
    if (!d.checksum_ok) alert('Warning: the stored file no longer matches its recorded SHA-256 checksum. It may have been altered.');
    var bytes = atob(d.file_data), arr = new Uint8Array(bytes.length);
    for (var i = 0; i < bytes.length; i++) arr[i] = bytes.charCodeAt(i);
    var url = URL.createObjectURL(new Blob([arr])), a = document.createElement('a');
    a.href = url; a.download = d.file_name; document.body.appendChild(a); a.click(); document.body.removeChild(a); URL.revokeObjectURL(url);
  }).catch(fail);
}
function delDoc(id) { if (!confirm('Delete this document and its file?')) return; call('delete_document', { id: id }).then(function () { toast('Deleted'); openVendor(S.vendor.id, 'contracts'); refreshAfterVendorChange(); }).catch(fail); }

// ---------- linked data / lifecycle ----------
function tabLinked() {
  var v = S.vendor, a = v.linked_assets_list || [], r = v.linked_risks || [];
  $('vtab-body').innerHTML = '<p class="sub" style="margin-top:0">Data assets in the Data Governance register that name this vendor as a recipient or linked vendor. These feed the personal-data and sensitive-data signals in the risk tier.</p>' +
    (a.length ? '<table><thead><tr><th>Data asset</th><th>Category</th><th>Sensitivity</th><th>Relation</th><th>Status</th></tr></thead><tbody>' + a.map(function (x) {
      return '<tr><td><a href="data-governance-register.html" style="color:var(--teal);font-weight:700">' + esc(x.name) + '</a></td><td><span class="tag">' + esc(x.category) + '</span></td><td>' + esc(x.sensitivity) + '</td><td>' + esc(x.relation) + '</td><td>' + esc(nice(x.discovery_status)) + '</td></tr>';
    }).join('') + '</tbody></table>' : '<div class="empty">No data assets are linked to this vendor.</div>') +
    '<div style="margin-top:20px"><strong>Linked risks</strong></div>' + (r.length ? '<ul>' + r.map(function (x) { return '<li><a href="risks-register.html" style="color:var(--teal);font-weight:700">' + esc(x.risk_code) + '</a> ' + esc(x.title) + ' ' + badge(x.status) + '</li>'; }).join('') + '</ul>' : '<div class="hint">None. Findings escalated to the Risk Register appear here.</div>');
}
function tabLifecycle() {
  var v = S.vendor, ro = !!v.archived_at, ck = v.offboarding || {};
  var html = '<div class="kv"><div><label>Current status</label><span>' + badge(v.lifecycle_status) + '</span></div><div><label>Open findings</label><span>' + (v.open_findings || 0) + '</span></div><div><label>Linked data assets</label><span>' + (v.linked_assets || 0) + '</span></div></div>';
  if (!ro) html += '<div class="list-actions" style="margin-bottom:16px">' + LIFECYCLE.filter(function (l) { return l[0] !== v.lifecycle_status; }).map(function (l) { return '<button class="btn" onclick="changeLifecycle(\'' + l[0] + '\')">Move to ' + l[1] + '</button>'; }).join('') + '</div>';
  if (v.lifecycle_status === 'OFFBOARDING') {
    html += '<div class="dsec"><h4>Offboarding checklist</h4>' + OFFBOARDING_ITEMS.map(function (i) {
      return '<label style="display:flex;gap:8px;align-items:center;padding:6px 0;font-size:13px"><input type="checkbox" ' + (ck[i[0]] ? 'checked ' : '') + (ro ? 'disabled ' : '') + 'onchange="tickItem(\'' + i[0] + '\',this.checked)"> ' + esc(i[1]) + '</label>';
    }).join('') + '<div class="hint">Moving to Terminated needs every item ticked, or an override reason.' + ((v.open_findings || 0) > 0 ? ' This vendor still has ' + v.open_findings + ' open finding(s).' : '') + ((v.linked_assets || 0) > 0 ? ' It is still linked to ' + v.linked_assets + ' data asset(s) in the register.' : '') + '</div></div>';
  } else if (v.lifecycle_status === 'TERMINATED' && v.offboarding_override_reason) {
    html += '<div class="banner">Terminated with an override: ' + esc(v.offboarding_override_reason) + '</div>';
  } else html += '<div class="hint">Moving a vendor to Offboarding shows a checklist (access, data return, assets, findings, final invoice).</div>';
  $('vtab-body').innerHTML = html;
}
function changeLifecycle(target) {
  if (target === 'TERMINATED') {
    openForm({
      title: 'Terminate vendor', submit: 'Terminate',
      intro: 'Every offboarding item must be ticked. If some cannot be completed, give a reason and it is recorded.',
      fields: [{ key: 'override_reason', label: 'Override reason (only needed if the checklist is incomplete)', type: 'textarea', full: true }],
      onSubmit: function (v) { return call('set_vendor_status', { id: S.vendor.id, lifecycle_status: target, override_reason: v.override_reason }).then(function () { toast('Vendor terminated'); openVendor(S.vendor.id, 'lifecycle'); refreshAfterVendorChange(); }); }
    });
    return;
  }
  call('set_vendor_status', { id: S.vendor.id, lifecycle_status: target }).then(function () { toast('Status: ' + nice(target)); openVendor(S.vendor.id, 'lifecycle'); refreshAfterVendorChange(); }).catch(fail);
}
function tickItem(item, done) {
  call('update_offboarding_item', { id: S.vendor.id, item: item, done: done }).then(function () { S.vendor.offboarding[item] = done; }).catch(function (e) { fail(e); openVendor(S.vendor.id, 'lifecycle'); });
}

// ---------- questionnaires ----------
function loadQuestionnaires() {
  call('list_questionnaires').then(function (d) {
    $('pane-questionnaires').innerHTML = (IS_ADMIN ? '<div style="margin-bottom:12px"><button class="btn primary" onclick="newQuestionnaire()">+ New questionnaire</button></div>' : '<div class="banner" style="background:#eef6ff;border-color:#c9dcf5;color:#2b4a7a">Only administrators can edit questionnaires. You can view them here.</div>') +
      '<div class="section-card"><div class="table-scroll"><table><thead><tr><th>Questionnaire</th><th>Version</th><th>Active</th><th>Questions</th><th>Completed assessments</th><th></th></tr></thead><tbody>' +
      (d.questionnaires || []).map(function (q) {
        return '<tr><td><strong>' + esc(q.name) + '</strong><div style="font-size:11.5px;color:var(--muted);max-width:420px">' + esc(q.description || '') + '</div></td><td>' + esc(q.version) + '</td><td>' + (q.is_active ? badge('ACTIVE', 'Active') : badge('DRAFT', 'Inactive')) + '</td><td>' + q.question_count + '</td><td>' + q.completed_assessments + '</td>' +
          '<td><div class="list-actions"><button class="btn sm primary" onclick="viewQuestionnaire(\'' + esc(q.id) + '\')">' + (IS_ADMIN ? 'Open' : 'View') + '</button>' + (IS_ADMIN ? '<button class="btn sm" onclick="cloneQ(\'' + esc(q.id) + '\')">Clone as new version</button>' : '') + '</div></td></tr>';
      }).join('') + '</tbody></table></div></div><div id="q-detail"></div>';
  }).catch(function (e) { $('pane-questionnaires').innerHTML = '<div class="empty">' + esc(e.message) + '</div>'; });
}
function newQuestionnaire() {
  openForm({
    title: 'New questionnaire', submit: 'Create',
    fields: [{ key: 'name', label: 'Name', required: true, full: true }, { key: 'version', label: 'Version', value: '1.0' }, { key: 'description', label: 'Description', type: 'textarea', full: true }],
    onSubmit: function (v) { return call('save_questionnaire', v).then(function (d) { toast('Created'); loadQuestionnaires(); viewQuestionnaire(d.id); }); }
  });
}
function cloneQ(id) {
  openForm({
    title: 'Clone questionnaire', submit: 'Clone', intro: 'Used questionnaires are locked so past assessments stay accurate. Clone to a new version to change questions.',
    fields: [{ key: 'version', label: 'New version', required: true, placeholder: 'e.g. 2.0' }],
    onSubmit: function (v) { return call('clone_questionnaire', { id: id, version: v.version }).then(function (d) { toast('Cloned'); loadQuestionnaires(); viewQuestionnaire(d.id); }); }
  });
}
function viewQuestionnaire(id) {
  call('get_questionnaire', { id: id }).then(function (d) {
    var q = d.questionnaire, locked = q.completed_assessments > 0, sec = null;
    var html = '<div class="section-card" style="padding:18px 20px"><div style="display:flex;justify-content:space-between;gap:10px;align-items:center"><div><h3 style="margin:0">' + esc(q.name) + ' v' + esc(q.version) + '</h3><div class="hint">' + q.questions.length + ' questions</div></div><div class="list-actions">' +
      (IS_ADMIN ? '<button class="btn" onclick="toggleActive(\'' + esc(q.id) + '\',' + (q.is_active ? 'false' : 'true') + ')">' + (q.is_active ? 'Deactivate' : 'Activate') + '</button>' + (locked ? '' : '<button class="btn primary" onclick="editQuestion(\'' + esc(q.id) + '\')">+ Add question</button>') : '') + '</div></div>' +
      (locked && IS_ADMIN ? '<div class="banner" style="margin-top:12px">This questionnaire has completed assessments, so its questions are locked. Clone it to change them.</div>' : '');
    q.questions.forEach(function (x) {
      if (x.section !== sec) { sec = x.section; html += '<div class="sechead">' + esc(sec) + '</div>'; }
      html += '<div class="qblock' + (x.is_critical ? ' crit' : '') + '"><div class="qtext">' + x.position + '. ' + esc(x.text) + (x.is_critical ? ' <span class="badge b-HIGH">Critical</span>' : '') + '</div><div class="qmeta">' + esc(x.answer_type.replace(/_/g, '/')) + ' &middot; weight ' + x.weight + (x.maps_to ? ' &middot; ' + esc(x.maps_to) : '') + (x.guidance ? ' &middot; ' + esc(x.guidance) : '') + '</div>' +
        (IS_ADMIN && !locked ? '<div class="list-actions" style="margin-top:8px"><button class="btn sm" onclick="editQuestion(\'' + esc(q.id) + '\',\'' + esc(x.id) + '\')">Edit</button><button class="btn sm danger" onclick="delQuestion(\'' + esc(q.id) + '\',\'' + esc(x.id) + '\')">Delete</button></div>' : '') + '</div>';
    });
    S.q = q;
    $('q-detail').innerHTML = html + '</div>'; $('q-detail').scrollIntoView({ behavior: 'smooth' });
  }).catch(fail);
}
function toggleActive(id, on) { call('save_questionnaire', { id: id, is_active: on }).then(function () { toast(on ? 'Activated' : 'Deactivated'); loadQuestionnaires(); }).catch(fail); }
function editQuestion(qid, id) {
  var x = id ? S.q.questions.filter(function (y) { return y.id === id; })[0] : {};
  openForm({
    title: id ? 'Edit question' : 'Add question', submit: 'Save',
    fields: [
      { key: 'section', label: 'Section', required: true, value: x.section || (S.q.questions.length ? S.q.questions[S.q.questions.length - 1].section : ''), full: true },
      { key: 'text', label: 'Question (phrase it so Yes is the good answer)', type: 'textarea', required: true, value: x.text, full: true },
      { key: 'guidance', label: 'Guidance for the assessor', type: 'textarea', value: x.guidance, full: true },
      { key: 'answer_type', label: 'Answer type', type: 'select', optionsHtml: opts([['YES_NO', 'Yes / No'], ['YES_NO_NA', 'Yes / No / N/A'], ['TEXT', 'Free text (not scored)']], x.answer_type || 'YES_NO') },
      { key: 'weight', label: 'Weight (0-10)', type: 'number', value: x.weight == null ? 1 : x.weight },
      { key: 'maps_to', label: 'Reference', value: x.maps_to, placeholder: 'e.g. DPDP Sec 8(2), ISO 27001 A.5.19' },
      { key: 'is_critical', label: 'Critical: a "No" raises a finding', type: 'checkbox', value: x.is_critical }
    ],
    onSubmit: function (v) {
      var body = Object.assign({}, v); if (id) body.id = id; else body.questionnaire_id = qid; if (body.weight == null) body.weight = 1;
      return call('save_question', body).then(function () { toast('Saved'); viewQuestionnaire(qid); loadQuestionnairesQuiet(); });
    }
  });
}
function loadQuestionnairesQuiet() { /* counts refresh when the list is next opened */ }
function delQuestion(qid, id) { if (!confirm('Delete this question?')) return; call('delete_question', { id: id }).then(function () { toast('Deleted'); viewQuestionnaire(qid); }).catch(fail); }

// ---------- init ----------
(function () {
  var n = localStorage.getItem('username') || 'Demo Administrator', r = localStorage.getItem('role') || 'Admin';
  var av = $('user-avatar'), nm = $('user-name'), ro = $('user-role');
  if (nm) nm.textContent = n; if (ro) ro.textContent = r;
  if (av) av.textContent = n.trim().split(/\s+/).map(function (w) { return w[0]; }).join('').slice(0, 2).toUpperCase() || 'U';
  showTab('overview');
})();
