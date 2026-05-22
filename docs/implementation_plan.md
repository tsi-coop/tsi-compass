# Implementation Plan: Database Schema for TSI Compass (IT GRC Tool)

We need to create `db/init.sql` to support all functional areas defined in [functional-specs.md](file:///home/tsi/tsi-compass/docs/functional-specs.md). 

This plan details the design of the PostgreSQL schema. We will use UUIDs for all primary keys to align with modern web applications and support multi-tenant/distributed scalability. Relational constraints (Foreign Keys, cascading rules, unique checks, checks on score limits) will be strictly enforced.

---

## 1. Schema Architecture & Design

We will enable the `uuid-ossp` extension to automatically generate UUIDs (`uuid_generate_v4()`). The database will consist of the following logical modules:

1. **Organization & User Access Management (RBAC)**:
   - Multi-tier offices/departments
   - Corporate roles & designations with KRAs
   - Users with status and audit settings
   - An cryptographic audit trail ledger
2. **IT Governance & Policy Management**:
   - Committees, meetings, actions, MoM
   - Policy documents, review cycles, framework mapping (ISO 27001, etc.), attestations
3. **Risk & Vulnerability Management**:
   - Risk register (inherent, treatment, impact x likelihood checks)
   - Vulnerability scans & remediation SLAs
4. **Compliance, Controls & Exceptions**:
   - Global regulator library and compliance tasks
   - Controls defined & mapped to requirements
   - Control attestations & uploads
   - Exception management requests and compensating controls
5. **Internal Audit & Cryptographic Evidence Locker**:
   - Internal audit scheduler
   - Audit findings & collaborative remediation
   - Evidence locker with sha256 checksums and digital sign locks
6. **IT Operations, Assets & Change Management**:
   - Change request workflow with adaptive document-gated stages
   - Asset inventory and classifications
   - Vendor risk register and licensing
   - Helpdesk tickets with Incident conversion link
7. **Incident Management, Awareness & Training**:
   - Security incidents, severity, escalation, RCA workspaces
   - Training modules, registration, campaigns
   - Best practices vault

---

## 2. Open Questions & Design Decisions

> [!NOTE]
> Review the following design suggestions:
> 1. **Audit Trail Immutability**: To achieve a "tamper-proof, immutable ledger" without external block-chains, we propose using a PostgreSQL trigger that computes a hash chain. Each log entry will contain a `previous_hash` and a `log_hash` calculated as `SHA256(id || timestamp || user_id || action || details || previous_hash)`. A table-level constraint or trigger will prevent updates or deletes on this table.
> 2. **Roles & RBAC**: We will define standard seed roles matching the specification: `ADMIN`, `RISK_OWNER`, `COMPLIANCE_OFFICER`, `INTERNAL_AUDITOR`, `IT_STAFF`, `USER`.
> 3. **Risk Scoring**: Risk register will use columns `inherent_impact` (1-5) and `inherent_likelihood` (1-5), computing `inherent_risk_score` automatically via a generated column `(inherent_impact * inherent_likelihood)`.

---

## 3. Proposed Schema Tables (to be created in `db/init.sql`)

### Module 1: Core Platform & RBAC

#### `organizations`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `name` VARCHAR(255) NOT NULL
- `type` VARCHAR(50) NOT NULL (e.g. 'HEAD_OFFICE', 'REGIONAL_OFFICE', 'BRANCH')
- `parent_id` UUID REFERENCES organizations(id) ON DELETE SET NULL
- `created_at` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP

#### `departments`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `org_id` UUID REFERENCES organizations(id) ON DELETE CASCADE
- `name` VARCHAR(255) NOT NULL
- `created_at` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP

#### `designations`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `title` VARCHAR(255) NOT NULL UNIQUE
- `created_at` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP

#### `designation_kras`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `designation_id` UUID REFERENCES designations(id) ON DELETE CASCADE
- `kra_title` VARCHAR(255) NOT NULL
- `responsibility_description` TEXT NOT NULL
- `created_at` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP

#### `users`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `email` VARCHAR(255) NOT NULL UNIQUE
- `password_hash` VARCHAR(255) NOT NULL
- `username` VARCHAR(100) NOT NULL
- `role` VARCHAR(50) NOT NULL CHECK (role IN ('ADMIN', 'RISK_OWNER', 'COMPLIANCE_OFFICER', 'INTERNAL_AUDITOR', 'IT_STAFF', 'USER'))
- `designation_id` UUID REFERENCES designations(id) ON DELETE SET NULL
- `department_id` UUID REFERENCES departments(id) ON DELETE SET NULL
- `status` VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'PENDING'))
- `created_at` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP

