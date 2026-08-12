// Role-based access control — runs on every authenticated page.
// Reads the permissions map cached in localStorage at login time,
// hides nav links for modules the user cannot access, and
// redirects away from any page the user has no permission to visit.
(function () {
  var permsRaw = localStorage.getItem('permissions');
  if (!permsRaw) return; // permissions not yet loaded (e.g. first load race); API will 403 anyway

  var perms = {};
  try { perms = JSON.parse(permsRaw); } catch (e) { return; }

  // Maps every page filename to its GRC module key in role_permissions
  var PAGE_MODULE = {
    'platform.html':              'platform',
    'platform-users.html':        'platform',
    'platform-roles.html':        'platform',
    'platform-audit.html':        'platform',
    'platform-apikeys.html':      'platform',
    'platform-ticket-categories.html': 'platform',
    'platform-business-settings.html': 'platform',
    'governance.html':            'governance',
    'governance-policies.html':   'governance',
    'governance-committees.html': 'governance',
    'governance-attestations.html': 'governance',
    'risks.html':                 'risks',
    'risks-register.html':        'risks',
    'risks-vapt.html':            'risks',
    'controls.html':              'controls',
    'controls-register.html':     'controls',
    'controls-frameworks.html':   'controls',
    'controls-exceptions.html':   'controls',
    'data-register.html':         'data',
    'supplychain.html':           'supplychain',
    'supplychain-sbom.html':      'supplychain',
    'supplychain-cbom.html':      'supplychain',
    'evidence.html':              'evidence',
    'evidence-audits.html':       'evidence',
    'evidence-findings.html':     'evidence',
    'evidence-locker.html':       'evidence',
    'operations.html':            'operations',
    'operations-assets.html':     'operations',
    'operations-changes.html':    'operations',
    'operations-vendors.html':    'operations',
    'operations-helpdesk.html':   'helpdesk',
    'incidents.html':             'incidents',
    'incidents-register.html':    'incidents',
    'incidents-knowledge.html':   'incidents',
    'incidents-training.html':    'incidents',
    'reports.html':               'reports'
  };

  // Guard current page — redirect before any page script fires
  var currentPage = window.location.pathname.split('/').pop().split('?')[0];
  var currentModule = PAGE_MODULE[currentPage];
  if (currentModule && perms[currentModule] === 'NONE') {
    window.location.replace('dashboard.html');
    return;
  }

  // Hide nav links (and their subnav siblings) for NONE-access modules
  var links = document.querySelectorAll('.nav a[href]');
  links.forEach(function (link) {
    var href = link.getAttribute('href').split('?')[0];
    var mod = PAGE_MODULE[href];
    if (mod && perms[mod] === 'NONE') {
      link.style.display = 'none';
    }
  });
}());
