# Functional Specification: TSI Compass (IT GRC & Operations Platform)
Prepared by: TSI Consulting LLP

Target Audience: Cross-industry Small and Medium Enterprises (SMEs) and Mid-Market Organizations

---

## 1. Core Platform & Access Control

### 1.1 Organization Structure Configuration
- **Office Hierarchy**: Ability to map multi-tiered organizational structures (Head Office, Regional Offices, Branches, Departments).
- **Designations & KRAs**: Define corporate roles, designations, and link Key Result Areas (KRAs) directly to security and compliance responsibilities.

### 1.2 User & Access Management
- **Roles & Permissions**: Role-Based Access Control (RBAC) to define granular view/edit/delete permissions for Risk Owners, Compliance Officers, Internal Auditors, and IT Staff.
- **User Provisioning**: Centralized user management dashboard with support for secure authentication integration (e.g., OAuth/SAML for Single Sign-On).
- **System Audit Trail (New - Critical)**: A tamper-proof, immutable ledger tracking all user actions within the system (e.g., who approved a policy, who altered a risk score, or who modified user access).

---

## 2. IT Governance & Policy Management

### 2.1 Committee Governance
- **Meeting Calendars**: Create, manage, and schedule recurring meetings for the IT Strategy Committee, IT Steering Committee, and Risk Boards.
- **Minutes & Agenda Distribution**: Upload and publish meeting agendas, log action items, track pending deliverables, and distribute final Meeting Minutes (MoM) to stakeholders.

### 2.2 Policy & SOP Management
- **Taxonomy & Lifecycle**: Define document hierarchies (Policies, Standards, Guidelines, and Standard Operating Procedures).
- **Collaborative Review**: Workflow engine allowing drafts to be created, reviewed with inline comments, and routed for executive approval.
- **Framework Tagging**: Map individual policy clauses or SOP steps to specific regulatory requirements (e.g., ISO 27001, SOC 2, GDPR, local central bank mandates).
- **Publish & Attest**: Distribute published policies company-wide and track employee acknowledgment read-receipts.

---

## 3. Risk & Vulnerability Management (Enhanced)

### 3.1 Enterprise Risk Register
- **Risk Lifecycle**: Capabilities to Identify, Assess (Inherent Risk scoring based on Impact × Likelihood), Treat, and Monitor IT risks.
- **Dynamic Sorting & Categorization**: Filter and prioritize risks using interactive risk matrices, criticality levels (High/Medium/Low), and business categories.

### 3.2 Vulnerability Remediation Tracking (New)
- **VAPT Log**: A central repository to log vulnerabilities identified from external audits, penetration testing (VAPT), or automated security scans.
- **Remediation Workflows**: Assign vulnerability fixes to system owners, set SLAs/deadlines, track mitigation progress, and link recurring vulnerabilities back to the primary Risk Register.

---

## 4. Compliance & Control Management (Enhanced)

### 4.1 Compliance Register
- **Multi-Regulator Library**: Centralized library to upload and track compliance requirements from various regional and global bodies.
- **Compliance Calendar**: Automated deadline tracking and alerts for recurring statutory filings, mandatory assessments, and licensing renewals.

### 4.2 Controls Definition & Exception Management
- **Control Mapping**: Define technical or administrative controls and tag them directly against corporate policies and external standards (e.g., ISO 27001 controls).
- **Control Attestation Workflow (New)**: Automated engine that periodically prompts "Control Owners" to submit evidence demonstrating that a control is active and operational.
- **Exception Management (New)**: Formal workflow to request, evaluate, temporarily approve, and log policy/control exceptions with mandatory expiry dates and compensating control documentation.

---

## 5. Internal Audit & Evidence Management (Enhanced)

### 5.1 Audit Lifecycle Management
- **Scheduling**: Manage the internal and external audit calendar across different departments and frameworks.
- **Observation Tracking**: Log audit findings, assign priority levels (High/Medium/Low), and map observations to affected risks or failed controls.
- **Collaborative Remediation**: Share specific audit findings with auditees via secure email links to capture management comments, action plans, and target closure dates.

### 5.2 Immutable Evidence Locker (New)
- **Secure Depository**: A dedicated repository where audit evidence (logs, screenshots, configurations) is cryptographically timestamped and locked upon upload to guarantee data integrity to external auditors.

---

## 6. IT Operations & Change Management (Streamlined)

### 6.1 Configurable Change Management Workflow
- **Adaptive Stages**: Flexible workflow gates covering Business Requirement Documents (BRD), Solution Design, Approvals, QA/Testing, UAT, and Production Deployment.
- **Document-Gated Status Changes**: Configurable rules requiring specific compliance or technical files to be uploaded before a project or Change Request (CR) can advance to the next phase.

### 6.2 Asset & Vendor Risk Management
- **Asset Inventory**: Category classification separating critical infrastructure (Applications, Core Servers, Firewalls) from end-user devices (Laptops, Desktops, Printers).
- **Third-Party & Vendor Risk Management (Enhanced)**: Maintain vendor agreements, monitor software licenses, and perform baseline vendor risk scoring or security questionnaire tracking for critical third-party dependencies (SaaS, cloud providers).

### 6.3 Simplified Helpdesk
- **Ticketing Hub**: Create, update, and manage support tickets for IT Assets with standard status tracking (Open, In-Progress, Resolved, Closed).
- **Incident Conversion**: One-click capability to escalate an operational helpdesk ticket into an official Security Incident.

---

## 7. Incident Management & Security Awareness

### 7.1 Incident Management Lifecycle
- **Escalation Matrix**: Configurable alerts and routing paths based on incident severity levels.
- **Root Cause Analysis (RCA)**: Post-incident workspace to document timelines, business impact, Root Cause Analysis, and long-term preventative actions.

### 7.2 Security Awareness & Training
- **Content Library**: Centralized repository to host internal training materials, security guidelines, and compliance videos.
- **Campaign Delivery**: Internal scheduling tool to coordinate security awareness email campaigns or manage registration calendars for in-person/webinar training sessions.

### 7.3 Knowledge Sharing Platform
- **Best Practices Vault**: Collaborative space for internal security teams to document architecture patterns, hardening guides, and security wins, shareable across partner networks (e.g., the Abler Nordic Network).

---

## 8. Executive Dashboards & Reporting

### 8.1 Real-Time Dashboards
- **IT Risk Dashboard**: High-level view of heatmaps, top vulnerabilities, and critical unmitigated risks.
- **IT Compliance Dashboard**: Real-time percentage tracking of framework readiness (e.g., "82% ISO 27001 Compliant") and upcoming regulatory deadlines.
- **IT Performance Dashboard**: High-level operational overview summarizing open change requests, helpdesk ticket volumes, and pending control attestations.

### 8.2 Standardized Export Reports
- **Risk & Vulnerability Report** (PDF/Excel)
- **Compliance Readiness & Exception Report** (PDF)
- **Audit Executive Summary & Remediation Tracker** (PDF)
- **Incident & RCA Analysis Report** (PDF)
- **Change Management & SLA Compliance Report** (Excel)