#### `system_audit_trail`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `timestamp` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
- `user_id` UUID REFERENCES users(id) ON DELETE SET NULL
- `audit_action` VARCHAR(255) NOT NULL
- `context_details` JSONB
- `previous_hash` VARCHAR(64)
- `log_hash` VARCHAR(64) NOT NULL

---

### Module 2: Governance & Policy Management

#### `committees`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `name` VARCHAR(255) NOT NULL UNIQUE (e.g. 'IT Strategy Committee')
- `description` TEXT
- `created_at` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP

#### `committee_meetings`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `committee_id` UUID REFERENCES committees(id) ON DELETE CASCADE
- `scheduled_at` TIMESTAMP WITH TIME ZONE NOT NULL
- `agenda` TEXT
- `status` VARCHAR(20) DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'CONDUCTED', 'CANCELLED'))

#### `committee_mom`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `meeting_id` UUID REFERENCES committee_meetings(id) ON DELETE CASCADE
- `mom_text` TEXT NOT NULL
- `published_at` TIMESTAMP WITH TIME ZONE
- `distributed_by` UUID REFERENCES users(id)

#### `committee_action_items`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `mom_id` UUID REFERENCES committee_mom(id) ON DELETE CASCADE
- `deliverable` TEXT NOT NULL
- `assignee_id` UUID REFERENCES users(id) ON DELETE SET NULL
- `due_date` DATE NOT NULL
- `status` VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'OVERDUE'))

#### `frameworks`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `name` VARCHAR(255) NOT NULL UNIQUE (e.g. 'ISO 27001', 'SOC 2', 'GDPR')
- `version` VARCHAR(50)
- `description` TEXT

#### `framework_requirements`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `framework_id` UUID REFERENCES frameworks(id) ON DELETE CASCADE
- `section_code` VARCHAR(50) NOT NULL (e.g., 'A.5.1')
- `title` VARCHAR(255) NOT NULL
- `description` TEXT NOT NULL

#### `policies`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `title` VARCHAR(255) NOT NULL
- `type` VARCHAR(50) NOT NULL CHECK (type IN ('POLICY', 'STANDARD', 'GUIDELINE', 'SOP'))
- `parent_policy_id` UUID REFERENCES policies(id) ON DELETE SET NULL
- `version` VARCHAR(20) DEFAULT '1.0'
- `status` VARCHAR(20) DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'UNDER_REVIEW', 'APPROVED', 'PUBLISHED', 'ARCHIVED'))
- `author_id` UUID REFERENCES users(id) ON DELETE SET NULL
- `approved_by` UUID REFERENCES users(id) ON DELETE SET NULL
- `content` TEXT NOT NULL
- `created_at` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP

#### `policy_clauses`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `policy_id` UUID REFERENCES policies(id) ON DELETE CASCADE
- `clause_number` VARCHAR(50) NOT NULL
- `title` VARCHAR(255) NOT NULL
- `content` TEXT NOT NULL

#### `clause_framework_mappings`
- `clause_id` UUID REFERENCES policy_clauses(id) ON DELETE CASCADE
- `requirement_id` UUID REFERENCES framework_requirements(id) ON DELETE CASCADE
- PRIMARY KEY (clause_id, requirement_id)

#### `policy_reviews`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `policy_id` UUID REFERENCES policies(id) ON DELETE CASCADE
- `reviewer_id` UUID REFERENCES users(id)
- `comments` TEXT NOT NULL
- `created_at` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP

#### `policy_attestations`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `policy_id` UUID REFERENCES policies(id) ON DELETE CASCADE
- `user_id` UUID REFERENCES users(id) ON DELETE CASCADE
- `acknowledged_at` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
- UNIQUE (policy_id, user_id)

