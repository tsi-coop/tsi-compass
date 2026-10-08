# DPIA walkthrough: the Varam example

Continues `data-governance-varam-example.md`. Shows how the DPIA page (`data-governance-dpia.html`, DPDP Sec 10(2)(c)(i) / Rule 13(1)) derives a risk rating for each of the 7 Varam processing activities, and how to take one through review. Scoring rules are in `docs/dpia-plan.md` section 2.

## Load it

1. Apply `db/19_dpia.sql`, then `seed-varam-data.sql` (see this folder's README).
2. Run `seed-varam-dpia.sql`. It sets two signals that no existing column can express: **profiling** on Credit Assessment & Scoring, and **large scale** on Loan Servicing & Collections.
3. Open the DPIA page and click **Auto-generate from RoPA**.

## Expected result: 4 High / 1 Medium / 2 Low

Score of 4 or more is High, 3 is Medium, 2 or less is Low.

| Activity | Factors (points) | Score | Risk |
|---|---|---|---|
| Borrower Credit Assessment & Scoring | Profiling 3, Financial 2, Third-party 1 | 6 | High |
| Borrower KYC & Identity Verification | Sensitive (RESTRICTED) 4, Third-party 1 | 5 | High |
| Employee Health Insurance & Benefits | Sensitive (HEALTH) 4, Third-party 1 | 5 | High |
| Borrower Loan Servicing & Collections | Financial 2, Third-party 1, Large scale 1 | 4 | High |
| Borrower Marketing & Promotional Offers | Cross-border (US, Twilio) 2, Third-party 1 | 3 | Medium |
| Employee Payroll, Taxation & Compliance | Third-party 1 | 1 | Low |
| Employee Performance & Skill Development | Third-party 1 | 1 | Low |

Each row's detail view holds the five sections: Necessity & Lawful Basis (from purpose and processing basis), Proportionality (category, data elements, principals, retention), Risk Factors, Mitigations (one canned line per factor, editable) and Residual Risk (defaults to inherent).

## Try the workflow

1. **Edit and review.** On Payroll, edit the mitigations and set residual risk, then move it Draft → Under Review → Approved → Submitted (with a board reference). Skipping a step is rejected, and an Approved DPIA is locked.
2. **Re-generate is safe.** Click Auto-generate again: Draft rows refresh their derived fields but keep your edited text; Under Review and later rows are never overwritten.
3. **Stale detection.** Edit the Payroll flow "Payroll processing via Greytip" to be cross-border. Re-generate. The submitted Payroll DPIA stays as filed but shows **stale** (derived score is now 3, Medium), prompting re-review.
4. **Clear.** *Clear auto-generated* deletes Draft rows only.

## Notes

- The scores follow the Sec 16 and Rule 6 factors only; the point weights are a starting heuristic for a GRC Officer to tune, not a legal determination.
- Access follows Data Governance: GRC_OFFICER and ADMIN only; deleting a DPIA is ADMIN-only.
