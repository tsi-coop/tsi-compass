package org.tsicoop.compass.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.EventLog;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.OutputProcessor;
import org.tsicoop.compass.framework.PoolDB;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Third-Party Risk Management. Extends the shared vendor register (vendors table) with a risk tier,
// questionnaire-based assessments, findings, contracts, documents, computed gaps and a review cadence.
// Sits under Risk & Vulnerability and uses the 'risks' permission module. See docs/guides/third-party-risk-guide.md and docs/architecture/system-design.md.
public class Tprm implements Action {

    // ---------- small framework bits ----------

    private static class HttpError extends RuntimeException {
        final int status; final String error;
        HttpError(int status, String error, String message) { super(message); this.status = status; this.error = error; }
    }
    private static HttpError bad(String m)      { return new HttpError(400, "Bad Request", m); }
    private static HttpError notFound(String m) { return new HttpError(404, "Not Found", m); }
    private static HttpError conflict(String m) { return new HttpError(409, "Conflict", m); }

    private interface Work<T> { T run(Connection c) throws Exception; }

    private <T> T db(boolean tx, Work<T> w) throws Exception {
        PoolDB pool = new PoolDB(); Connection conn = null;
        try {
            conn = pool.getConnection();
            if (tx) conn.setAutoCommit(false);
            try {
                T out = w.run(conn);
                if (tx) conn.commit();
                return out;
            } catch (Exception e) {
                if (tx) try { conn.rollback(); } catch (Exception ignored) {}
                throw e;
            } finally {
                if (tx) try { conn.setAutoCommit(true); } catch (Exception ignored) {}
            }
        } finally { try { pool.cleanup(null, null, conn); } catch (Exception ignored) {} }
    }