---

### Module 3: Risk & Vulnerability Management

#### `risks`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `title` VARCHAR(255) NOT NULL
- `description` TEXT
- `category` VARCHAR(100) NOT NULL (e.g. 'Security', 'Operations', 'Compliance')
- `inherent_impact` INT NOT NULL CHECK (inherent_impact BETWEEN 1 AND 5)
- `inherent_likelihood` INT NOT NULL CHECK (inherent_likelihood BETWEEN 1 AND 5)
- `inherent_risk_score` INT GENERATED ALWAYS AS (inherent_impact * inherent_likelihood) STORED
- `treatment_strategy` VARCHAR(50) CHECK (treatment_strategy IN ('ACCEPT', 'MITIGATE', 'TRANSFER', 'AVOID'))
- `mitigating_controls` TEXT
- `residual_impact` INT CHECK (residual_impact BETWEEN 1 AND 5)
- `residual_likelihood` INT CHECK (residual_likelihood BETWEEN 1 AND 5)
- `owner_id` UUID REFERENCES users(id) ON DELETE SET NULL
- `status` VARCHAR(20) DEFAULT 'IDENTIFIED' CHECK (status IN ('IDENTIFIED', 'ASSESSED', 'TREATED', 'MONITORED', 'RETIRED'))
- `created_at` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP

#### `vulnerabilities`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `title` VARCHAR(255) NOT NULL
- `description` TEXT
- `source` VARCHAR(100) NOT NULL (e.g. 'VAPT', 'External Audit', 'Qualys')
- `severity` VARCHAR(20) NOT NULL CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW'))
- `status` VARCHAR(20) DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'FALSE_POSITIVE'))
- `assignee_id` UUID REFERENCES users(id) ON DELETE SET NULL
- `linked_risk_id` UUID REFERENCES risks(id) ON DELETE SET NULL
- `sla_deadline` TIMESTAMP WITH TIME ZONE NOT NULL
- `mitigation_details` TEXT
- `created_at` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP

---

### Module 4: Compliance & Control Management

#### `compliance_regulators`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `name` VARCHAR(255) NOT NULL UNIQUE (e.g. 'RBI', 'GDPR', 'SEBI')
- `description` TEXT

#### `compliance_items`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `regulator_id` UUID REFERENCES compliance_regulators(id) ON DELETE CASCADE
- `requirement_title` VARCHAR(255) NOT NULL
- `section_reference` VARCHAR(50)
- `description` TEXT NOT NULL

#### `compliance_calendar`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `compliance_item_id` UUID REFERENCES compliance_items(id) ON DELETE CASCADE
- `filing_name` VARCHAR(255) NOT NULL
- `due_date` DATE NOT NULL
- `assigned_officer_id` UUID REFERENCES users(id) ON DELETE SET NULL
- `status` VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SUBMITTED', 'OVERDUE', 'EXTENSION_REQUESTED'))
- `remind_days_before` INT DEFAULT 7

#### `controls`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `code` VARCHAR(50) NOT NULL UNIQUE (e.g. 'CTRL-01')
- `title` VARCHAR(255) NOT NULL
- `type` VARCHAR(50) NOT NULL CHECK (type IN ('TECHNICAL', 'ADMINISTRATIVE', 'PHYSICAL'))
- `description` TEXT NOT NULL
- `owner_id` UUID REFERENCES users(id) ON DELETE SET NULL
- `frequency` VARCHAR(50) NOT NULL CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'ANNUALLY', 'CONTINUOUS'))

#### `control_requirement_mappings`
- `control_id` UUID REFERENCES controls(id) ON DELETE CASCADE
- `requirement_id` UUID REFERENCES framework_requirements(id) ON DELETE CASCADE
- PRIMARY KEY (control_id, requirement_id)

#### `control_attestations`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `control_id` UUID REFERENCES controls(id) ON DELETE CASCADE
- `attested_by` UUID REFERENCES users(id) ON DELETE SET NULL
- `evidence_file_link` VARCHAR(512)
- `evidence_checksum` VARCHAR(64)
- `status` VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'COMPLIANT', 'NON_COMPLIANT', 'APPROVED'))
- `attested_at` TIMESTAMP WITH TIME ZONE
- `next_due_date` DATE NOT NULL

