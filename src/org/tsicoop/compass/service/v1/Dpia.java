package org.tsicoop.compass.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.EventLog;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.OutputProcessor;
import org.tsicoop.compass.framework.PoolDB;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// DPIA (Data Protection Impact Assessment). One per processing activity (a data_assets row),
// with the risk derived from the Data Register / Data Flows. See docs/architecture/system-design.md section 5.2.
public class Dpia implements Action {

    private static final String SELECT_ASSETS =
        "SELECT d.id::text AS asset_id, d.name, d.category, d.sensitivity, d.purpose, d.processing_basis, " +
        "d.retention_period, d.description, d.is_large_scale, d.involves_profiling, o.username AS owner_name, " +
        "(EXISTS (SELECT 1 FROM data_flows f WHERE (f.source_data_asset_id = d.id OR f.destination_data_asset_id = d.id) AND f.is_cross_border = true)) AS has_cross_border, " +
        "(SELECT STRING_AGG(DISTINCT f.destination_country, ', ') FROM data_flows f " +
        " WHERE (f.source_data_asset_id = d.id OR f.destination_data_asset_id = d.id) AND f.is_cross_border = true AND f.destination_country IS NOT NULL) AS countries, " +
        "(SELECT COUNT(*) FROM data_asset_recipients r WHERE r.data_asset_id = d.id) AS recipient_count, " +
        "(SELECT STRING_AGG(dp.name, ', ' ORDER BY dp.name) FROM data_asset_principals dap " +
        " JOIN data_principals dp ON dp.id = dap.principal_id WHERE dap.data_asset_id = d.id) AS principals, " +
        "(SELECT STRING_AGG(DISTINCT f.data_elements, '; ') FROM data_flows f " +
        " WHERE (f.source_data_asset_id = d.id OR f.destination_data_asset_id = d.id) AND f.data_elements IS NOT NULL) AS flow_elements " +
        "FROM data_assets d LEFT JOIN users o ON o.id = d.owner_id";