    private void bind(PreparedStatement p, Object[] args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) p.setNull(i + 1, Types.VARCHAR);
            else if (a instanceof Boolean) p.setBoolean(i + 1, (Boolean) a);
            else if (a instanceof Integer) p.setInt(i + 1, (Integer) a);
            else if (a instanceof Long) p.setLong(i + 1, (Long) a);
            else if (a instanceof Double) p.setDouble(i + 1, (Double) a);
            else p.setString(i + 1, a.toString());
        }
    }

    /** Runs a SELECT and returns one JSON object per row, typed from the column metadata. */
    @SuppressWarnings("unchecked")
    private JSONArray query(Connection c, String sql, Object... args) throws Exception {
        PreparedStatement p = null; ResultSet rs = null;
        JSONArray out = new JSONArray();
        try {
            p = c.prepareStatement(sql); bind(p, args); rs = p.executeQuery();
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                JSONObject o = new JSONObject();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    int t = md.getColumnType(i); Object v;
                    if (t == Types.BIT || t == Types.BOOLEAN) { boolean b = rs.getBoolean(i); v = rs.wasNull() ? null : (Object) b; }
                    else if (t == Types.INTEGER || t == Types.SMALLINT || t == Types.BIGINT || t == Types.TINYINT) { long l = rs.getLong(i); v = rs.wasNull() ? null : (Object) l; }
                    else if (t == Types.NUMERIC || t == Types.DECIMAL || t == Types.DOUBLE || t == Types.REAL || t == Types.FLOAT) { double d = rs.getDouble(i); v = rs.wasNull() ? null : (Object) d; }
                    else v = rs.getString(i);
                    o.put(md.getColumnLabel(i), v);
                }
                out.add(o);
            }
        } finally { closeQuietly(rs, p); }
        return out;
    }

    private JSONObject one(Connection c, String sql, Object... args) throws Exception {
        JSONArray a = query(c, sql, args);
        return a.isEmpty() ? null : (JSONObject) a.get(0);
    }

    private int exec(Connection c, String sql, Object... args) throws Exception {
        PreparedStatement p = null;
        try { p = c.prepareStatement(sql); bind(p, args); return p.executeUpdate(); }
        finally { closeQuietly(null, p); }
    }

    private void closeQuietly(ResultSet rs, PreparedStatement p) {
        try { if (rs != null) rs.close(); } catch (Exception ignored) {}
        try { if (p != null) p.close(); } catch (Exception ignored) {}
    }

    private static String s(JSONObject o, String k) { Object v = o.get(k); return v == null ? null : v.toString(); }
    private static long l(JSONObject o, String k) { Object v = o.get(k); return v instanceof Number ? ((Number) v).longValue() : 0L; }
    private static boolean b(JSONObject o, String k) { return Boolean.TRUE.equals(o.get(k)); }
    private static boolean isBlank(String x) { return x == null || x.trim().isEmpty(); }

    private JSONObject ok() { JSONObject r = new JSONObject(); r.put("success", true); return r; }

    private void log(HttpServletRequest req, String action, Object... kv) {
        JSONObject ctx = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) ctx.put(kv[i], kv[i + 1]);
        EventLog.log(InputProcessor.getEmail(req), action, ctx.toJSONString());
    }

    private void requireAdmin(HttpServletRequest req) {
        if (!"ADMIN".equals(InputProcessor.getRole(req))) throw new HttpError(403, "Forbidden", "Only administrators can do this.");
    }

    private String requireText(JSONObject in, String key) {
        String v = (String) in.get(key);
        if (isBlank(v)) throw bad(key + " required");
        return v.trim();
    }

    private String enumVal(JSONObject in, String key, String... allowed) {
        Object o = in.get(key);
        if (o == null || o.toString().trim().isEmpty()) return null;
        String v = o.toString().trim().toUpperCase();
        if (!Arrays.asList(allowed).contains(v)) throw bad(key + " must be one of " + String.join(", ", allowed));
        return v;
    }

    private static final String[] TIERS = {"LOW", "MEDIUM", "HIGH", "CRITICAL"};
    private static final String[] SEVERITIES = {"CRITICAL", "HIGH", "MEDIUM", "LOW"};

    // ---------- dispatch ----------

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject in = InputProcessor.getInput(req);
            String func = (String) in.get("_func");
            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing _func", req.getRequestURI()); return;
            }
            JSONObject out;
            switch (func.toLowerCase()) {
                case "get_tprm_metrics":          out = getMetrics(); break;
                case "get_review_calendar":       out = getCalendar(in); break;
                case "list_owners":               out = listOwners(); break;
                // vendors
                case "list_vendors_tprm":         out = listVendors(in); break;
                case "get_vendor_profile":        out = getVendorProfile(in); break;
                case "create_vendor":             out = createVendor(req, in); break;
                case "update_vendor_profile":     out = updateVendorProfile(req, in); break;
                case "recalculate_inherent_risk": out = recalculate(req, in); break;
                case "set_vendor_status":         out = setVendorStatus(req, in); break;
                case "update_offboarding_item":   out = updateOffboardingItem(req, in); break;
                case "archive_vendor":            out = archiveVendor(req, in); break;
                case "restore_vendor":            out = restoreVendor(req, in); break;
                // questionnaires
                case "list_questionnaires":       out = listQuestionnaires(); break;
                case "get_questionnaire":         out = getQuestionnaire(in); break;
                case "save_questionnaire":        out = saveQuestionnaire(req, in); break;
                case "clone_questionnaire":       out = cloneQuestionnaire(req, in); break;
                case "save_question":             out = saveQuestion(req, in); break;
                case "delete_question":           out = deleteQuestion(req, in); break;
                // assessments
                case "list_assessments":          out = listAssessments(in); break;
                case "start_assessment":          out = startAssessment(req, in); break;
                case "get_assessment":            out = getAssessment(in); break;
                case "save_answer":               out = saveAnswer(req, in); break;
                case "mark_assessment_sent":      out = markSent(req, in); break;
                case "complete_assessment":       out = completeAssessment(req, in); break;
                case "cancel_assessment":         out = cancelAssessment(req, in); break;
                // findings
                case "list_findings":             out = listFindings(in); break;
                case "add_finding":               out = addFinding(req, in); break;
                case "update_finding":            out = updateFinding(req, in); break;
                case "accept_finding":            out = acceptFinding(req, in); break;
                case "escalate_finding_to_risk":  out = escalateFinding(req, in); break;
                // vendor portal (GRC side)
                case "list_vendor_users":         out = listVendorUsers(in); break;
                case "create_vendor_user":        out = createVendorUser(req, in); break;
                case "disable_vendor_user":       out = setVendorUserStatus(req, in, false); break;
                case "enable_vendor_user":        out = setVendorUserStatus(req, in, true); break;
                case "reset_vendor_user_password": out = resetVendorUserPassword(req, in); break;
                case "send_to_vendor":            out = sendToVendor(req, in); break;
                case "withdraw_from_vendor":      out = withdrawFromVendor(req, in); break;
                case "return_questions":          out = returnQuestions(req, in); break;
                // contracts / documents
                case "list_contracts":            out = listContracts(in); break;
                case "add_contract":              out = addContract(req, in); break;
                case "update_contract":           out = updateContract(req, in); break;
                case "delete_contract":           out = deleteContract(req, in); break;
                case "list_documents":            out = listDocuments(in); break;
                case "upload_document":           out = uploadDocument(req, in); break;
                case "get_document":              out = getDocument(in); break;
                case "delete_document":           out = deleteDocument(req, in); break;
                default:
                    OutputProcessor.errorResponse(res, 400, "Bad Request", "Unknown function: " + func, req.getRequestURI()); return;
            }
            OutputProcessor.send(res, 200, out);
        } catch (HttpError e) {
            OutputProcessor.errorResponse(res, e.status, e.error, e.getMessage(), req.getRequestURI());
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Internal Error", e.getMessage(), req.getRequestURI());
        }
    }

    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse r) { return "POST".equalsIgnoreCase(m); }

    // ---------- inherent risk ----------

    /** Data assets shared with a vendor: linked directly, or listed as a recipient (by link or by name). */
    private static final String LINKED = "(da.linked_vendor_id = v.id OR EXISTS (SELECT 1 FROM data_asset_recipients r " +
        "WHERE r.data_asset_id = da.id AND (r.linked_vendor_id = v.id OR LOWER(r.recipient_name) = LOWER(v.name))))";

    private static final String VENDOR_SQL =
        "SELECT v.id::text AS id, v.name, v.vendor_type, v.criticality, v.lifecycle_status, v.business_owner_id::text AS business_owner_id, " +
        "bo.username AS business_owner_name, v.description, v.primary_contact_name, v.primary_contact_email, v.website, " +
        "v.processes_personal_data, v.stores_data_outside_india, v.has_network_access, v.is_fourth_party_dependent, " +
        "v.inherent_override, v.inherent_override_reason, v.residual_risk, v.last_assessed_at::text AS last_assessed_at, " +
        "v.next_review_due::text AS next_review_due, v.review_cycle_months, v.license_expiry::text AS license_expiry, v.agreement_details, " +
        "v.baseline_risk_score, v.security_questionnaire_status, v.archived_at::text AS archived_at, v.created_at::text AS created_at, " +
        "v.offboarding_checklist::text AS offboarding_checklist, v.offboarding_override_reason, " +
        "(SELECT COUNT(*) FROM data_assets da WHERE " + LINKED + ") AS linked_assets, " +
        "(SELECT COUNT(*) FROM data_assets da WHERE " + LINKED + " AND da.category IN ('PII','HEALTH','FINANCIAL','EMPLOYEE')) AS linked_personal, " +
        "EXISTS (SELECT 1 FROM data_assets da WHERE " + LINKED + " AND (da.category IN ('HEALTH','FINANCIAL') OR da.sensitivity = 'RESTRICTED')) AS linked_sensitive, " +
        "EXISTS (SELECT 1 FROM data_assets da JOIN data_flows f ON (f.source_data_asset_id = da.id OR f.destination_data_asset_id = da.id) " +
        " WHERE " + LINKED + " AND f.is_cross_border = true) AS linked_cross_border, " +
        "(SELECT COUNT(*) FROM tprm_findings f WHERE f.vendor_id = v.id AND f.status IN ('OPEN','IN_PROGRESS')) AS open_findings " +
        "FROM vendors v LEFT JOIN users bo ON bo.id = v.business_owner_id";

    private static class Risk {
        int score; String derived; String effective; JSONArray factors = new JSONArray(); boolean effPersonal;
    }

    @SuppressWarnings("unchecked")
    private void factor(Risk r, String code, String label, String reference, int points) {
        JSONObject f = new JSONObject();
        f.put("code", code); f.put("label", label); f.put("reference", reference); f.put("points", (long) points);
        r.factors.add(f); r.score += points;
    }

    private Risk assess(JSONObject v) {
        Risk r = new Risk();
        String crit = s(v, "criticality");
        int cp = "CRITICAL".equals(crit) ? 4 : "HIGH".equals(crit) ? 3 : "MEDIUM".equals(crit) ? 2 : 0;
        if (cp > 0) factor(r, "CRITICALITY", "Business criticality: " + crit, "Business impact", cp);
        r.effPersonal = b(v, "processes_personal_data") || l(v, "linked_personal") > 0;
        if (r.effPersonal) factor(r, "PERSONAL_DATA", "Processes personal data" + (b(v, "processes_personal_data") ? "" : " (from Data Governance links)"), "DPDP Sec 8(2)", 3);
        if (b(v, "linked_sensitive")) factor(r, "SENSITIVE_DATA", "Sensitive data shared (health, financial or restricted)", "DPDP Rule 6", 2);
        if (b(v, "stores_data_outside_india") || b(v, "linked_cross_border"))
            factor(r, "DATA_OUTSIDE_INDIA", "Data held or transferred outside India", "DPDP Sec 16", 2);
        if (b(v, "has_network_access")) factor(r, "NETWORK_ACCESS", "Network or system access", "Access risk", 2);
        if (b(v, "is_fourth_party_dependent")) factor(r, "FOURTH_PARTY", "Depends on fourth parties", "Supply chain", 1);
        r.derived = r.score >= 8 ? "CRITICAL" : r.score >= 5 ? "HIGH" : r.score >= 3 ? "MEDIUM" : "LOW";
        String ov = s(v, "inherent_override");
        r.effective = isBlank(ov) ? r.derived : ov;
        return r;
    }

    private static int defaultCycleMonths(String tier) {
        return "CRITICAL".equals(tier) ? 6 : "HIGH".equals(tier) ? 12 : "MEDIUM".equals(tier) ? 24 : 36;
    }

    private static String stepTier(String tier, int delta) {
        int i = Arrays.asList(TIERS).indexOf(tier) + delta;
        return TIERS[Math.max(0, Math.min(TIERS.length - 1, i))];
    }

    private JSONObject loadVendor(Connection c, String id) throws Exception {
        JSONObject v = one(c, VENDOR_SQL + " WHERE v.id = ?::uuid", id);
        if (v == null) throw notFound("Vendor not found");
        return v;
    }

    private void assertWritable(Connection c, String vendorId) throws Exception {
        JSONObject v = one(c, "SELECT archived_at IS NOT NULL AS archived FROM vendors WHERE id = ?::uuid", vendorId);
        if (v == null) throw notFound("Vendor not found");
        if (b(v, "archived")) throw conflict("This vendor is archived and read-only. Restore it first.");
    }

    /** Stores the current effective tier and score on the vendor row. */
    private Risk snapshot(Connection c, String vendorId) throws Exception {
        JSONObject v = loadVendor(c, vendorId);
        Risk r = assess(v);
        exec(c, "UPDATE vendors SET inherent_risk = ?, inherent_score = ? WHERE id = ?::uuid", r.effective, r.score, vendorId);
        return r;
    }

    // ---------- gaps ----------

    private static final String GAP_SQL =
        "SELECT v.id::text AS id, v.name, v.lifecycle_status, (v.archived_at IS NOT NULL) AS archived, v.next_review_due, v.last_assessed_at, " +
        "(v.processes_personal_data OR EXISTS (SELECT 1 FROM data_assets da WHERE " + LINKED + " AND da.category IN ('PII','HEALTH','FINANCIAL','EMPLOYEE'))) AS eff_personal, " +
        "EXISTS (SELECT 1 FROM tprm_contracts c WHERE c.vendor_id = v.id AND c.contract_type = 'DPA' AND (c.end_date IS NULL OR c.end_date >= CURRENT_DATE)) AS has_dpa, " +
        "(SELECT COUNT(*) FROM tprm_contracts c WHERE c.vendor_id = v.id AND c.contract_type <> 'NDA' AND (c.end_date IS NULL OR c.end_date >= CURRENT_DATE)) AS live_contracts, " +
        "EXISTS (SELECT 1 FROM tprm_contracts c WHERE c.vendor_id = v.id AND c.contract_type <> 'NDA' AND (c.end_date IS NULL OR c.end_date >= CURRENT_DATE) AND c.has_breach_notification_clause) AS any_breach, " +
        "EXISTS (SELECT 1 FROM tprm_contracts c WHERE c.vendor_id = v.id AND c.contract_type <> 'NDA' AND (c.end_date IS NULL OR c.end_date >= CURRENT_DATE) AND c.has_data_return_deletion_clause) AS any_return, " +
        "(SELECT COUNT(*) FROM (SELECT doc_type, MAX(valid_until) AS m FROM tprm_documents d WHERE d.vendor_id = v.id AND d.valid_until IS NOT NULL GROUP BY doc_type) x WHERE x.m < CURRENT_DATE) AS docs_expired, " +
        "(SELECT COUNT(*) FROM (SELECT doc_type, MAX(valid_until) AS m FROM tprm_documents d WHERE d.vendor_id = v.id AND d.valid_until IS NOT NULL GROUP BY doc_type) x WHERE x.m >= CURRENT_DATE AND x.m < CURRENT_DATE + 60) AS docs_expiring, " +
        "(SELECT COUNT(*) FROM tprm_findings f WHERE f.vendor_id = v.id AND f.severity IN ('CRITICAL','HIGH') AND f.status IN ('OPEN','IN_PROGRESS') AND f.due_date < CURRENT_DATE) AS overdue_findings, " +
        "(SELECT COUNT(*) FROM data_assets da WHERE " + LINKED + ") AS linked_assets " +
        "FROM vendors v";

    @SuppressWarnings("unchecked")
    private void gap(JSONArray list, String code, String label, String reference, String severity) {
        JSONObject g = new JSONObject();
        g.put("code", code); g.put("label", label); g.put("reference", reference); g.put("severity", severity);
        list.add(g);
    }

    private JSONArray gapsFor(JSONObject g) {
        JSONArray gaps = new JSONArray();
        String life = s(g, "lifecycle_status");
        boolean archived = b(g, "archived");
        boolean live = !archived && ("ACTIVE".equals(life) || "ONBOARDING".equals(life));
        boolean personal = b(g, "eff_personal");
        if (!archived && !"TERMINATED".equals(life) && !"PROSPECT".equals(life)) {
            if (personal && !b(g, "has_dpa"))
                gap(gaps, "NO_DPA", "Personal data is shared but no valid DPA is on file", "DPDP Sec 8(2)", "HIGH");
            if (personal && l(g, "live_contracts") > 0 && !b(g, "any_breach"))
                gap(gaps, "BREACH_CLAUSE", "No contract has a breach notification clause", "DPDP Sec 8(6)", "HIGH");
            if (personal && l(g, "live_contracts") > 0 && !b(g, "any_return"))
                gap(gaps, "DATA_RETURN_CLAUSE", "No contract has a data return / deletion clause", "DPDP Sec 8(7)", "MEDIUM");
        }
        if (live) {
            if (s(g, "last_assessed_at") == null) gap(gaps, "NEVER_ASSESSED", "Never assessed", "Review cadence", "MEDIUM");
            String due = s(g, "next_review_due");
            if (due != null && java.time.LocalDate.parse(due.substring(0, 10)).isBefore(java.time.LocalDate.now()))
                gap(gaps, "REVIEW_OVERDUE", "Review date has passed", "Review cadence", "MEDIUM");
        }
        if (!archived && !"TERMINATED".equals(life)) {
            if (l(g, "docs_expired") > 0) gap(gaps, "DOC_EXPIRED", "A certificate or report on file has expired", "Evidence validity", "HIGH");
            else if (l(g, "docs_expiring") > 0) gap(gaps, "DOC_EXPIRING", "A certificate or report expires within 60 days", "Evidence validity", "MEDIUM");
            if (l(g, "overdue_findings") > 0) gap(gaps, "FINDING_OVERDUE", "Critical or high finding is past its due date", "Remediation", "HIGH");
        }
        if ((archived || "TERMINATED".equals(life) || "OFFBOARDING".equals(life)) && l(g, "linked_assets") > 0)
            gap(gaps, "OFFBOARDED_RECIPIENT", "Vendor is " + (archived ? "archived" : life.toLowerCase()) + " but still linked to " + l(g, "linked_assets") + " data asset(s)", "DPDP Sec 8(7)", "HIGH");
        return gaps;
    }

    private Map<String, JSONArray> allGaps(Connection c) throws Exception {
        Map<String, JSONArray> map = new HashMap<>();
        JSONArray rows = query(c, GAP_SQL);
        for (Object o : rows) {
            JSONObject g = (JSONObject) o;
            map.put(s(g, "id"), gapsFor(g));
        }
        return map;
    }

    // ---------- vendors ----------

    @SuppressWarnings("unchecked")
    private JSONObject decorateVendor(JSONObject v, Risk r, JSONArray gaps) {
        v.put("inherent_derived", r.derived);
        v.put("inherent_risk", r.effective);
        v.put("inherent_score", (long) r.score);
        v.put("risk_factors", r.factors);
        v.put("effective_personal_data", r.effPersonal);
        v.put("override_active", !isBlank(s(v, "inherent_override")));
        v.put("effective_review_cycle_months", v.get("review_cycle_months") != null ? v.get("review_cycle_months") : (Object) (long) defaultCycleMonths(r.effective));
        v.put("gaps", gaps == null ? new JSONArray() : gaps);
        v.put("gap_count", (long) (gaps == null ? 0 : gaps.size()));
        String due = s(v, "next_review_due");
        String rs = "OK";
        if (s(v, "last_assessed_at") == null) rs = "NEVER";
        if (due != null) {
            java.time.LocalDate d = java.time.LocalDate.parse(due.substring(0, 10)), today = java.time.LocalDate.now();
            if (d.isBefore(today)) rs = "OVERDUE"; else if (!d.isAfter(today.plusDays(30))) rs = "DUE_SOON";
        }
        v.put("review_status", rs);
        try { v.put("offboarding", new org.json.simple.parser.JSONParser().parse(s(v, "offboarding_checklist"))); } catch (Exception e) { v.put("offboarding", new JSONObject()); }
        v.remove("offboarding_checklist");
        return v;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listVendors(JSONObject in) throws Exception {
        String tier = enumVal(in, "tier", TIERS);
        String life = enumVal(in, "lifecycle_status", "PROSPECT", "ONBOARDING", "ACTIVE", "OFFBOARDING", "TERMINATED");
        String search = (String) in.get("search");
        boolean archived = Boolean.TRUE.equals(in.get("show_archived"));
        boolean gapsOnly = Boolean.TRUE.equals(in.get("with_gaps"));
        return db(false, c -> {
            Map<String, JSONArray> gaps = allGaps(c);
            JSONArray out = new JSONArray();
            for (Object o : query(c, VENDOR_SQL + " ORDER BY v.name")) {
                JSONObject v = (JSONObject) o;
                boolean isArch = s(v, "archived_at") != null;
                if (isArch != archived) continue;
                if (!isBlank(search) && !s(v, "name").toLowerCase().contains(search.trim().toLowerCase())) continue;
                if (life != null && !life.equals(s(v, "lifecycle_status"))) continue;
                Risk r = assess(v);
                if (tier != null && !tier.equals(r.effective)) continue;
                JSONArray g = gaps.get(s(v, "id"));
                if (gapsOnly && (g == null || g.isEmpty())) continue;
                decorateVendor(v, r, g);
                out.add(v);
            }
            JSONObject res = ok(); res.put("vendors", out); res.put("total", (long) out.size());
            return res;
        });
    }

    @SuppressWarnings("unchecked")
    private JSONObject getVendorProfile(JSONObject in) throws Exception {
        String id = requireText(in, "id");
        return db(false, c -> {
            JSONObject v = loadVendor(c, id);
            Risk r = assess(v);
            JSONObject g = one(c, GAP_SQL + " WHERE v.id = ?::uuid", id);
            decorateVendor(v, r, g == null ? new JSONArray() : gapsFor(g));
            v.put("linked_assets_list", query(c,
                "SELECT da.id::text AS id, da.name, da.category, da.sensitivity, da.discovery_status, " +
                "CASE WHEN da.linked_vendor_id = v.id THEN 'Linked vendor' ELSE 'Recipient' END AS relation " +
                "FROM vendors v, data_assets da WHERE v.id = ?::uuid AND " + LINKED + " ORDER BY da.name", id));
            v.put("linked_risks", query(c,
                "SELECT DISTINCT rk.id::text AS id, rk.risk_code, rk.title, rk.status FROM tprm_findings f JOIN risks rk ON rk.id = f.linked_risk_id " +
                "WHERE f.vendor_id = ?::uuid ORDER BY rk.risk_code", id));
            JSONObject res = ok(); res.put("vendor", v);
            return res;
        });
    }

    private static final Map<String, String> VENDOR_FIELDS = new LinkedHashMap<>();
    static {
        VENDOR_FIELDS.put("vendor_type", ""); VENDOR_FIELDS.put("criticality", ""); VENDOR_FIELDS.put("business_owner_id", "uuid");
        VENDOR_FIELDS.put("description", ""); VENDOR_FIELDS.put("primary_contact_name", ""); VENDOR_FIELDS.put("primary_contact_email", "");
        VENDOR_FIELDS.put("website", ""); VENDOR_FIELDS.put("processes_personal_data", "boolean"); VENDOR_FIELDS.put("stores_data_outside_india", "boolean");
        VENDOR_FIELDS.put("has_network_access", "boolean"); VENDOR_FIELDS.put("is_fourth_party_dependent", "boolean");
        VENDOR_FIELDS.put("review_cycle_months", "int"); VENDOR_FIELDS.put("agreement_details", ""); VENDOR_FIELDS.put("license_expiry", "date");
    }

    private void validateVendorEnums(JSONObject in) {
        enumVal(in, "vendor_type", "SAAS", "CLOUD_INFRA", "SERVICE_PROVIDER", "CONSULTANT", "HARDWARE", "OTHER");
        enumVal(in, "criticality", SEVERITIES);
        Object m = in.get("review_cycle_months");
        if (m instanceof Number && (((Number) m).longValue() < 1 || ((Number) m).longValue() > 120)) throw bad("review_cycle_months must be between 1 and 120");
    }

    /** Applies only the keys present in the input; a blank string clears a nullable column. */
    private void applyFields(Connection c, String table, String id, JSONObject in, Map<String, String> fields) throws Exception {
        StringBuilder set = new StringBuilder(); List<Object> args = new ArrayList<>();
        for (Map.Entry<String, String> f : fields.entrySet()) {
            if (!in.containsKey(f.getKey())) continue;
            Object raw = in.get(f.getKey()); Object val;
            String cast = f.getValue();
            if (raw == null) val = null;
            else if (raw instanceof Boolean) val = raw;
            else if (raw instanceof Number) val = "int".equals(cast) ? (Object) ((Number) raw).intValue() : raw.toString();
            else val = raw.toString().trim().isEmpty() ? null : raw.toString().trim();
            if (set.length() > 0) set.append(", ");
            set.append(f.getKey()).append(" = ?").append(cast.isEmpty() ? "" : "::" + cast);
            args.add(val);
        }
        if (set.length() == 0) return;
        args.add(id);
        exec(c, "UPDATE " + table + " SET " + set + " WHERE id = ?::uuid", args.toArray());
    }

    private void checkVendorName(Connection c, String name, String exceptId) throws Exception {
        JSONObject clash = one(c, "SELECT id::text AS id, (archived_at IS NOT NULL) AS archived FROM vendors WHERE LOWER(name) = LOWER(?)" +
            (exceptId == null ? "" : " AND id <> ?::uuid"), exceptId == null ? new Object[]{name} : new Object[]{name, exceptId});
        if (clash == null) return;
        throw conflict(b(clash, "archived") ? "An archived vendor with this name exists, restore it instead" : "A vendor with this name already exists");
    }

    @SuppressWarnings("unchecked")
    private JSONObject createVendor(HttpServletRequest req, JSONObject in) throws Exception {
        String name = requireText(in, "name");
        validateVendorEnums(in);
        return db(true, c -> {
            checkVendorName(c, name, null);
            JSONObject row = one(c, "INSERT INTO vendors (name) VALUES (?) RETURNING id::text AS id", name);
            String id = s(row, "id");
            applyFields(c, "vendors", id, in, VENDOR_FIELDS);
            Risk r = snapshot(c, id);
            log(req, "TPRM_VENDOR_CREATED", "vendor_id", id, "name", name, "inherent_risk", r.effective);
            JSONObject res = ok(); res.put("id", id); res.put("inherent_risk", r.effective); res.put("inherent_score", (long) r.score);
            return res;
        });
    }

    @SuppressWarnings("unchecked")
    private JSONObject updateVendorProfile(HttpServletRequest req, JSONObject in) throws Exception {
        String id = requireText(in, "id");
        validateVendorEnums(in);
        return db(true, c -> {
            assertWritable(c, id);
            if (in.containsKey("name")) {
                String name = requireText(in, "name");
                checkVendorName(c, name, id);
                exec(c, "UPDATE vendors SET name = ? WHERE id = ?::uuid", name, id);
            }
            applyFields(c, "vendors", id, in, VENDOR_FIELDS);
            if (in.containsKey("inherent_override")) {
                String ov = enumVal(in, "inherent_override", TIERS);
                if (ov == null) {
                    exec(c, "UPDATE vendors SET inherent_override = NULL, inherent_override_reason = NULL WHERE id = ?::uuid", id);
                } else {
                    String reason = (String) in.get("inherent_override_reason");
                    if (isBlank(reason)) throw bad("A reason is required when overriding the risk tier");
                    exec(c, "UPDATE vendors SET inherent_override = ?, inherent_override_reason = ? WHERE id = ?::uuid", ov, reason.trim(), id);
                }
            }
            Risk r = snapshot(c, id);
            // A changed review cycle moves the next review relative to the last assessment.
            JSONObject v = loadVendor(c, id);
            if (in.containsKey("review_cycle_months") && s(v, "last_assessed_at") != null) {
                long months = v.get("review_cycle_months") != null ? l(v, "review_cycle_months") : defaultCycleMonths(r.effective);
                exec(c, "UPDATE vendors SET next_review_due = (last_assessed_at + (? || ' months')::interval)::date WHERE id = ?::uuid", String.valueOf(months), id);
            }
            log(req, "TPRM_VENDOR_UPDATED", "vendor_id", id, "inherent_risk", r.effective);
            JSONObject res = ok(); res.put("inherent_risk", r.effective); res.put("inherent_score", (long) r.score);
            return res;
        });
    }

    @SuppressWarnings("unchecked")
    private JSONObject recalculate(HttpServletRequest req, JSONObject in) throws Exception {
        String id = (String) in.get("id");
        return db(true, c -> {
            long n = 0;
            if (isBlank(id)) {
                for (Object o : query(c, "SELECT id::text AS id FROM vendors WHERE archived_at IS NULL")) { snapshot(c, s((JSONObject) o, "id")); n++; }
            } else { assertWritable(c, id); snapshot(c, id); n = 1; }
            log(req, "TPRM_RISK_RECALCULATED", "vendors", n);
            JSONObject res = ok(); res.put("recalculated", n);
            return res;
        });
    }

    private static final String[] OFFBOARDING_ITEMS = {"revoke_access", "return_delete_data", "retrieve_assets", "close_findings", "final_invoice"};

    @SuppressWarnings("unchecked")
    private JSONObject setVendorStatus(HttpServletRequest req, JSONObject in) throws Exception {
        String id = requireText(in, "id");
        String target = enumVal(in, "lifecycle_status", "PROSPECT", "ONBOARDING", "ACTIVE", "OFFBOARDING", "TERMINATED");
        if (target == null) throw bad("lifecycle_status required");
        String overrideReason = (String) in.get("override_reason");
        return db(true, c -> {
            assertWritable(c, id);
            JSONObject v = loadVendor(c, id);
            String current = s(v, "lifecycle_status");
            if (current.equals(target)) throw conflict("Vendor is already " + target);
            if ("TERMINATED".equals(target)) {
                if (!"OFFBOARDING".equals(current) && !"PROSPECT".equals(current)) throw conflict("Start offboarding before terminating a vendor");
                if ("OFFBOARDING".equals(current)) {
                    JSONObject ck = (JSONObject) new org.json.simple.parser.JSONParser().parse(s(v, "offboarding_checklist"));
                    boolean all = true;
                    for (String it : OFFBOARDING_ITEMS) if (!Boolean.TRUE.equals(ck.get(it))) all = false;
                    if (!all && isBlank(overrideReason)) throw conflict("Tick every offboarding item, or give an override reason");
                    if (!all) exec(c, "UPDATE vendors SET offboarding_override_reason = ? WHERE id = ?::uuid", overrideReason.trim(), id);
                }
            }
            if ("OFFBOARDING".equals(target)) {
                JSONObject ck = new JSONObject();
                for (String it : OFFBOARDING_ITEMS) ck.put(it, false);
                exec(c, "UPDATE vendors SET offboarding_checklist = ?::jsonb, offboarding_override_reason = NULL WHERE id = ?::uuid", ck.toJSONString(), id);
            }
            exec(c, "UPDATE vendors SET lifecycle_status = ? WHERE id = ?::uuid", target, id);
            log(req, "TPRM_VENDOR_STATUS", "vendor_id", id, "from", current, "to", target);
            if ("OFFBOARDING".equals(target))
                Notification.emit("TPRM_OFFBOARDING", "risks", "Vendor offboarding started", s(v, "name") + " is being offboarded. Work through the checklist.", "risks-tprm.html", UUID.fromString(id));
            return ok();
        });
    }

    @SuppressWarnings("unchecked")
    private JSONObject updateOffboardingItem(HttpServletRequest req, JSONObject in) throws Exception {
        String id = requireText(in, "id");
        String item = requireText(in, "item");
        if (!Arrays.asList(OFFBOARDING_ITEMS).contains(item)) throw bad("Unknown checklist item");
        boolean done = Boolean.TRUE.equals(in.get("done"));
        return db(true, c -> {
            assertWritable(c, id);
            JSONObject v = loadVendor(c, id);
            if (!"OFFBOARDING".equals(s(v, "lifecycle_status"))) throw conflict("The checklist applies only while a vendor is being offboarded");
            exec(c, "UPDATE vendors SET offboarding_checklist = jsonb_set(offboarding_checklist, ARRAY[?], to_jsonb(?::boolean)) WHERE id = ?::uuid", item, done, id);
            log(req, "TPRM_OFFBOARDING_ITEM", "vendor_id", id, "item", item, "done", done);
            return ok();
        });
    }

    @SuppressWarnings("unchecked")
    private JSONObject archiveVendor(HttpServletRequest req, JSONObject in) throws Exception {
        String id = requireText(in, "id");
        UUID user = InputProcessor.getAuthenticatedUserId(req);
        return db(true, c -> {
            JSONObject v = loadVendor(c, id);
            if (s(v, "archived_at") != null) throw conflict("Vendor is already archived");
            exec(c, "UPDATE vendors SET archived_at = CURRENT_TIMESTAMP, archived_by = ?::uuid, lifecycle_status = 'TERMINATED' WHERE id = ?::uuid",
                user == null ? null : user.toString(), id);
            log(req, "TPRM_VENDOR_ARCHIVED", "vendor_id", id, "name", s(v, "name"));
            return ok();
        });
    }

    @SuppressWarnings("unchecked")
    private JSONObject restoreVendor(HttpServletRequest req, JSONObject in) throws Exception {
        String id = requireText(in, "id");
        return db(true, c -> {
            JSONObject v = loadVendor(c, id);
            if (s(v, "archived_at") == null) throw conflict("Vendor is not archived");
            exec(c, "UPDATE vendors SET archived_at = NULL, archived_by = NULL WHERE id = ?::uuid", id);
            log(req, "TPRM_VENDOR_RESTORED", "vendor_id", id, "name", s(v, "name"));
            return ok();
        });
    }

    private JSONObject listOwners() throws Exception {
        return db(false, c -> {
            JSONObject res = ok();
            res.put("users", query(c, "SELECT id::text AS id, username, role FROM users WHERE status = 'ACTIVE' ORDER BY username"));
            return res;
        });
    }

    // ---------- questionnaires ----------

    private JSONObject listQuestionnaires() throws Exception {
        return db(false, c -> {
            JSONObject res = ok();
            res.put("questionnaires", query(c,
                "SELECT q.id::text AS id, q.name, q.version, q.description, q.is_active, q.created_at::text AS created_at, " +
                "(SELECT COUNT(*) FROM tprm_questions x WHERE x.questionnaire_id = q.id) AS question_count, " +
                "(SELECT COUNT(*) FROM tprm_assessments a WHERE a.questionnaire_id = q.id AND a.status = 'COMPLETED') AS completed_assessments " +
                "FROM tprm_questionnaires q ORDER BY q.is_active DESC, q.name, q.version DESC"));
            return res;
        });
    }

    private JSONObject getQuestionnaire(JSONObject in) throws Exception {
        String id = requireText(in, "id");
        return db(false, c -> {
            JSONObject q = one(c, "SELECT id::text AS id, name, version, description, is_active, " +
                "(SELECT COUNT(*) FROM tprm_assessments a WHERE a.questionnaire_id = tprm_questionnaires.id AND a.status = 'COMPLETED') AS completed_assessments " +
                "FROM tprm_questionnaires WHERE id = ?::uuid", id);
            if (q == null) throw notFound("Questionnaire not found");
            q.put("questions", query(c, "SELECT id::text AS id, section, position, text, guidance, answer_type, weight, is_critical, maps_to " +
                "FROM tprm_questions WHERE questionnaire_id = ?::uuid ORDER BY position", id));
            JSONObject res = ok(); res.put("questionnaire", q);
            return res;
        });
    }

    private void assertQuestionnaireEditable(Connection c, String qid) throws Exception {
        JSONObject q = one(c, "SELECT (SELECT COUNT(*) FROM tprm_assessments a WHERE a.questionnaire_id = ?::uuid AND a.status <> 'CANCELLED') AS used FROM tprm_questionnaires WHERE id = ?::uuid", qid, qid);
        if (q == null) throw notFound("Questionnaire not found");
        if (l(q, "used") > 0) throw conflict("This questionnaire has been used in assessments and is locked. Clone it to a new version to change questions.");
    }

    @SuppressWarnings("unchecked")
    private JSONObject saveQuestionnaire(HttpServletRequest req, JSONObject in) throws Exception {
        requireAdmin(req);
        String id = (String) in.get("id");
        String name = isBlank(id) ? requireText(in, "name") : (String) in.get("name");
        return db(true, c -> {
            JSONObject res = ok();
            if (isBlank(id)) {
                String version = isBlank((String) in.get("version")) ? "1.0" : ((String) in.get("version")).trim();
                JSONObject row = one(c, "INSERT INTO tprm_questionnaires (name, version, description, created_by) VALUES (?,?,?,?::uuid) RETURNING id::text AS id",
                    name, version, in.get("description"), InputProcessor.getAuthenticatedUserId(req) == null ? null : InputProcessor.getAuthenticatedUserId(req).toString());
                res.put("id", s(row, "id"));
            } else {
                Map<String, String> f = new LinkedHashMap<>();
                f.put("name", ""); f.put("description", ""); f.put("is_active", "boolean");
                applyFields(c, "tprm_questionnaires", id, in, f);
                res.put("id", id);
            }
            log(req, "TPRM_QUESTIONNAIRE_SAVED", "questionnaire_id", res.get("id"));
            return res;
        });
    }

    @SuppressWarnings("unchecked")
    private JSONObject cloneQuestionnaire(HttpServletRequest req, JSONObject in) throws Exception {
        requireAdmin(req);
        String id = requireText(in, "id");
        String version = requireText(in, "version");
        return db(true, c -> {
            JSONObject src = one(c, "SELECT name, description FROM tprm_questionnaires WHERE id = ?::uuid", id);
            if (src == null) throw notFound("Questionnaire not found");
            if (one(c, "SELECT 1 AS x FROM tprm_questionnaires WHERE name = ? AND version = ?", s(src, "name"), version) != null) throw conflict("That version already exists");
            UUID user = InputProcessor.getAuthenticatedUserId(req);
            JSONObject row = one(c, "INSERT INTO tprm_questionnaires (name, version, description, created_by) VALUES (?,?,?,?::uuid) RETURNING id::text AS id",
                s(src, "name"), version, s(src, "description"), user == null ? null : user.toString());
            String nid = s(row, "id");
            exec(c, "INSERT INTO tprm_questions (questionnaire_id, section, position, text, guidance, answer_type, weight, is_critical, maps_to) " +
                "SELECT ?::uuid, section, position, text, guidance, answer_type, weight, is_critical, maps_to FROM tprm_questions WHERE questionnaire_id = ?::uuid", nid, id);
            log(req, "TPRM_QUESTIONNAIRE_CLONED", "from", id, "to", nid, "version", version);
            JSONObject res = ok(); res.put("id", nid);
            return res;
        });
    }

    @SuppressWarnings("unchecked")
    private JSONObject saveQuestion(HttpServletRequest req, JSONObject in) throws Exception {
        requireAdmin(req);
        String id = (String) in.get("id");
        String qid = (String) in.get("questionnaire_id");
        String type = enumVal(in, "answer_type", "YES_NO", "YES_NO_NA", "TEXT");
        Object w = in.get("weight");
        if (w instanceof Number && (((Number) w).longValue() < 0 || ((Number) w).longValue() > 10)) throw bad("weight must be between 0 and 10");
        return db(true, c -> {
            JSONObject res = ok();
            if (isBlank(id)) {
                if (isBlank(qid)) throw bad("questionnaire_id required");
                assertQuestionnaireEditable(c, qid);
                String text = requireText(in, "text"), section = requireText(in, "section");
                JSONObject pos = one(c, "SELECT COALESCE(MAX(position), 0) + 1 AS p FROM tprm_questions WHERE questionnaire_id = ?::uuid", qid);
                JSONObject row = one(c, "INSERT INTO tprm_questions (questionnaire_id, section, position, text, guidance, answer_type, weight, is_critical, maps_to) " +
                    "VALUES (?::uuid,?,?,?,?,?,?,?,?) RETURNING id::text AS id", qid, section,
                    in.get("position") instanceof Number ? ((Number) in.get("position")).intValue() : (int) l(pos, "p"), text, in.get("guidance"),
                    type == null ? "YES_NO" : type, w instanceof Number ? ((Number) w).intValue() : 1, Boolean.TRUE.equals(in.get("is_critical")), in.get("maps_to"));
                res.put("id", s(row, "id"));
            } else {
                JSONObject q = one(c, "SELECT questionnaire_id::text AS qid FROM tprm_questions WHERE id = ?::uuid", id);
                if (q == null) throw notFound("Question not found");
                assertQuestionnaireEditable(c, s(q, "qid"));
                Map<String, String> f = new LinkedHashMap<>();
                f.put("section", ""); f.put("position", "int"); f.put("text", ""); f.put("guidance", ""); f.put("answer_type", "");
                f.put("weight", "int"); f.put("is_critical", "boolean"); f.put("maps_to", "");
                applyFields(c, "tprm_questions", id, in, f);
                res.put("id", id);
            }
            log(req, "TPRM_QUESTION_SAVED", "question_id", res.get("id"));
            return res;
        });
    }

    private JSONObject deleteQuestion(HttpServletRequest req, JSONObject in) throws Exception {
        String id = requireText(in, "id");
        return db(true, c -> {
            JSONObject q = one(c, "SELECT questionnaire_id::text AS qid FROM tprm_questions WHERE id = ?::uuid", id);
            if (q == null) throw notFound("Question not found");
            assertQuestionnaireEditable(c, s(q, "qid"));
            exec(c, "DELETE FROM tprm_questions WHERE id = ?::uuid", id);
            log(req, "TPRM_QUESTION_DELETED", "question_id", id);
            return ok();
        });
    }

    // ---------- assessments ----------

    private JSONObject listAssessments(JSONObject in) throws Exception {
        String vendorId = (String) in.get("vendor_id"), status = enumVal(in, "status", "DRAFT", "SENT", "IN_REVIEW", "COMPLETED", "CANCELLED");
        StringBuilder where = new StringBuilder(" WHERE 1=1"); List<Object> args = new ArrayList<>();
        if (!isBlank(vendorId)) { where.append(" AND a.vendor_id = ?::uuid"); args.add(vendorId); }
        if (status != null) { where.append(" AND a.status = ?"); args.add(status); }
        return db(false, c -> {
            JSONObject res = ok();
            res.put("assessments", query(c,
                "SELECT a.id::text AS id, a.vendor_id::text AS vendor_id, v.name AS vendor_name, (v.archived_at IS NOT NULL) AS vendor_archived, " +
                "a.questionnaire_id::text AS questionnaire_id, qn.name || ' v' || qn.version AS questionnaire_name, a.assessment_type, a.status, " +
                "u.username AS assessor_name, a.inherent_risk, a.score_pct, a.residual_risk, a.conclusion, a.summary, a.due_date::text AS due_date, " +
                "a.portal_status, a.portal_due_date::text AS portal_due_date, " +
                "a.completed_at::text AS completed_at, a.created_at::text AS created_at, " +
                "(SELECT COUNT(*) FROM tprm_answers x WHERE x.assessment_id = a.id AND (x.answer IS NOT NULL OR x.text_answer IS NOT NULL)) AS answered, " +
                "(SELECT COUNT(*) FROM tprm_questions x WHERE x.questionnaire_id = a.questionnaire_id) AS total_questions " +
                "FROM tprm_assessments a JOIN vendors v ON v.id = a.vendor_id JOIN tprm_questionnaires qn ON qn.id = a.questionnaire_id " +
                "LEFT JOIN users u ON u.id = a.assessor_id" + where + " ORDER BY a.created_at DESC LIMIT 500", args.toArray()));
            return res;
        });
    }

    @SuppressWarnings("unchecked")
    private JSONObject startAssessment(HttpServletRequest req, JSONObject in) throws Exception {
        String vendorId = requireText(in, "vendor_id");
        String type = enumVal(in, "assessment_type", "ONBOARDING", "PERIODIC", "EVENT_DRIVEN");
        String due = (String) in.get("due_date");
        return db(true, c -> {
            assertWritable(c, vendorId);
            if (one(c, "SELECT 1 AS x FROM tprm_assessments WHERE vendor_id = ?::uuid AND status IN ('DRAFT','SENT','IN_REVIEW')", vendorId) != null)
                throw conflict("This vendor already has an assessment in progress. Complete or cancel it first.");
            String qid = (String) in.get("questionnaire_id");
            if (isBlank(qid)) {
                JSONObject q = one(c, "SELECT id::text AS id FROM tprm_questionnaires WHERE is_active = TRUE ORDER BY created_at DESC LIMIT 1");
                if (q == null) throw conflict("No active questionnaire is available");
                qid = s(q, "id");
            } else {
                JSONObject q = one(c, "SELECT is_active FROM tprm_questionnaires WHERE id = ?::uuid", qid);
                if (q == null) throw notFound("Questionnaire not found");
                if (!b(q, "is_active")) throw conflict("That questionnaire is inactive");
            }
            JSONObject v = loadVendor(c, vendorId);
            String t = type != null ? type : (s(v, "last_assessed_at") == null ? "ONBOARDING" : "PERIODIC");
            UUID user = InputProcessor.getAuthenticatedUserId(req);
            JSONObject row = one(c, "INSERT INTO tprm_assessments (vendor_id, questionnaire_id, assessment_type, assessor_id, due_date) " +
                "VALUES (?::uuid,?::uuid,?,?::uuid,?::date) RETURNING id::text AS id", vendorId, qid, t, user == null ? null : user.toString(), isBlank(due) ? null : due);
            log(req, "TPRM_ASSESSMENT_STARTED", "assessment_id", s(row, "id"), "vendor_id", vendorId, "type", t);
            JSONObject res = ok(); res.put("id", s(row, "id"));
            return res;
        });
    }

    /** Score from the answers so far: Yes weight over the weight of applicable (non-N/A) scored questions. */
    private JSONObject scoreOf(JSONArray questions) {
        long yes = 0, applicable = 0, unanswered = 0, scored = 0;
        for (Object o : questions) {
            JSONObject q = (JSONObject) o;
            if ("TEXT".equals(s(q, "answer_type"))) continue;
            scored++;
            String a = s(q, "answer");
            if (a == null) { unanswered++; continue; }
            if ("NA".equals(a)) continue;
            long w = l(q, "weight");
            applicable += w;
            if ("YES".equals(a)) yes += w;
        }
        JSONObject r = new JSONObject();
        r.put("yes_weight", yes); r.put("applicable_weight", applicable); r.put("unanswered", unanswered); r.put("scored_questions", scored);
        r.put("score_pct", applicable == 0 ? 100.0 : Math.round(10000.0 * yes / applicable) / 100.0);
        return r;
    }

    private JSONArray assessmentQuestions(Connection c, String assessmentId) throws Exception {
        return query(c,
            "SELECT q.id::text AS id, q.section, q.position, q.text, q.guidance, q.answer_type, q.weight, q.is_critical, q.maps_to, " +
            "x.answer, x.text_answer, x.comment, x.evidence_ref, x.source, x.original_answer, x.original_comment, COALESCE(x.returned, FALSE) AS returned, x.return_note FROM tprm_assessments a " +
            "JOIN tprm_questions q ON q.questionnaire_id = a.questionnaire_id LEFT JOIN tprm_answers x ON x.assessment_id = a.id AND x.question_id = q.id " +
            "WHERE a.id = ?::uuid ORDER BY q.position", assessmentId);
    }

    @SuppressWarnings("unchecked")
    private JSONObject getAssessment(JSONObject in) throws Exception {
        String id = requireText(in, "id");
        return db(false, c -> {
            JSONObject a = one(c,
                "SELECT a.id::text AS id, a.vendor_id::text AS vendor_id, v.name AS vendor_name, (v.archived_at IS NOT NULL) AS vendor_archived, " +
                "qn.name || ' v' || qn.version AS questionnaire_name, a.assessment_type, a.status, u.username AS assessor_name, a.inherent_risk, " +
                "a.score_pct, a.residual_risk, a.conclusion, a.summary, a.due_date::text AS due_date, a.completed_at::text AS completed_at, " +
                "a.portal_status, a.sent_at::text AS sent_at, a.portal_due_date::text AS portal_due_date, a.submitted_at::text AS submitted_at, " +
                "sb.username AS submitted_by_name, a.declaration_accepted " +
                "FROM tprm_assessments a JOIN vendors v ON v.id = a.vendor_id JOIN tprm_questionnaires qn ON qn.id = a.questionnaire_id " +
                "LEFT JOIN users u ON u.id = a.assessor_id LEFT JOIN users sb ON sb.id = a.submitted_by WHERE a.id = ?::uuid", id);
            if (a == null) throw notFound("Assessment not found");
            JSONArray qs = assessmentQuestions(c, id);
            a.put("questions", qs);
            a.put("progress", scoreOf(qs));
            a.put("findings", query(c, "SELECT id::text AS id, title, severity, status FROM tprm_findings WHERE assessment_id = ?::uuid ORDER BY created_at", id));
            a.put("vendor_evidence", query(c, "SELECT d.id::text AS id, d.doc_type, d.file_name, d.file_size, d.valid_until::text AS valid_until, d.question_id::text AS question_id, " +
                "d.sha256_checksum, d.uploaded_at::text AS uploaded_at, u.username AS uploaded_by_name FROM tprm_documents d LEFT JOIN users u ON u.id = d.uploaded_by " +
                "WHERE d.assessment_id = ?::uuid AND d.uploaded_by_vendor = TRUE ORDER BY d.uploaded_at", id));
            JSONObject res = ok(); res.put("assessment", a);
            return res;
        });
    }

    private JSONObject assessmentForUpdate(Connection c, String id) throws Exception {
        JSONObject a = one(c, "SELECT id::text AS id, vendor_id::text AS vendor_id, questionnaire_id::text AS questionnaire_id, status FROM tprm_assessments WHERE id = ?::uuid FOR UPDATE", id);
        if (a == null) throw notFound("Assessment not found");
        return a;
    }

    @SuppressWarnings("unchecked")
    private JSONObject saveAnswer(HttpServletRequest req, JSONObject in) throws Exception {
        String aid = requireText(in, "assessment_id"), qid = requireText(in, "question_id");
        String answer = enumVal(in, "answer", "YES", "NO", "NA");
        return db(true, c -> {
            JSONObject a = assessmentForUpdate(c, aid);
            String st = s(a, "status");
            if ("COMPLETED".equals(st) || "CANCELLED".equals(st)) throw conflict("A " + st.toLowerCase() + " assessment is read-only");
            assertWritable(c, s(a, "vendor_id"));
            assertNotWithVendor(c, aid);
            JSONObject q = one(c, "SELECT answer_type FROM tprm_questions WHERE id = ?::uuid AND questionnaire_id = ?::uuid", qid, s(a, "questionnaire_id"));
            if (q == null) throw notFound("Question does not belong to this assessment");
            String type = s(q, "answer_type");
            if ("TEXT".equals(type) && answer != null) throw bad("This question takes a text answer");
            if ("YES_NO".equals(type) && "NA".equals(answer)) throw bad("N/A is not allowed for this question");
            String text = (String) in.get("text_answer");
            String newComment = isBlank((String) in.get("comment")) ? null : ((String) in.get("comment")).trim();
            // If the assessor changes what the vendor said, keep the vendor's version beside it.
            JSONObject prev = one(c, "SELECT answer, comment, source, original_answer FROM tprm_answers WHERE assessment_id = ?::uuid AND question_id = ?::uuid", aid, qid);
            boolean overrides = prev != null && "VENDOR".equals(s(prev, "source")) && prev.get("original_answer") == null
                && (!java.util.Objects.equals(s(prev, "answer"), answer) || !java.util.Objects.equals(s(prev, "comment"), newComment));
            exec(c, "INSERT INTO tprm_answers (assessment_id, question_id, answer, text_answer, comment, evidence_ref) VALUES (?::uuid,?::uuid,?,?,?,?) " +
                "ON CONFLICT (assessment_id, question_id) DO UPDATE SET answer = EXCLUDED.answer, text_answer = EXCLUDED.text_answer, " +
                "comment = EXCLUDED.comment, evidence_ref = EXCLUDED.evidence_ref, answered_at = CURRENT_TIMESTAMP",
                aid, qid, answer, isBlank(text) ? null : text.trim(), newComment,
                isBlank((String) in.get("evidence_ref")) ? null : ((String) in.get("evidence_ref")).trim());
            if (overrides) exec(c, "UPDATE tprm_answers SET source = 'ASSESSOR', original_answer = ?, original_comment = ? WHERE assessment_id = ?::uuid AND question_id = ?::uuid",
                s(prev, "answer"), s(prev, "comment"), aid, qid);
            if ("DRAFT".equals(st) || "SENT".equals(st)) {
                exec(c, "UPDATE tprm_assessments SET status = 'IN_REVIEW' WHERE id = ?::uuid", aid);
                exec(c, "UPDATE vendors SET security_questionnaire_status = 'RECEIVED' WHERE id = ?::uuid AND COALESCE(security_questionnaire_status,'') <> 'EVALUATED'", s(a, "vendor_id"));
            }
            JSONObject res = ok(); res.put("progress", scoreOf(assessmentQuestions(c, aid)));
            return res;
        });
    }

    private JSONObject markSent(HttpServletRequest req, JSONObject in) throws Exception {
        String aid = requireText(in, "id");
        return db(true, c -> {
            JSONObject a = assessmentForUpdate(c, aid);
            if (!"DRAFT".equals(s(a, "status"))) throw conflict("Only a draft assessment can be marked as sent");
            assertNotWithVendor(c, aid);
            assertWritable(c, s(a, "vendor_id"));
            exec(c, "UPDATE tprm_assessments SET status = 'SENT' WHERE id = ?::uuid", aid);
            exec(c, "UPDATE vendors SET security_questionnaire_status = 'SENT' WHERE id = ?::uuid", s(a, "vendor_id"));
            log(req, "TPRM_ASSESSMENT_SENT", "assessment_id", aid);
            return ok();
        });
    }

    private JSONObject cancelAssessment(HttpServletRequest req, JSONObject in) throws Exception {
        String aid = requireText(in, "id");
        return db(true, c -> {
            JSONObject a = assessmentForUpdate(c, aid);
            if ("COMPLETED".equals(s(a, "status")) || "CANCELLED".equals(s(a, "status"))) throw conflict("Assessment is already " + s(a, "status").toLowerCase());
            assertWritable(c, s(a, "vendor_id"));
            exec(c, "UPDATE tprm_assessments SET status = 'CANCELLED', portal_status = 'NOT_SENT' WHERE id = ?::uuid", aid);
            log(req, "TPRM_ASSESSMENT_CANCELLED", "assessment_id", aid);
            return ok();
        });
    }

    @SuppressWarnings("unchecked")
    private JSONObject completeAssessment(HttpServletRequest req, JSONObject in) throws Exception {
        String aid = requireText(in, "id");
        String conclusion = enumVal(in, "conclusion", "APPROVED", "APPROVED_WITH_CONDITIONS", "REJECTED");
        if (conclusion == null) throw bad("conclusion required (APPROVED, APPROVED_WITH_CONDITIONS or REJECTED)");
        String summary = (String) in.get("summary");
        return db(true, c -> {
            JSONObject a = assessmentForUpdate(c, aid);
            if (!"DRAFT".equals(s(a, "status")) && !"SENT".equals(s(a, "status")) && !"IN_REVIEW".equals(s(a, "status")))
                throw conflict("Assessment is already " + s(a, "status").toLowerCase());
            String vid = s(a, "vendor_id");
            assertWritable(c, vid);
            assertNotWithVendor(c, aid);
            JSONArray qs = assessmentQuestions(c, aid);
            JSONObject sc = scoreOf(qs);
            if (l(sc, "unanswered") > 0) throw conflict(l(sc, "unanswered") + " question(s) are still unanswered");
            double pct = ((Number) sc.get("score_pct")).doubleValue();

            JSONObject v = loadVendor(c, vid);
            Risk r = assess(v);
            String residual = pct >= 80 ? stepTier(r.effective, -1) : pct >= 50 ? r.effective : stepTier(r.effective, 1);
            long months = v.get("review_cycle_months") != null ? l(v, "review_cycle_months") : defaultCycleMonths(r.effective);

            exec(c, "UPDATE tprm_assessments SET status = 'COMPLETED', portal_status = CASE WHEN portal_status = 'SUBMITTED' THEN 'COMPLETED' ELSE portal_status END, " +
                "score_pct = ?, residual_risk = ?, inherent_risk = ?, conclusion = ?, summary = ?, completed_at = CURRENT_TIMESTAMP WHERE id = ?::uuid",
                pct, residual, r.effective, conclusion, isBlank(summary) ? null : summary.trim(), aid);

            // A "No" on a critical question raises a finding.
            String owner = s(v, "business_owner_id");
            if (owner == null) { UUID u = InputProcessor.getAuthenticatedUserId(req); owner = u == null ? null : u.toString(); }
            long raised = 0;
            for (Object o : qs) {
                JSONObject q = (JSONObject) o;
                if (b(q, "is_critical") && "NO".equals(s(q, "answer"))) {
                    String text = s(q, "text");
                    String title = "Critical control gap: " + (text.length() > 200 ? text.substring(0, 197) + "..." : text);
                    String desc = "Raised from assessment. Question: " + text + (s(q, "comment") == null ? "" : "\nAssessor comment: " + s(q, "comment")) +
                        (s(q, "maps_to") == null ? "" : "\nReference: " + s(q, "maps_to"));
                    exec(c, "INSERT INTO tprm_findings (vendor_id, assessment_id, title, description, severity, source, owner_id, due_date) " +
                        "VALUES (?::uuid,?::uuid,?,?,'HIGH','ASSESSMENT',?::uuid, CURRENT_DATE + 30)", vid, aid, title, desc, owner);
                    raised++;
                }
            }
            exec(c, "UPDATE vendors SET last_assessed_at = CURRENT_TIMESTAMP, residual_risk = ?, inherent_risk = ?, inherent_score = ?, " +
                "next_review_due = (CURRENT_DATE + (? || ' months')::interval)::date, security_questionnaire_status = 'EVALUATED' WHERE id = ?::uuid",
                residual, r.effective, r.score, String.valueOf(months), vid);
            log(req, "TPRM_ASSESSMENT_COMPLETED", "assessment_id", aid, "vendor_id", vid, "score_pct", pct, "residual_risk", residual, "conclusion", conclusion, "findings_raised", raised);
            Notification.emit("TPRM_ASSESSMENT_DONE", "risks", "Vendor assessment completed",
                s(v, "name") + ": " + conclusion.replace('_', ' ').toLowerCase() + ", score " + pct + "%, residual risk " + residual + ".", "risks-tprm.html", UUID.fromString(aid));
            JSONObject res = ok();
            res.put("score_pct", pct); res.put("residual_risk", residual); res.put("inherent_risk", r.effective); res.put("findings_raised", raised);
            res.put("next_review_due", one(c, "SELECT next_review_due::text AS d FROM vendors WHERE id = ?::uuid", vid).get("d"));
            return res;
        });
    }

    // ---------- findings ----------

    private JSONObject listFindings(JSONObject in) throws Exception {
        String vendorId = (String) in.get("vendor_id");
        String status = enumVal(in, "status", "OPEN", "IN_PROGRESS", "REMEDIATED", "ACCEPTED", "CLOSED");
        String severity = enumVal(in, "severity", SEVERITIES);
        boolean openOnly = Boolean.TRUE.equals(in.get("open_only")), overdue = Boolean.TRUE.equals(in.get("overdue_only"));
        boolean archived = Boolean.TRUE.equals(in.get("show_archived"));
        StringBuilder where = new StringBuilder(" WHERE 1=1"); List<Object> args = new ArrayList<>();
        if (!isBlank(vendorId)) { where.append(" AND f.vendor_id = ?::uuid"); args.add(vendorId); }
        else if (!archived) where.append(" AND v.archived_at IS NULL");
        if (status != null) { where.append(" AND f.status = ?"); args.add(status); }
        if (severity != null) { where.append(" AND f.severity = ?"); args.add(severity); }
        if (openOnly) where.append(" AND f.status IN ('OPEN','IN_PROGRESS')");
        if (overdue) where.append(" AND f.status IN ('OPEN','IN_PROGRESS') AND f.due_date < CURRENT_DATE");
        return db(false, c -> {
            JSONObject res = ok();
            res.put("findings", query(c,
                "SELECT f.id::text AS id, f.vendor_id::text AS vendor_id, v.name AS vendor_name, (v.archived_at IS NOT NULL) AS vendor_archived, " +
                "f.assessment_id::text AS assessment_id, f.title, f.description, f.severity, f.status, f.source, f.owner_id::text AS owner_id, " +
                "o.username AS owner_name, f.due_date::text AS due_date, f.remediation_plan, f.acceptance_reason, ab.username AS accepted_by_name, " +
                "f.linked_risk_id::text AS linked_risk_id, rk.risk_code, f.closed_at::text AS closed_at, f.created_at::text AS created_at, " +
                "(f.status IN ('OPEN','IN_PROGRESS') AND f.due_date < CURRENT_DATE) AS overdue " +
                "FROM tprm_findings f JOIN vendors v ON v.id = f.vendor_id LEFT JOIN users o ON o.id = f.owner_id LEFT JOIN users ab ON ab.id = f.accepted_by " +
                "LEFT JOIN risks rk ON rk.id = f.linked_risk_id" + where +
                " ORDER BY CASE f.status WHEN 'OPEN' THEN 1 WHEN 'IN_PROGRESS' THEN 2 WHEN 'REMEDIATED' THEN 3 WHEN 'ACCEPTED' THEN 4 ELSE 5 END, " +
                "CASE f.severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END, f.due_date NULLS LAST LIMIT 1000", args.toArray()));
            return res;
        });
    }

    private static final Map<String, String> FINDING_FIELDS = new LinkedHashMap<>();
    static {
        FINDING_FIELDS.put("title", ""); FINDING_FIELDS.put("description", ""); FINDING_FIELDS.put("severity", "");
        FINDING_FIELDS.put("owner_id", "uuid"); FINDING_FIELDS.put("due_date", "date"); FINDING_FIELDS.put("remediation_plan", "");
    }

    @SuppressWarnings("unchecked")
    private JSONObject addFinding(HttpServletRequest req, JSONObject in) throws Exception {
        String vid = requireText(in, "vendor_id"), title = requireText(in, "title");
        String sev = enumVal(in, "severity", SEVERITIES);
        return db(true, c -> {
            assertWritable(c, vid);
            String aid = (String) in.get("assessment_id");
            JSONObject row = one(c, "INSERT INTO tprm_findings (vendor_id, assessment_id, title, description, severity, owner_id, due_date, remediation_plan) " +
                "VALUES (?::uuid,?::uuid,?,?,?,?::uuid,?::date,?) RETURNING id::text AS id", vid, isBlank(aid) ? null : aid, title, in.get("description"),
                sev == null ? "MEDIUM" : sev, isBlank((String) in.get("owner_id")) ? null : in.get("owner_id"), isBlank((String) in.get("due_date")) ? null : in.get("due_date"), in.get("remediation_plan"));
            log(req, "TPRM_FINDING_ADDED", "finding_id", s(row, "id"), "vendor_id", vid, "severity", sev == null ? "MEDIUM" : sev);
            JSONObject res = ok(); res.put("id", s(row, "id"));
            return res;
        });
    }

    private static final Map<String, String[]> FINDING_FLOW = new HashMap<>();
    static {
        FINDING_FLOW.put("OPEN", new String[]{"IN_PROGRESS", "CLOSED"});
        FINDING_FLOW.put("IN_PROGRESS", new String[]{"OPEN", "REMEDIATED", "CLOSED"});
        FINDING_FLOW.put("REMEDIATED", new String[]{"IN_PROGRESS", "CLOSED"});
        FINDING_FLOW.put("ACCEPTED", new String[]{"OPEN", "CLOSED"});
        FINDING_FLOW.put("CLOSED", new String[]{"OPEN"});
    }

    @SuppressWarnings("unchecked")
    private JSONObject updateFinding(HttpServletRequest req, JSONObject in) throws Exception {
        String id = requireText(in, "id");
        enumVal(in, "severity", SEVERITIES);
        String target = enumVal(in, "status", "OPEN", "IN_PROGRESS", "REMEDIATED", "ACCEPTED", "CLOSED");
        if ("ACCEPTED".equals(target)) throw bad("Use accept_finding to accept a finding (a reason is required)");
        return db(true, c -> {
            JSONObject f = one(c, "SELECT vendor_id::text AS vendor_id, status FROM tprm_findings WHERE id = ?::uuid FOR UPDATE", id);
            if (f == null) throw notFound("Finding not found");
            assertWritable(c, s(f, "vendor_id"));
            applyFields(c, "tprm_findings", id, in, FINDING_FIELDS);
            if (target != null && !target.equals(s(f, "status"))) {
                if (!Arrays.asList(FINDING_FLOW.get(s(f, "status"))).contains(target)) throw conflict("Cannot move a finding from " + s(f, "status") + " to " + target);
                exec(c, "UPDATE tprm_findings SET status = ?, closed_at = CASE WHEN ? = 'CLOSED' THEN CURRENT_TIMESTAMP ELSE NULL END, " +
                    "accepted_by = CASE WHEN ? = 'ACCEPTED' THEN accepted_by ELSE NULL END, acceptance_reason = CASE WHEN ? = 'ACCEPTED' THEN acceptance_reason ELSE NULL END " +
                    "WHERE id = ?::uuid", target, target, target, target, id);
            }
            log(req, "TPRM_FINDING_UPDATED", "finding_id", id, "status", target == null ? s(f, "status") : target);
            return ok();
        });
    }

    private JSONObject acceptFinding(HttpServletRequest req, JSONObject in) throws Exception {
        String id = requireText(in, "id"), reason = requireText(in, "reason");
        String role = InputProcessor.getRole(req);
        if (!"ADMIN".equals(role) && !"GRC_OFFICER".equals(role)) throw new HttpError(403, "Forbidden", "Only an administrator or GRC officer can accept a finding.");
        UUID user = InputProcessor.getAuthenticatedUserId(req);
        return db(true, c -> {
            JSONObject f = one(c, "SELECT vendor_id::text AS vendor_id, status FROM tprm_findings WHERE id = ?::uuid FOR UPDATE", id);
            if (f == null) throw notFound("Finding not found");
            assertWritable(c, s(f, "vendor_id"));
            if (!"OPEN".equals(s(f, "status")) && !"IN_PROGRESS".equals(s(f, "status"))) throw conflict("Only an open finding can be accepted");
            exec(c, "UPDATE tprm_findings SET status = 'ACCEPTED', accepted_by = ?::uuid, acceptance_reason = ?, closed_at = NULL WHERE id = ?::uuid",
                user == null ? null : user.toString(), reason, id);
            log(req, "TPRM_FINDING_ACCEPTED", "finding_id", id, "reason", reason);
            return ok();
        });
    }

    @SuppressWarnings("unchecked")
    private JSONObject escalateFinding(HttpServletRequest req, JSONObject in) throws Exception {
        String id = requireText(in, "id");
        UUID user = InputProcessor.getAuthenticatedUserId(req);
        return db(true, c -> {
            // The row lock makes a double click create one risk, not two.
            JSONObject f = one(c, "SELECT f.vendor_id::text AS vendor_id, f.title, f.description, f.severity, f.owner_id::text AS owner_id, f.linked_risk_id::text AS linked_risk_id, v.name AS vendor_name " +
                "FROM tprm_findings f JOIN vendors v ON v.id = f.vendor_id WHERE f.id = ?::uuid FOR UPDATE OF f", id);
            if (f == null) throw notFound("Finding not found");
            assertWritable(c, s(f, "vendor_id"));
            if (s(f, "linked_risk_id") != null) {
                JSONObject rk = one(c, "SELECT risk_code FROM risks WHERE id = ?::uuid", s(f, "linked_risk_id"));
                throw conflict("This finding is already escalated" + (rk == null ? "" : " as " + s(rk, "risk_code")));
            }
            String sev = s(f, "severity");
            int impact = "CRITICAL".equals(sev) ? 5 : "HIGH".equals(sev) ? 4 : "MEDIUM".equals(sev) ? 3 : 2;
            int likelihood = "CRITICAL".equals(sev) ? 4 : "HIGH".equals(sev) ? 3 : "MEDIUM".equals(sev) ? 3 : 2;
            String owner = s(f, "owner_id") != null ? s(f, "owner_id") : (user == null ? null : user.toString());
            JSONObject rk = one(c, "INSERT INTO risks (title, description, category, inherent_impact, inherent_likelihood, treatment_strategy, owner_id) " +
                "VALUES (?,?,'Third Party',?,?,'MITIGATE',?::uuid) RETURNING id::text AS id, risk_code",
                s(f, "vendor_name") + ": " + s(f, "title"), (s(f, "description") == null ? "" : s(f, "description") + "\n") + "Escalated from a third-party risk finding (" + sev + ").",
                impact, likelihood, owner);
            exec(c, "UPDATE tprm_findings SET linked_risk_id = ?::uuid WHERE id = ?::uuid", s(rk, "id"), id);
            log(req, "TPRM_FINDING_ESCALATED", "finding_id", id, "risk_id", s(rk, "id"), "risk_code", s(rk, "risk_code"));
            Notification.emit("TPRM_FINDING_ESCALATED", "risks", "Vendor finding escalated to the Risk Register",
                s(rk, "risk_code") + ": " + s(f, "vendor_name") + " - " + s(f, "title"), "risks-register.html", UUID.fromString(s(rk, "id")));
            JSONObject res = ok(); res.put("risk_id", s(rk, "id")); res.put("risk_code", s(rk, "risk_code"));
            return res;
        });
    }

    // ---------- contracts ----------

    private static final Map<String, String> CONTRACT_FIELDS = new LinkedHashMap<>();
    static {
        CONTRACT_FIELDS.put("contract_type", ""); CONTRACT_FIELDS.put("title", ""); CONTRACT_FIELDS.put("start_date", "date"); CONTRACT_FIELDS.put("end_date", "date");
        CONTRACT_FIELDS.put("auto_renews", "boolean"); CONTRACT_FIELDS.put("notice_period_days", "int"); CONTRACT_FIELDS.put("value_inr", "numeric");
        CONTRACT_FIELDS.put("has_security_clauses", "boolean"); CONTRACT_FIELDS.put("has_breach_notification_clause", "boolean");
        CONTRACT_FIELDS.put("has_audit_right", "boolean"); CONTRACT_FIELDS.put("has_data_return_deletion_clause", "boolean"); CONTRACT_FIELDS.put("notes", "");
    }

    private void validateContract(JSONObject in) {
        enumVal(in, "contract_type", "MSA", "SOW", "DPA", "NDA", "SLA", "OTHER");
        Object n = in.get("notice_period_days");
        if (n instanceof Number && ((Number) n).longValue() < 0) throw bad("notice_period_days cannot be negative");
        String sd = (String) in.get("start_date"), ed = (String) in.get("end_date");
        if (!isBlank(sd) && !isBlank(ed) && ed.compareTo(sd) < 0) throw bad("end_date is before start_date");
    }

    private JSONObject listContracts(JSONObject in) throws Exception {
        String vid = requireText(in, "vendor_id");
        return db(false, c -> {
            JSONObject res = ok();
            res.put("contracts", query(c,
                "SELECT id::text AS id, contract_type, title, start_date::text AS start_date, end_date::text AS end_date, auto_renews, notice_period_days, value_inr, " +
                "has_security_clauses, has_breach_notification_clause, has_audit_right, has_data_return_deletion_clause, notes, " +
                "(end_date - notice_period_days)::text AS renewal_due, (end_date IS NOT NULL AND end_date < CURRENT_DATE) AS expired, " +
                "(end_date IS NOT NULL AND end_date >= CURRENT_DATE AND (end_date - notice_period_days) < CURRENT_DATE + 60) AS renewal_soon " +
                "FROM tprm_contracts WHERE vendor_id = ?::uuid ORDER BY end_date NULLS LAST, title", vid));
            return res;
        });
    }

    private JSONObject addContract(HttpServletRequest req, JSONObject in) throws Exception {
        String vid = requireText(in, "vendor_id"); requireText(in, "title");
        if (enumVal(in, "contract_type", "MSA", "SOW", "DPA", "NDA", "SLA", "OTHER") == null) throw bad("contract_type required");
        validateContract(in);
        return db(true, c -> {
            assertWritable(c, vid);
            JSONObject row = one(c, "INSERT INTO tprm_contracts (vendor_id, contract_type, title) VALUES (?::uuid,?,?) RETURNING id::text AS id", vid, enumVal(in, "contract_type", "MSA", "SOW", "DPA", "NDA", "SLA", "OTHER"), requireText(in, "title"));
            applyFields(c, "tprm_contracts", s(row, "id"), in, CONTRACT_FIELDS);
            log(req, "TPRM_CONTRACT_ADDED", "contract_id", s(row, "id"), "vendor_id", vid);
            JSONObject res = ok(); res.put("id", s(row, "id"));
            return res;
        });
    }

    private JSONObject updateContract(HttpServletRequest req, JSONObject in) throws Exception {
        String id = requireText(in, "id");
        validateContract(in);
        return db(true, c -> {
            JSONObject k = one(c, "SELECT vendor_id::text AS vendor_id FROM tprm_contracts WHERE id = ?::uuid", id);
            if (k == null) throw notFound("Contract not found");
            assertWritable(c, s(k, "vendor_id"));
            applyFields(c, "tprm_contracts", id, in, CONTRACT_FIELDS);
            log(req, "TPRM_CONTRACT_UPDATED", "contract_id", id);
            return ok();
        });
    }

    private JSONObject deleteContract(HttpServletRequest req, JSONObject in) throws Exception {
        String id = requireText(in, "id");
        return db(true, c -> {
            JSONObject k = one(c, "SELECT vendor_id::text AS vendor_id FROM tprm_contracts WHERE id = ?::uuid", id);
            if (k == null) throw notFound("Contract not found");
            assertWritable(c, s(k, "vendor_id"));
            exec(c, "DELETE FROM tprm_contracts WHERE id = ?::uuid", id);
            log(req, "TPRM_CONTRACT_DELETED", "contract_id", id);
            return ok();
        });
    }

    // ---------- documents ----------

    private static final Set<String> DOC_EXTENSIONS = new HashSet<>(Arrays.asList(
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".png", ".jpg", ".jpeg", ".zip", ".txt", ".csv"));
    private static final int MAX_DOC_BYTES = 10 * 1024 * 1024;

    private JSONObject listDocuments(JSONObject in) throws Exception {
        String vid = requireText(in, "vendor_id");
        return db(false, c -> {
            JSONObject res = ok();
            res.put("documents", query(c,
                "SELECT d.id::text AS id, d.doc_type, d.file_name, d.file_size, d.sha256_checksum, d.valid_until::text AS valid_until, d.contract_id::text AS contract_id, " +
                "d.assessment_id::text AS assessment_id, d.question_id::text AS question_id, d.uploaded_by_vendor, d.uploaded_at::text AS uploaded_at, u.username AS uploaded_by_name, " +
                "(d.valid_until IS NOT NULL AND d.valid_until < CURRENT_DATE) AS expired, " +
                "(d.valid_until IS NOT NULL AND d.valid_until >= CURRENT_DATE AND d.valid_until < CURRENT_DATE + 60) AS expiring_soon " +
                "FROM tprm_documents d LEFT JOIN users u ON u.id = d.uploaded_by WHERE d.vendor_id = ?::uuid ORDER BY d.uploaded_at DESC", vid));
            return res;
        });
    }

    @SuppressWarnings("unchecked")
    private JSONObject uploadDocument(HttpServletRequest req, JSONObject in) throws Exception {
        String vid = requireText(in, "vendor_id"), fileName = requireText(in, "file_name"), fileData = requireText(in, "file_data");
        String docType = enumVal(in, "doc_type", "CERTIFICATE", "SOC_REPORT", "PENTEST_SUMMARY", "POLICY", "CONTRACT", "OTHER");
        String valid = (String) in.get("valid_until");
        String safeName = new File(fileName).getName();
        int dot = safeName.lastIndexOf('.');
        String ext = dot >= 0 ? safeName.substring(dot).toLowerCase() : "";
        if (!DOC_EXTENSIONS.contains(ext)) throw bad("File type not allowed: " + ext);
        String raw = fileData.contains(",") ? fileData.substring(fileData.indexOf(',') + 1) : fileData;
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(raw.trim()); } catch (Exception e) { throw bad("Invalid base64 content"); }
        if (bytes.length > MAX_DOC_BYTES) throw bad("File exceeds the " + (MAX_DOC_BYTES / (1024 * 1024)) + "MB size limit");
        if (bytes.length == 0) throw bad("File is empty");
        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(bytes)) sb.append(String.format("%02x", x));
        String checksum = sb.toString();
        UUID user = InputProcessor.getAuthenticatedUserId(req);
        return db(true, c -> {
            assertWritable(c, vid);
            String base = System.getenv("TSI_EXPORT_PATH");
            if (isBlank(base)) base = System.getProperty("user.home") + "/.tsi-compass/exports";
            File dir = new File(base + "/tprm_documents");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create upload directory");
            File dest = new File(dir, UUID.randomUUID() + ext);
            try (FileOutputStream fos = new FileOutputStream(dest)) { fos.write(bytes); }
            try {
                JSONObject row = one(c, "INSERT INTO tprm_documents (vendor_id, assessment_id, contract_id, doc_type, file_name, file_path, sha256_checksum, valid_until, uploaded_by) " +
                    "VALUES (?::uuid,?::uuid,?::uuid,?,?,?,?,?::date,?::uuid) RETURNING id::text AS id", vid,
                    isBlank((String) in.get("assessment_id")) ? null : in.get("assessment_id"), isBlank((String) in.get("contract_id")) ? null : in.get("contract_id"),
                    docType == null ? "OTHER" : docType, safeName, dest.getAbsolutePath(), checksum, isBlank(valid) ? null : valid, user == null ? null : user.toString());
                log(req, "TPRM_DOCUMENT_UPLOADED", "document_id", s(row, "id"), "vendor_id", vid, "sha256", checksum);
                JSONObject res = ok(); res.put("id", s(row, "id")); res.put("sha256_checksum", checksum);
                return res;
            } catch (Exception e) { dest.delete(); throw e; }
        });
    }

    @SuppressWarnings("unchecked")
    private JSONObject getDocument(JSONObject in) throws Exception {
        String id = requireText(in, "id");
        return db(false, c -> {
            JSONObject d = one(c, "SELECT file_name, file_path, sha256_checksum FROM tprm_documents WHERE id = ?::uuid", id);
            if (d == null) throw notFound("Document not found");
            File f = new File(s(d, "file_path"));
            if (!f.isFile()) throw notFound("File not found on server");
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            StringBuilder sb = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(bytes)) sb.append(String.format("%02x", x));
            JSONObject res = ok();
            res.put("file_name", s(d, "file_name")); res.put("file_data", Base64.getEncoder().encodeToString(bytes));
            res.put("sha256_checksum", s(d, "sha256_checksum")); res.put("checksum_ok", sb.toString().equals(s(d, "sha256_checksum")));
            return res;
        });
    }

    private JSONObject deleteDocument(HttpServletRequest req, JSONObject in) throws Exception {
        String id = requireText(in, "id");
        return db(true, c -> {
            JSONObject d = one(c, "SELECT vendor_id::text AS vendor_id, file_path FROM tprm_documents WHERE id = ?::uuid", id);
            if (d == null) throw notFound("Document not found");
            assertWritable(c, s(d, "vendor_id"));
            exec(c, "DELETE FROM tprm_documents WHERE id = ?::uuid", id);
            try { new File(s(d, "file_path")).delete(); } catch (Exception ignored) {}
            log(req, "TPRM_DOCUMENT_DELETED", "document_id", id);
            return ok();
        });
    }

    // ---------- vendor portal (GRC side) ----------

    private static final String PW_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private String tempPassword() {
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(PW_ALPHABET.charAt(rnd.nextInt(PW_ALPHABET.length())));
        return sb.toString();
    }

    /** A vendor cannot be edited around while it holds the questionnaire. */
    private void assertNotWithVendor(Connection c, String assessmentId) throws Exception {
        JSONObject a = one(c, "SELECT portal_status FROM tprm_assessments WHERE id = ?::uuid", assessmentId);
        if (a != null && Arrays.asList("SENT", "IN_PROGRESS", "RETURNED").contains(s(a, "portal_status")))
            throw conflict("This questionnaire is with the vendor. Withdraw it from the vendor first, or wait for them to submit.");
    }

    private JSONObject listVendorUsers(JSONObject in) throws Exception {
        String vid = requireText(in, "vendor_id");
        return db(false, c -> {
            JSONObject res = ok();
            res.put("users", query(c, "SELECT id::text AS id, username, email, status, created_at::text AS created_at FROM users WHERE role = 'VENDOR' AND vendor_id = ?::uuid ORDER BY username", vid));
            res.put("portal_enabled", org.tsicoop.compass.framework.PortalConfig.vendorPortalEnabled());
            return res;
        });
    }

    @SuppressWarnings("unchecked")
    private JSONObject createVendorUser(HttpServletRequest req, JSONObject in) throws Exception {
        String vid = requireText(in, "vendor_id"), username = requireText(in, "username"), email = requireText(in, "email").toLowerCase();
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw bad("Enter a valid email address");
        if (username.length() > 100) throw bad("Name is too long");
        String pw = tempPassword();
        String hash = new org.tsicoop.compass.framework.PasswordHasher().hashPassword(pw);
        return db(true, c -> {
            assertWritable(c, vid);
            if (one(c, "SELECT 1 AS x FROM users WHERE email = ?", email) != null) throw conflict("A user with that email already exists");
            JSONObject row = one(c, "INSERT INTO users (email, password_hash, username, role, status, vendor_id) VALUES (?,?,?,'VENDOR','ACTIVE',?::uuid) RETURNING id::text AS id", email, hash, username, vid);
            log(req, "TPRM_VENDOR_USER_CREATED", "user_id", s(row, "id"), "vendor_id", vid, "email", email);
            JSONObject res = ok(); res.put("id", s(row, "id")); res.put("email", email); res.put("temporary_password", pw);
            res.put("portal_enabled", org.tsicoop.compass.framework.PortalConfig.vendorPortalEnabled());
            return res;
        });
    }

    private JSONObject setVendorUserStatus(HttpServletRequest req, JSONObject in, boolean enable) throws Exception {
        String id = requireText(in, "id");
        return db(true, c -> {
            int n = exec(c, "UPDATE users SET status = ? WHERE id = ?::uuid AND role = 'VENDOR'", enable ? "ACTIVE" : "SUSPENDED", id);
            if (n == 0) throw notFound("Vendor user not found");
            log(req, enable ? "TPRM_VENDOR_USER_ENABLED" : "TPRM_VENDOR_USER_DISABLED", "user_id", id);
            return ok();
        });
    }

    private JSONObject resetVendorUserPassword(HttpServletRequest req, JSONObject in) throws Exception {
        String id = requireText(in, "id");
        String pw = tempPassword();
        String hash = new org.tsicoop.compass.framework.PasswordHasher().hashPassword(pw);
        return db(true, c -> {
            int n = exec(c, "UPDATE users SET password_hash = ? WHERE id = ?::uuid AND role = 'VENDOR'", hash, id);
            if (n == 0) throw notFound("Vendor user not found");
            log(req, "TPRM_VENDOR_USER_PASSWORD_RESET", "user_id", id);
            JSONObject res = ok(); res.put("temporary_password", pw);
            return res;
        });
    }

    @SuppressWarnings("unchecked")
    private JSONObject sendToVendor(HttpServletRequest req, JSONObject in) throws Exception {
        String aid = requireText(in, "id");
        String due = (String) in.get("due_date");
        if (!isBlank(due) && !due.matches("\\d{4}-\\d{2}-\\d{2}")) throw bad("due_date must be a date (YYYY-MM-DD)");
        UUID user = InputProcessor.getAuthenticatedUserId(req);
        return db(true, c -> {
            if (!org.tsicoop.compass.framework.PortalConfig.vendorPortalEnabled())
                throw conflict("The vendor portal is switched off. Set TSI_VENDOR_PORTAL_ENABLED=true and restart to use it.");
            JSONObject a = assessmentForUpdate(c, aid);
            JSONObject cur = one(c, "SELECT portal_status, due_date::text AS due_date FROM tprm_assessments WHERE id = ?::uuid", aid);
            if (!Arrays.asList("DRAFT", "SENT", "IN_REVIEW").contains(s(a, "status")) || !"NOT_SENT".equals(s(cur, "portal_status")))
                throw conflict("Only an assessment that is in progress and not already with the vendor can be sent.");
            String vid = s(a, "vendor_id");
            assertWritable(c, vid);
            if (one(c, "SELECT 1 AS x FROM users WHERE role = 'VENDOR' AND status = 'ACTIVE' AND vendor_id = ?::uuid", vid) == null)
                throw conflict("Add an active portal user for this vendor first (Portal users tab).");
            exec(c, "UPDATE tprm_assessments SET portal_status = 'SENT', status = 'SENT', sent_at = CURRENT_TIMESTAMP, sent_by = ?::uuid, portal_due_date = COALESCE(?::date, due_date) WHERE id = ?::uuid",
                user == null ? null : user.toString(), isBlank(due) ? null : due, aid);
            exec(c, "UPDATE vendors SET security_questionnaire_status = 'SENT' WHERE id = ?::uuid", vid);
            log(req, "TPRM_ASSESSMENT_SENT_TO_VENDOR", "assessment_id", aid, "vendor_id", vid);
            return ok();
        });
    }

    private JSONObject withdrawFromVendor(HttpServletRequest req, JSONObject in) throws Exception {
        String aid = requireText(in, "id");
        return db(true, c -> {
            JSONObject a = assessmentForUpdate(c, aid);
            JSONObject cur = one(c, "SELECT portal_status FROM tprm_assessments WHERE id = ?::uuid", aid);
            if (!Arrays.asList("SENT", "IN_PROGRESS", "RETURNED", "SUBMITTED").contains(s(cur, "portal_status"))) throw conflict("This assessment is not with the vendor.");
            assertWritable(c, s(a, "vendor_id"));
            exec(c, "UPDATE tprm_assessments SET portal_status = 'NOT_SENT', status = CASE WHEN EXISTS (SELECT 1 FROM tprm_answers x WHERE x.assessment_id = tprm_assessments.id AND x.answer IS NOT NULL) THEN 'IN_REVIEW' ELSE 'DRAFT' END WHERE id = ?::uuid", aid);
            log(req, "TPRM_ASSESSMENT_WITHDRAWN", "assessment_id", aid);
            return ok();
        });
    }

    @SuppressWarnings("unchecked")
    private JSONObject returnQuestions(HttpServletRequest req, JSONObject in) throws Exception {
        String aid = requireText(in, "id");
        Object items = in.get("items");
        if (!(items instanceof JSONArray) || ((JSONArray) items).isEmpty()) throw bad("Choose at least one question to return");
        return db(true, c -> {
            JSONObject a = assessmentForUpdate(c, aid);
            JSONObject cur = one(c, "SELECT portal_status FROM tprm_assessments WHERE id = ?::uuid", aid);
            if (!"SUBMITTED".equals(s(cur, "portal_status"))) throw conflict("Only a submitted questionnaire can be returned to the vendor.");
            assertWritable(c, s(a, "vendor_id"));
            long n = 0;
            for (Object o : (JSONArray) items) {
                JSONObject it = (JSONObject) o;
                String qid = s(it, "question_id"), note = s(it, "note");
                if (isBlank(qid)) throw bad("question_id required");
                if (note != null && note.length() > 1000) throw bad("A note is too long (maximum 1000 characters)");
                if (one(c, "SELECT 1 AS x FROM tprm_questions WHERE id = ?::uuid AND questionnaire_id = ?::uuid", qid, s(a, "questionnaire_id")) == null) throw notFound("Question does not belong to this assessment");
                exec(c, "INSERT INTO tprm_answers (assessment_id, question_id, returned, return_note) VALUES (?::uuid,?::uuid,TRUE,?) " +
                    "ON CONFLICT (assessment_id, question_id) DO UPDATE SET returned = TRUE, return_note = EXCLUDED.return_note", aid, qid, isBlank(note) ? null : note.trim());
                n++;
            }
            exec(c, "UPDATE tprm_assessments SET portal_status = 'RETURNED' WHERE id = ?::uuid", aid);
            log(req, "TPRM_ASSESSMENT_RETURNED", "assessment_id", aid, "questions", n);
            JSONObject res = ok(); res.put("returned", n);
            return res;
        });
    }

    // ---------- metrics, calendar, notices ----------

    @SuppressWarnings("unchecked")
    private JSONObject getMetrics() throws Exception {
        JSONObject res = db(false, c -> {
            Map<String, JSONArray> gaps = allGaps(c);
            long[] tiers = new long[4]; long total = 0, overdue = 0, due30 = 0, never = 0, gapVendors = 0, gapTotal = 0;
            JSONArray gapList = new JSONArray();
            for (Object o : query(c, VENDOR_SQL + " WHERE v.archived_at IS NULL ORDER BY v.name")) {
                JSONObject v = (JSONObject) o;
                String life = s(v, "lifecycle_status");
                if ("TERMINATED".equals(life)) continue;
                total++;
                Risk r = assess(v);
                tiers[Arrays.asList(TIERS).indexOf(r.effective)]++;
                decorateVendor(v, r, gaps.get(s(v, "id")));
                if (!"ACTIVE".equals(life) && !"ONBOARDING".equals(life)) continue;
                String rs = s(v, "review_status");
                if ("OVERDUE".equals(rs)) overdue++; else if ("DUE_SOON".equals(rs)) due30++;
                if ("NEVER".equals(rs)) never++;
            }
            for (Map.Entry<String, JSONArray> e : gaps.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                JSONObject nm = one(c, "SELECT name FROM vendors WHERE id = ?::uuid", e.getKey());
                gapVendors++;
                for (Object g : e.getValue()) {
                    gapTotal++;
                    if (gapList.size() < 60) { JSONObject x = new JSONObject((JSONObject) g); x.put("vendor_id", e.getKey()); x.put("vendor_name", s(nm, "name")); gapList.add(x); }
                }
            }
            JSONObject m = one(c,
                "SELECT (SELECT COUNT(*) FROM vendors WHERE archived_at IS NOT NULL) AS archived_count, " +
                "(SELECT COUNT(*) FROM tprm_assessments a JOIN vendors v ON v.id = a.vendor_id WHERE v.archived_at IS NULL AND a.status IN ('DRAFT','SENT','IN_REVIEW')) AS assessments_in_progress, " +
                "(SELECT COUNT(*) FROM tprm_assessments a JOIN vendors v ON v.id = a.vendor_id WHERE v.archived_at IS NULL AND a.status IN ('DRAFT','SENT','IN_REVIEW') AND a.due_date <= CURRENT_DATE + 30) AS assessments_due_30, " +
                "(SELECT COUNT(*) FROM tprm_assessments a JOIN vendors v ON v.id = a.vendor_id WHERE v.archived_at IS NULL AND a.portal_status IN ('SENT','IN_PROGRESS','RETURNED') AND a.status <> 'CANCELLED') AS awaiting_vendor, " +
                "(SELECT COUNT(*) FROM tprm_assessments a JOIN vendors v ON v.id = a.vendor_id WHERE v.archived_at IS NULL AND a.portal_status = 'SUBMITTED' AND a.status <> 'CANCELLED') AS submitted_for_review, " +
                "(SELECT COUNT(*) FROM users WHERE role = 'VENDOR' AND status = 'ACTIVE') AS vendor_users, " +
                "(SELECT COUNT(*) FROM tprm_findings f JOIN vendors v ON v.id = f.vendor_id WHERE v.archived_at IS NULL AND f.status IN ('OPEN','IN_PROGRESS')) AS open_findings, " +
                "(SELECT COUNT(*) FROM tprm_findings f JOIN vendors v ON v.id = f.vendor_id WHERE v.archived_at IS NULL AND f.status IN ('OPEN','IN_PROGRESS') AND f.due_date < CURRENT_DATE) AS overdue_findings, " +
                "(SELECT COUNT(*) FROM tprm_findings f JOIN vendors v ON v.id = f.vendor_id WHERE v.archived_at IS NULL AND f.status IN ('OPEN','IN_PROGRESS') AND f.severity IN ('CRITICAL','HIGH')) AS high_findings, " +
                "(SELECT COUNT(*) FROM (SELECT d.vendor_id, d.doc_type, MAX(d.valid_until) AS m FROM tprm_documents d JOIN vendors v ON v.id = d.vendor_id " +
                "  WHERE v.archived_at IS NULL AND d.valid_until IS NOT NULL GROUP BY d.vendor_id, d.doc_type) x WHERE x.m < CURRENT_DATE + 60) AS expiring_documents, " +
                "(SELECT COUNT(*) FROM tprm_contracts k JOIN vendors v ON v.id = k.vendor_id WHERE v.archived_at IS NULL AND k.end_date >= CURRENT_DATE AND (k.end_date - k.notice_period_days) < CURRENT_DATE + 60) AS contracts_renewing");
            JSONObject out = ok();
            out.putAll(m);
            out.put("portal_enabled", org.tsicoop.compass.framework.PortalConfig.vendorPortalEnabled());
            out.put("vendors_total", total);
            out.put("tier_critical", tiers[3]); out.put("tier_high", tiers[2]); out.put("tier_medium", tiers[1]); out.put("tier_low", tiers[0]);
            out.put("reviews_overdue", overdue); out.put("reviews_due_30", due30); out.put("never_assessed", never);
            out.put("vendors_with_gaps", gapVendors); out.put("gap_total", gapTotal); out.put("gaps", gapList);
            return out;
        });
        try { emitDueNotices(); } catch (Exception e) { System.err.println("[TPRM] due notices failed: " + e.getMessage()); }
        return res;
    }

    /**
     * Compass has no scheduler, so "due soon" notices are raised lazily whenever the TPRM hub loads, at most
     * once a week per vendor / document / finding / contract, and never more than 20 per load.
     */
    private void emitDueNotices() throws Exception {
        List<Object[]> notices = new ArrayList<>();
        db(false, c -> {
            String[][] sources = {
                {"TPRM_REVIEW_DUE", "SELECT id::text AS id, name || ': periodic review due ' || next_review_due::text AS msg FROM vendors " +
                    "WHERE archived_at IS NULL AND lifecycle_status IN ('ACTIVE','ONBOARDING') AND next_review_due <= CURRENT_DATE + 30", "Vendor review due"},
                {"TPRM_DOC_EXPIRING", "SELECT d.id::text AS id, v.name || ': ' || d.file_name || ' expires ' || d.valid_until::text AS msg FROM tprm_documents d JOIN vendors v ON v.id = d.vendor_id " +
                    "WHERE v.archived_at IS NULL AND d.valid_until <= CURRENT_DATE + 30 AND d.valid_until = (SELECT MAX(x.valid_until) FROM tprm_documents x WHERE x.vendor_id = d.vendor_id AND x.doc_type = d.doc_type)", "Vendor document expiring"},
                {"TPRM_FINDING_OVERDUE", "SELECT f.id::text AS id, v.name || ': ' || f.title || ' (due ' || f.due_date::text || ')' AS msg FROM tprm_findings f JOIN vendors v ON v.id = f.vendor_id " +
                    "WHERE v.archived_at IS NULL AND f.status IN ('OPEN','IN_PROGRESS') AND f.due_date < CURRENT_DATE", "Vendor finding overdue"},
                {"TPRM_CONTRACT_RENEWAL", "SELECT k.id::text AS id, v.name || ': ' || k.title || ' needs a renewal decision by ' || (k.end_date - k.notice_period_days)::text AS msg FROM tprm_contracts k JOIN vendors v ON v.id = k.vendor_id " +
                    "WHERE v.archived_at IS NULL AND k.end_date >= CURRENT_DATE AND (k.end_date - k.notice_period_days) <= CURRENT_DATE + 30", "Vendor contract renewal"}
            };
            for (String[] src : sources) {
                for (Object o : query(c, src[1] + " LIMIT 20")) {
                    JSONObject r = (JSONObject) o;
                    if (one(c, "SELECT 1 AS x FROM notifications WHERE event_type = ? AND entity_id = ?::uuid AND created_at > CURRENT_TIMESTAMP - INTERVAL '7 days'", src[0], s(r, "id")) != null) continue;
                    if (notices.size() < 20) notices.add(new Object[]{src[0], src[2], s(r, "msg"), UUID.fromString(s(r, "id"))});
                }
            }
            return null;
        });
        for (Object[] n : notices) Notification.emit((String) n[0], "risks", (String) n[1], (String) n[2], "risks-tprm.html", (UUID) n[3]);
    }

    private JSONObject getCalendar(JSONObject in) throws Exception {
        long days = in.get("days") instanceof Number ? Math.max(1, Math.min(365, ((Number) in.get("days")).longValue())) : 90;
        return db(false, c -> {
            JSONObject res = ok();
            res.put("days", days);
            JSONArray items = query(c,
                "SELECT * FROM (" +
                " SELECT 'REVIEW' AS kind, v.id::text AS vendor_id, v.name AS vendor_name, 'Periodic review' AS label, v.next_review_due AS due FROM vendors v " +
                "  WHERE v.archived_at IS NULL AND v.lifecycle_status IN ('ACTIVE','ONBOARDING') AND v.next_review_due IS NOT NULL" +
                " UNION ALL SELECT 'ASSESSMENT', v.id::text, v.name, 'Assessment due', COALESCE(a.portal_due_date, a.due_date) FROM tprm_assessments a JOIN vendors v ON v.id = a.vendor_id " +
                "  WHERE v.archived_at IS NULL AND a.status IN ('DRAFT','SENT','IN_REVIEW') AND COALESCE(a.portal_due_date, a.due_date) IS NOT NULL" +
                " UNION ALL SELECT 'CONTRACT', v.id::text, v.name, k.title || ': renewal decision (notice ' || k.notice_period_days || ' days)', (k.end_date - k.notice_period_days) FROM tprm_contracts k JOIN vendors v ON v.id = k.vendor_id " +
                "  WHERE v.archived_at IS NULL AND k.end_date >= CURRENT_DATE" +
                " UNION ALL SELECT 'DOCUMENT', v.id::text, v.name, d.file_name || ' expires', d.valid_until FROM tprm_documents d JOIN vendors v ON v.id = d.vendor_id " +
                "  WHERE v.archived_at IS NULL AND d.valid_until IS NOT NULL AND d.valid_until = (SELECT MAX(x.valid_until) FROM tprm_documents x WHERE x.vendor_id = d.vendor_id AND x.doc_type = d.doc_type)" +
                " UNION ALL SELECT 'FINDING', v.id::text, v.name, f.title, f.due_date FROM tprm_findings f JOIN vendors v ON v.id = f.vendor_id " +
                "  WHERE v.archived_at IS NULL AND f.status IN ('OPEN','IN_PROGRESS') AND f.due_date IS NOT NULL" +
                ") x WHERE due <= CURRENT_DATE + ?::int ORDER BY due, vendor_name", (int) days);

            for (Object o : items) { JSONObject r = (JSONObject) o; r.put("overdue", s(r, "due") != null && s(r, "due").compareTo(java.time.LocalDate.now().toString()) < 0); }
            res.put("items", items);
            return res;
        });
    }
}