#### `exceptions`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `policy_id` UUID REFERENCES policies(id) ON DELETE CASCADE
- `control_id` UUID REFERENCES controls(id) ON DELETE SET NULL
- `requested_by` UUID REFERENCES users(id) ON DELETE SET NULL
- `reason` TEXT NOT NULL
- `compensating_controls` TEXT NOT NULL
- `expiry_date` DATE NOT NULL
- `status` VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED'))
- `approver_id` UUID REFERENCES users(id) ON DELETE SET NULL
- `created_at` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP

---

### Module 5: Internal Audit & Evidence Locker

#### `audits`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `title` VARCHAR(255) NOT NULL
- `scope` TEXT NOT NULL
- `framework_id` UUID REFERENCES frameworks(id) ON DELETE SET NULL
- `department_id` UUID REFERENCES departments(id) ON DELETE SET NULL
- `lead_auditor_id` UUID REFERENCES users(id) ON DELETE SET NULL
- `scheduled_start` DATE NOT NULL
- `scheduled_end` DATE NOT NULL
- `status` VARCHAR(20) DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'DRAFT_REPORT', 'FINAL_REPORT', 'CLOSED'))

#### `audit_observations`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `audit_id` UUID REFERENCES audits(id) ON DELETE CASCADE
- `title` VARCHAR(255) NOT NULL
- `description` TEXT NOT NULL
- `priority` VARCHAR(20) NOT NULL CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW'))
- `linked_risk_id` UUID REFERENCES risks(id) ON DELETE SET NULL
- `failed_control_id` UUID REFERENCES controls(id) ON DELETE SET NULL
- `status` VARCHAR(20) DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'REMEDIATED', 'CLOSED'))

#### `audit_remediations`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `observation_id` UUID REFERENCES audit_observations(id) ON DELETE CASCADE
- `action_plan` TEXT NOT NULL
- `target_date` DATE NOT NULL
- `auditee_comments` TEXT
- `secure_link_token` VARCHAR(64) UNIQUE
- `token_expiry` TIMESTAMP WITH TIME ZONE
- `status` VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'IN_PROGRESS', 'UNDER_REVIEW', 'RESOLVED'))

#### `evidence_locker`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `file_name` VARCHAR(255) NOT NULL
- `file_path` VARCHAR(512) NOT NULL
- `sha256_checksum` VARCHAR(64) NOT NULL
- `timestamp_signature` VARCHAR(256) NOT NULL -- Cryptographic hash or signature validating timestamp integrity
- `uploaded_by` UUID REFERENCES users(id) ON DELETE SET NULL
- `audit_id` UUID REFERENCES audits(id) ON DELETE SET NULL
- `control_attestation_id` UUID REFERENCES control_attestations(id) ON DELETE SET NULL
- `uploaded_at` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
- `is_locked` BOOLEAN DEFAULT TRUE

---

### Module 6: IT Operations & Change Management

#### `change_requests`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `title` VARCHAR(255) NOT NULL
- `description` TEXT NOT NULL
- `requester_id` UUID REFERENCES users(id) ON DELETE SET NULL
- `stage` VARCHAR(20) DEFAULT 'BRD' CHECK (stage IN ('BRD', 'SOLUTION_DESIGN', 'APPROVAL', 'QA_TESTING', 'UAT', 'PROD_DEPLOYMENT'))
- `status` VARCHAR(20) DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'COMPLETED'))
- `compliance_approver_id` UUID REFERENCES users(id) ON DELETE SET NULL
- `created_at` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP

#### `change_request_gates`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `change_request_id` UUID REFERENCES change_requests(id) ON DELETE CASCADE
- `stage` VARCHAR(20) NOT NULL
- `required_document_type` VARCHAR(100) NOT NULL (e.g. 'Test Plan', 'Security Sign-off')
- `uploaded_document_link` VARCHAR(512)
- `is_approved` BOOLEAN DEFAULT FALSE
- `approved_by` UUID REFERENCES users(id) ON DELETE SET NULL