    private static final String[] RISK_LEVELS = {"LOW", "MEDIUM", "HIGH"};

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");
            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing _func", req.getRequestURI()); return;
            }
            switch (func.toLowerCase()) {
                case "get_dpia_metrics":       OutputProcessor.send(res, 200, getMetrics());           break;
                case "list_dpias":             OutputProcessor.send(res, 200, listDpias(input, null)); break;
                case "get_dpia_detail":        getDetail(req, res, input);                             break;
                case "generate_dpias":         generate(req, res);                                     break;
                case "clear_generated_dpias":  clearGenerated(req, res);                               break;
                case "update_dpia":            updateDpia(req, res, input);                            break;
                case "set_dpia_status":        setStatus(req, res, input);                             break;
                case "delete_dpia":            deleteDpia(req, res, input);                            break;
                default: OutputProcessor.errorResponse(res, 400, "Bad Request", "Unknown function: " + func, req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Internal Error", e.getMessage(), req.getRequestURI());
        }
    }

    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse r) { return "POST".equalsIgnoreCase(m); }

    // ---------- Risk derivation ----------

    private static class Derived {
        int score;
        String level;
        JSONArray factors = new JSONArray();
        String necessity;
        String proportionality;
        String mitigations;
    }

    @SuppressWarnings("unchecked")
    private void factor(Derived d, java.util.List<String> mits, String code, String label, String reference, int points, String mitigation) {
        JSONObject f = new JSONObject();
        f.put("code", code); f.put("label", label); f.put("reference", reference); f.put("points", (long) points);
        d.factors.add(f);
        d.score += points;
        mits.add(mitigation);
    }

    private Derived derive(ResultSet rs) throws Exception {
        Derived d = new Derived();
        java.util.List<String> mits = new java.util.ArrayList<>();
        String category = rs.getString("category");
        String sensitivity = rs.getString("sensitivity");

        if ("HEALTH".equals(category) || "RESTRICTED".equals(sensitivity)) {
            factor(d, mits, "SENSITIVE_DATA", "Sensitive data (health or restricted)", "Rule 6 safeguards", 4,
                "Encrypt sensitive data at rest and in transit, restrict access to named roles and review access quarterly.");
        }
        if (rs.getBoolean("involves_profiling")) {
            factor(d, mits, "PROFILING", "Profiling / behavioural tracking", "Sec 10 / Rule 13", 3,
                "Document the profiling logic, offer an opt-out and review accuracy and bias periodically.");
        }
        if ("FINANCIAL".equals(category)) {
            factor(d, mits, "FINANCIAL_DATA", "Financial data", "Rule 6 safeguards", 2,
                "Tokenise or mask financial identifiers, limit retention and log access.");
        }
        if (rs.getBoolean("has_cross_border")) {
            String countries = rs.getString("countries");
            factor(d, mits, "CROSS_BORDER", "Cross-border transfer" + (isBlank(countries) ? "" : " (" + countries + ")"), "Sec 16", 2,
                "Confirm the destination is not restricted by the Central Government under Sec 16 and record the transfer safeguards.");
        }
        if (rs.getLong("recipient_count") > 0) {
            factor(d, mits, "THIRD_PARTY_SHARING", "Shared with third-party recipients", "Sec 8(2) / Rule 6(1)(f)", 1,
                "Ensure a signed data processing agreement (Sec 8(2)) with each recipient covering security and breach notification.");
        }
        if (rs.getBoolean("is_large_scale")) {
            factor(d, mits, "LARGE_SCALE", "Large-scale processing", "Rule 13", 1,
                "Assign an accountable owner, audit periodically and monitor volumes and access anomalies.");
        }
        d.level = d.score >= 4 ? "HIGH" : (d.score == 3 ? "MEDIUM" : "LOW");
        d.mitigations = mits.isEmpty()
            ? "No elevated risk factors identified; continue routine safeguards."
            : String.join("\n", mits);

        String purpose = rs.getString("purpose");
        String basis = rs.getString("processing_basis");
        StringBuilder n = new StringBuilder();
        n.append("Purpose: ").append(isBlank(purpose) ? "not recorded" : purpose).append(". ");
        n.append("Lawful basis: ").append(isBlank(basis) ? "not recorded" : basis.replace('_', ' ').toLowerCase()).append(".");
        if (isBlank(purpose) || isBlank(basis)) n.append(" Record the purpose and lawful basis in the Data Register to justify necessity.");
        d.necessity = n.toString();

        String elements = rs.getString("flow_elements");
        if (isBlank(elements)) elements = rs.getString("description");
        String principals = rs.getString("principals");
        String retention = rs.getString("retention_period");
        d.proportionality =
            "Data category: " + (category == null ? "-" : category) + ", sensitivity: " + (sensitivity == null ? "-" : sensitivity) + ". " +
            "Data elements: " + (isBlank(elements) ? "not itemised" : elements) + ". " +
            "Data principals: " + (isBlank(principals) ? "not tagged" : principals) + ". " +
            "Retention: " + (isBlank(retention) ? "not recorded" : retention) + ". " +
            "Confirm only the data necessary for the stated purpose is collected and kept.";
        return d;
    }

    /** Derived risk for every data asset (or one), keyed by asset id. */
    private Map<String, Derived> deriveAll(Connection conn, String assetId) throws Exception {
        Map<String, Derived> out = new HashMap<>();
        PreparedStatement p = null; ResultSet rs = null;
        try {
            p = conn.prepareStatement(SELECT_ASSETS + (assetId == null ? "" : " WHERE d.id = ?::uuid"));
            if (assetId != null) p.setString(1, assetId);
            rs = p.executeQuery();
            while (rs.next()) out.put(rs.getString("asset_id"), derive(rs));
        } finally { closeQuietly(rs, p); }
        return out;
    }

    // ---------- Reads ----------

    @SuppressWarnings("unchecked")
    private JSONObject getMetrics() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            Map<String, Derived> derived = deriveAll(conn, null);
            long high = 0, medium = 0, low = 0;
            for (Derived d : derived.values()) {
                if ("HIGH".equals(d.level)) high++; else if ("MEDIUM".equals(d.level)) medium++; else low++;
            }
            result.put("total_activities", (long) derived.size());
            result.put("high_count", high); result.put("medium_count", medium); result.put("low_count", low);

            p = conn.prepareStatement("SELECT COUNT(*) FROM dpias"); rs = p.executeQuery();
            result.put("dpia_count", rs.next() ? rs.getLong(1) : 0L);
            closeQuietly(rs, p); rs = null; p = null;

            long stale = 0;
            p = conn.prepareStatement("SELECT data_asset_id::text, inherent_risk, risk_score FROM dpias WHERE status <> 'DRAFT'");
            rs = p.executeQuery();
            while (rs.next()) {
                Derived d = derived.get(rs.getString(1));
                if (d != null && (!d.level.equals(rs.getString(2)) || d.score != rs.getInt(3))) stale++;
            }
            result.put("stale_count", stale);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch (Exception i) {} }
        result.put("success", true);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listDpias(JSONObject input, String onlyId) throws Exception {
        String risk = (String) input.get("risk");
        String status = (String) input.get("status");
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (onlyId != null) where.append(" AND x.id = ?::uuid");
        if (!isBlank(risk)) where.append(" AND x.inherent_risk = ?");
        if (!isBlank(status)) where.append(" AND x.status = ?");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            Map<String, Derived> derived = deriveAll(conn, null);
            p = conn.prepareStatement(
                "SELECT x.id::text, x.data_asset_id::text, a.name AS activity_name, a.category, a.sensitivity, o.username AS owner_name, " +
                "x.status, x.inherent_risk, x.residual_risk, x.risk_score, x.risk_factors::text AS risk_factors, " +
                "x.necessity_note, x.proportionality_note, x.mitigations, x.residual_note, x.edited, " +
                "x.approved_at::text, ab.username AS approved_by_name, x.submitted_at::text, x.board_reference, " +
                "x.generated_at::text, x.updated_at::text " +
                "FROM dpias x JOIN data_assets a ON a.id = x.data_asset_id " +
                "LEFT JOIN users o ON o.id = a.owner_id LEFT JOIN users ab ON ab.id = x.approved_by" + where +
                " ORDER BY CASE x.inherent_risk WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END, a.name");
            int idx = 1;
            if (onlyId != null) p.setString(idx++, onlyId);
            if (!isBlank(risk)) p.setString(idx++, risk);
            if (!isBlank(status)) p.setString(idx++, status);
            rs = p.executeQuery();
            JSONParser parser = new JSONParser();
            while (rs.next()) {
                JSONObject o = new JSONObject();
                String assetId = rs.getString("data_asset_id");
                o.put("id", rs.getString("id"));
                o.put("data_asset_id", assetId);
                o.put("activity_name", rs.getString("activity_name"));
                o.put("category", rs.getString("category"));
                o.put("sensitivity", rs.getString("sensitivity"));
                o.put("owner_name", rs.getString("owner_name"));
                String st = rs.getString("status");
                o.put("status", st);
                o.put("inherent_risk", rs.getString("inherent_risk"));
                o.put("residual_risk", rs.getString("residual_risk"));
                o.put("risk_score", rs.getInt("risk_score"));
                o.put("risk_factors", parser.parse(rs.getString("risk_factors")));
                o.put("necessity_note", rs.getString("necessity_note"));
                o.put("proportionality_note", rs.getString("proportionality_note"));
                o.put("mitigations", rs.getString("mitigations"));
                o.put("residual_note", rs.getString("residual_note"));
                o.put("approved_at", rs.getString("approved_at"));
                o.put("approved_by_name", rs.getString("approved_by_name"));
                o.put("submitted_at", rs.getString("submitted_at"));
                o.put("board_reference", rs.getString("board_reference"));
                o.put("generated_at", rs.getString("generated_at"));
                o.put("updated_at", rs.getString("updated_at"));
                Derived d = derived.get(assetId);
                boolean stale = !"DRAFT".equals(st) && d != null
                    && (!d.level.equals(rs.getString("inherent_risk")) || d.score != rs.getInt("risk_score"));
                o.put("stale", stale);
                if (stale) { o.put("current_risk", d.level); o.put("current_score", (long) d.score); }
                list.add(o);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch (Exception i) {} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("dpias", list);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void getDetail(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        JSONObject r = listDpias(new JSONObject(), id);
        JSONArray arr = (JSONArray) r.get("dpias");
        if (arr.isEmpty()) { OutputProcessor.errorResponse(res, 404, "Not Found", "DPIA not found", req.getRequestURI()); return; }
        JSONObject out = new JSONObject(); out.put("success", true); out.put("dpia", arr.get(0));
        OutputProcessor.send(res, 200, out);
    }

    // ---------- Writes ----------

    @SuppressWarnings("unchecked")
    private void generate(HttpServletRequest req, HttpServletResponse res) throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        long created = 0, refreshed = 0, untouched = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            conn.setAutoCommit(false);
            Map<String, Derived> derived = deriveAll(conn, null);

            Map<String, String[]> existing = new LinkedHashMap<>(); // asset_id -> {status, edited}
            p = conn.prepareStatement("SELECT data_asset_id::text, status, edited FROM dpias");
            rs = p.executeQuery();
            while (rs.next()) existing.put(rs.getString(1), new String[]{rs.getString(2), String.valueOf(rs.getBoolean(3))});
            closeQuietly(rs, p); rs = null; p = null;

            for (Map.Entry<String, Derived> e : derived.entrySet()) {
                String assetId = e.getKey(); Derived d = e.getValue();
                String[] ex = existing.get(assetId);
                if (ex == null) {
                    p = conn.prepareStatement(
                        "INSERT INTO dpias (data_asset_id, inherent_risk, residual_risk, risk_score, risk_factors, necessity_note, proportionality_note, mitigations) " +
                        "VALUES (?::uuid,?,?,?,?::jsonb,?,?,?)");
                    p.setString(1, assetId); p.setString(2, d.level); p.setString(3, d.level); p.setInt(4, d.score);
                    p.setString(5, d.factors.toJSONString()); p.setString(6, d.necessity); p.setString(7, d.proportionality); p.setString(8, d.mitigations);
                    p.executeUpdate(); closeQuietly(null, p); p = null;
                    created++;
                } else if ("DRAFT".equals(ex[0])) {
                    if ("true".equals(ex[1])) {
                        // The user has edited notes: refresh only the derived risk fields.
                        p = conn.prepareStatement(
                            "UPDATE dpias SET inherent_risk=?, risk_score=?, risk_factors=?::jsonb, generated_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP WHERE data_asset_id=?::uuid");
                        p.setString(1, d.level); p.setInt(2, d.score); p.setString(3, d.factors.toJSONString()); p.setString(4, assetId);
                    } else {
                        p = conn.prepareStatement(
                            "UPDATE dpias SET inherent_risk=?, residual_risk=?, risk_score=?, risk_factors=?::jsonb, necessity_note=?, proportionality_note=?, mitigations=?, " +
                            "generated_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP WHERE data_asset_id=?::uuid");
                        p.setString(1, d.level); p.setString(2, d.level); p.setInt(3, d.score); p.setString(4, d.factors.toJSONString());
                        p.setString(5, d.necessity); p.setString(6, d.proportionality); p.setString(7, d.mitigations); p.setString(8, assetId);
                    }
                    p.executeUpdate(); closeQuietly(null, p); p = null;
                    refreshed++;
                } else {
                    untouched++; // UNDER_REVIEW and later are never overwritten; list shows a stale flag instead
                }
            }
            conn.commit();
        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (Exception ignored) {}
            throw e;
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); } catch (Exception ignored) {}
            if (pool != null) try { pool.cleanup(rs, p, conn); } catch (Exception i) {}
        }
        JSONObject ctx = new JSONObject(); ctx.put("created", created); ctx.put("refreshed", refreshed); ctx.put("untouched", untouched);
        EventLog.log(InputProcessor.getEmail(req), "DPIA_GENERATED", ctx.toJSONString());
        JSONObject result = new JSONObject(); result.put("success", true);
        result.put("created", created); result.put("refreshed", refreshed); result.put("untouched", untouched);
        OutputProcessor.send(res, 200, result);
    }

    @SuppressWarnings("unchecked")
    private void clearGenerated(HttpServletRequest req, HttpServletResponse res) throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        int deleted;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("DELETE FROM dpias WHERE status = 'DRAFT'");
            deleted = p.executeUpdate();
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch (Exception i) {} }
        JSONObject ctx = new JSONObject(); ctx.put("deleted", (long) deleted);
        EventLog.log(InputProcessor.getEmail(req), "DPIA_CLEARED", ctx.toJSONString());
        JSONObject result = new JSONObject(); result.put("success", true); result.put("deleted", (long) deleted);
        OutputProcessor.send(res, 200, result);
    }

    @SuppressWarnings("unchecked")
    private void updateDpia(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        String residual = (String) input.get("residual_risk");
        if (!isBlank(residual) && !isLevel(residual)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "residual_risk must be LOW, MEDIUM or HIGH", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            String status = currentStatus(conn, id);
            if (status == null) { OutputProcessor.errorResponse(res, 404, "Not Found", "DPIA not found", req.getRequestURI()); return; }
            if (!"DRAFT".equals(status) && !"UNDER_REVIEW".equals(status)) {
                OutputProcessor.errorResponse(res, 409, "Conflict", "An approved or submitted DPIA is locked", req.getRequestURI()); return;
            }
            p = conn.prepareStatement(
                "UPDATE dpias SET necessity_note=COALESCE(?,necessity_note), proportionality_note=COALESCE(?,proportionality_note), " +
                "mitigations=COALESCE(?,mitigations), residual_note=COALESCE(?,residual_note), residual_risk=COALESCE(?,residual_risk), " +
                "edited=TRUE, updated_at=CURRENT_TIMESTAMP WHERE id=?::uuid");
            p.setString(1, (String) input.get("necessity_note")); p.setString(2, (String) input.get("proportionality_note"));
            p.setString(3, (String) input.get("mitigations")); p.setString(4, (String) input.get("residual_note"));
            p.setString(5, isBlank(residual) ? null : residual.toUpperCase()); p.setString(6, id);
            p.executeUpdate();
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch (Exception i) {} }
        JSONObject ctx = new JSONObject(); ctx.put("dpia_id", id);
        EventLog.log(InputProcessor.getEmail(req), "DPIA_UPDATED", ctx.toJSONString());
        JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
    }

    @SuppressWarnings("unchecked")
    private void setStatus(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        String target = input.get("status") == null ? null : ((String) input.get("status")).toUpperCase();
        if (isBlank(id) || isBlank(target)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id and status required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            String current = currentStatus(conn, id);
            if (current == null) { OutputProcessor.errorResponse(res, 404, "Not Found", "DPIA not found", req.getRequestURI()); return; }
            boolean ok = ("DRAFT".equals(current) && "UNDER_REVIEW".equals(target))
                      || ("UNDER_REVIEW".equals(current) && ("DRAFT".equals(target) || "APPROVED".equals(target)))
                      || ("APPROVED".equals(current) && "SUBMITTED".equals(target));
            if (!ok) {
                OutputProcessor.errorResponse(res, 409, "Conflict", "Cannot move a DPIA from " + current + " to " + target, req.getRequestURI()); return;
            }
            if ("APPROVED".equals(target)) {
                UUID approver = InputProcessor.getAuthenticatedUserId(req);
                p = conn.prepareStatement("UPDATE dpias SET status='APPROVED', approved_by=?::uuid, approved_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP WHERE id=?::uuid");
                p.setString(1, approver == null ? null : approver.toString()); p.setString(2, id);
            } else if ("SUBMITTED".equals(target)) {
                String boardRef = (String) input.get("board_reference");
                p = conn.prepareStatement("UPDATE dpias SET status='SUBMITTED', submitted_at=CURRENT_TIMESTAMP, board_reference=?, updated_at=CURRENT_TIMESTAMP WHERE id=?::uuid");
                p.setString(1, isBlank(boardRef) ? null : boardRef.trim()); p.setString(2, id);
            } else {
                p = conn.prepareStatement("UPDATE dpias SET status=?, updated_at=CURRENT_TIMESTAMP WHERE id=?::uuid");
                p.setString(1, target); p.setString(2, id);
            }
            p.executeUpdate();
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch (Exception i) {} }
        JSONObject ctx = new JSONObject(); ctx.put("dpia_id", id); ctx.put("status", target);
        EventLog.log(InputProcessor.getEmail(req), "DPIA_STATUS_" + target, ctx.toJSONString());
        JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
    }

    @SuppressWarnings("unchecked")
    private void deleteDpia(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        int n;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("DELETE FROM dpias WHERE id=?::uuid");
            p.setString(1, id); n = p.executeUpdate();
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch (Exception i) {} }
        if (n == 0) { OutputProcessor.errorResponse(res, 404, "Not Found", "DPIA not found", req.getRequestURI()); return; }
        JSONObject ctx = new JSONObject(); ctx.put("dpia_id", id);
        EventLog.log(InputProcessor.getEmail(req), "DPIA_DELETED", ctx.toJSONString());
        JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
    }

    // ---------- helpers ----------

    private String currentStatus(Connection conn, String id) throws Exception {
        PreparedStatement p = null; ResultSet rs = null;
        try {
            p = conn.prepareStatement("SELECT status FROM dpias WHERE id=?::uuid");
            p.setString(1, id); rs = p.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } finally { closeQuietly(rs, p); }
    }

    private boolean isLevel(String v) {
        for (String l : RISK_LEVELS) if (l.equalsIgnoreCase(v)) return true;
        return false;
    }

    private void closeQuietly(ResultSet rs, PreparedStatement p) {
        try { if (rs != null) rs.close(); } catch (Exception ignored) {}
        try { if (p != null) p.close(); } catch (Exception ignored) {}
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