#### `assets`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `name` VARCHAR(255) NOT NULL
- `category` VARCHAR(50) NOT NULL CHECK (category IN ('APPLICATION', 'CORE_SERVER', 'FIREWALL', 'LAPTOP', 'DESKTOP', 'PRINTER', 'OTHER'))
- `criticality` VARCHAR(20) NOT NULL CHECK (criticality IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW'))
- `owner_id` UUID REFERENCES users(id) ON DELETE SET NULL
- `description` TEXT

#### `vendors`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `name` VARCHAR(255) NOT NULL UNIQUE
- `agreement_details` TEXT
- `license_expiry` DATE
- `baseline_risk_score` INT CHECK (baseline_risk_score BETWEEN 1 AND 25)
- `security_questionnaire_status` VARCHAR(50) CHECK (security_questionnaire_status IN ('SENT', 'RECEIVED', 'EVALUATED', 'NOT_APPLICABLE'))
- `created_at` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP

#### `helpdesk_tickets`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `asset_id` UUID REFERENCES assets(id) ON DELETE SET NULL
- `title` VARCHAR(255) NOT NULL
- `description` TEXT NOT NULL
- `status` VARCHAR(20) DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'))
- `priority` VARCHAR(20) DEFAULT 'MEDIUM' CHECK (priority IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW'))
- `created_by` UUID REFERENCES users(id) ON DELETE SET NULL
- `assigned_to` UUID REFERENCES users(id) ON DELETE SET NULL
- `created_at` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP

---

### Module 7: Incidents & Awareness Training

#### `incidents`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `ticket_escalation_id` UUID REFERENCES helpdesk_tickets(id) ON DELETE SET NULL
- `title` VARCHAR(255) NOT NULL
- `description` TEXT NOT NULL
- `severity` VARCHAR(20) NOT NULL CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW'))
- `status` VARCHAR(20) DEFAULT 'NEW' CHECK (status IN ('NEW', 'INVESTIGATING', 'CONTAINED', 'RESOLVED', 'CLOSED'))
- `rca_timeline` TEXT
- `rca_business_impact` TEXT
- `rca_root_cause` TEXT
- `rca_preventative_actions` TEXT
- `created_at` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP

#### `incident_escalation_rules`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `severity` VARCHAR(20) NOT NULL
- `role_to_notify` VARCHAR(50) NOT NULL
- `escalation_delay_minutes` INT NOT NULL

#### `training_materials`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `title` VARCHAR(255) NOT NULL
- `type` VARCHAR(50) CHECK (type IN ('VIDEO', 'DOCUMENT', 'GUIDELINE'))
- `content_url` VARCHAR(512) NOT NULL
- `created_at` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP

#### `awareness_campaigns`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `name` VARCHAR(255) NOT NULL
- `scheduled_start` DATE NOT NULL
- `scheduled_end` DATE NOT NULL
- `material_id` UUID REFERENCES training_materials(id) ON DELETE SET NULL
- `status` VARCHAR(20) DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'ACTIVE', 'COMPLETED'))

#### `campaign_enrollments`
- `campaign_id` UUID REFERENCES awareness_campaigns(id) ON DELETE CASCADE
- `user_id` UUID REFERENCES users(id) ON DELETE CASCADE
- `status` VARCHAR(20) DEFAULT 'ENROLLED' CHECK (status IN ('ENROLLED', 'IN_PROGRESS', 'COMPLETED', 'FAILED'))
- `completed_at` TIMESTAMP WITH TIME ZONE
- PRIMARY KEY (campaign_id, user_id)

#### `best_practices_vault`
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `title` VARCHAR(255) NOT NULL
- `content` TEXT NOT NULL
- `author_id` UUID REFERENCES users(id) ON DELETE SET NULL
- `created_at` TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP

---

## 4. Verification Plan

### Automated Verification
- We will verify the schema syntax by executing it against PostgreSQL or a lint parser.
- Ensure all constraints, relationships, triggers, and indices load without errors.

### Manual Verification
- We will run a test transaction to verify that:
  1. Users cannot edit the `system_audit_trail` table.
  2. The hash chain trigger automatically hashes entries and links them.
  3. Generating a risk with impact 4 and likelihood 5 computes `inherent_risk_score` = 20.
