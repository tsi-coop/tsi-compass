package org.tsicoop.compass.service.v1;

import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.OutputProcessor;
import org.tsicoop.compass.framework.PoolDB;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.sql.*;
import java.util.UUID;

public class Controls implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");
            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing _func", req.getRequestURI()); return;
            }
            switch (func.toLowerCase()) {
                case "get_controls_metrics":   OutputProcessor.send(res, 200, getControlsMetrics());     break;
                case "list_frameworks":        OutputProcessor.send(res, 200, listFrameworks());          break;
                case "list_controls":          OutputProcessor.send(res, 200, listControls(input));       break;
                case "add_control":            addControl(req, res, input);                               break;
                case "update_control":         updateControl(req, res, input);                            break;
                case "attest_control":         attestControl(req, res, input);                            break;
                case "list_exceptions":        OutputProcessor.send(res, 200, listExceptions());          break;
                case "add_exception":          addException(req, res, input);                             break;
                case "update_exception_status": updateExceptionStatus(req, res, input);                   break;
                case "list_staff":                     OutputProcessor.send(res, 200, listStaff());                       break;
                case "list_policies":                  OutputProcessor.send(res, 200, listPolicies());                    break;
                case "list_framework_requirements":    OutputProcessor.send(res, 200, listFrameworkRequirements(input));  break;
                case "import_framework_controls":      importFrameworkControls(req, res, input);                          break;
                case "delete_control":                 deleteControl(req, res, input);                                    break;
                case "delete_exception":               deleteException(req, res, input);                                  break;
                default: OutputProcessor.errorResponse(res, 400, "Bad Request", "Unknown function: "+func, req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Internal Error", e.getMessage(), req.getRequestURI());
        }
    }

    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse r) { return "POST".equalsIgnoreCase(m); }

    @SuppressWarnings("unchecked")
    private JSONObject getControlsMetrics() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT COUNT(*) FROM frameworks"); rs = p.executeQuery();
            result.put("framework_count", rs.next() ? rs.getLong(1) : 0L);
            pool.cleanup(rs, p, null); rs = null; p = null;

            p = conn.prepareStatement("SELECT COUNT(*) FROM controls"); rs = p.executeQuery();
            result.put("total_controls", rs.next() ? rs.getLong(1) : 0L);
            pool.cleanup(rs, p, null); rs = null; p = null;

            p = conn.prepareStatement(
                "SELECT COUNT(*) FROM controls c WHERE EXISTS (" +
                "  SELECT 1 FROM control_attestations ca WHERE ca.control_id = c.id" +
                "  AND ca.status IN ('COMPLIANT','APPROVED')" +
                "  AND ca.attested_at = (SELECT MAX(attested_at) FROM control_attestations WHERE control_id = c.id)" +
                ")"
            ); rs = p.executeQuery();
            long compliant = rs.next() ? rs.getLong(1) : 0L;
            result.put("compliant_controls", compliant);
            pool.cleanup(rs, p, null); rs = null; p = null;

            p = conn.prepareStatement(
                "SELECT COUNT(*) FROM exceptions WHERE status IN ('PENDING','APPROVED')"
            ); rs = p.executeQuery();
            long activeEx = rs.next() ? rs.getLong(1) : 0L;
            result.put("active_exceptions", activeEx);
            pool.cleanup(rs, p, null); rs = null; p = null;

            p = conn.prepareStatement(
                "SELECT COUNT(*) FROM exceptions WHERE status = 'PENDING'"
            ); rs = p.executeQuery();
            result.put("pending_exceptions", rs.next() ? rs.getLong(1) : 0L);
            pool.cleanup(rs, p, null); rs = null; p = null;

            p = conn.prepareStatement(
                "SELECT COUNT(*) FROM exceptions WHERE status = 'APPROVED'"
            ); rs = p.executeQuery();
            result.put("approved_exceptions", rs.next() ? rs.getLong(1) : 0L);

            // total controls count for compliance pct
            long total = (Long) result.get("total_controls");
            result.put("compliance_pct", total > 0 ? (compliant * 100 / total) : 0L);

        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        result.put("success", true); return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listFrameworks() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray frameworks = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT f.id::text, f.name, f.version, f.description, " +
                "COUNT(DISTINCT crm.control_id) AS total_controls, " +
                "COUNT(DISTINCT CASE WHEN ca.status IN ('COMPLIANT','APPROVED') THEN crm.control_id END) AS compliant_controls " +
                "FROM frameworks f " +
                "LEFT JOIN framework_requirements fr ON fr.framework_id = f.id " +
                "LEFT JOIN control_requirement_mappings crm ON crm.requirement_id = fr.id " +
                "LEFT JOIN LATERAL (" +
                "  SELECT control_id, status FROM control_attestations " +
                "  WHERE control_id = crm.control_id ORDER BY attested_at DESC NULLS LAST LIMIT 1" +
                ") ca ON true " +
                "GROUP BY f.id, f.name, f.version, f.description ORDER BY f.name"
            );
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject fw = new JSONObject();
                fw.put("id",               rs.getString("id"));
                fw.put("name",             rs.getString("name"));
                fw.put("version",          rs.getString("version"));
                fw.put("description",      rs.getString("description"));
                long total = rs.getLong("total_controls");
                long comp  = rs.getLong("compliant_controls");
                fw.put("total_controls",    total);
                fw.put("compliant_controls", comp);
                fw.put("readiness_pct",     total > 0 ? (comp * 100 / total) : 0L);
                frameworks.add(fw);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("frameworks", frameworks);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listControls(JSONObject input) throws Exception {
        String frameworkId = (String) input.get("framework_id");
        String search = (String) input.get("search");
        StringBuilder sql = new StringBuilder(
            "SELECT c.id::text, c.code, c.title, c.type, c.frequency, c.description, " +
            "c.owner_id::text, u.username AS owner_name, " +
            "ca.status AS attestation_status, ca.next_due_date::text, ca.attested_at::text, " +
            "ca.id::text AS attestation_id, " +
            "(SELECT el.id::text FROM evidence_locker el WHERE el.control_attestation_id = ca.id LIMIT 1) AS evidence_id, " +
            "(SELECT el.file_name FROM evidence_locker el WHERE el.control_attestation_id = ca.id LIMIT 1) AS evidence_file_name, " +
            "STRING_AGG(DISTINCT f.name, ', ' ORDER BY f.name) AS frameworks " +
            "FROM controls c " +
            "LEFT JOIN users u ON u.id = c.owner_id " +
            "LEFT JOIN LATERAL (" +
            "  SELECT id, control_id, status, next_due_date, attested_at FROM control_attestations " +
            "  WHERE control_id = c.id ORDER BY attested_at DESC NULLS LAST LIMIT 1" +
            ") ca ON true " +
            "LEFT JOIN control_requirement_mappings crm ON crm.control_id = c.id " +
            "LEFT JOIN framework_requirements fr ON fr.id = crm.requirement_id " +
            "LEFT JOIN frameworks f ON f.id = fr.framework_id " +
            "WHERE 1=1"
        );
        if (!isBlank(frameworkId)) sql.append(" AND fr.framework_id = ?");
        if (!isBlank(search)) sql.append(" AND (c.code ILIKE ? OR c.title ILIKE ?)");
        sql.append(" GROUP BY c.id, c.code, c.title, c.type, c.frequency, c.description, c.owner_id, u.username, ca.status, ca.next_due_date, ca.attested_at, ca.id ORDER BY c.code");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray controls = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(sql.toString());
            int idx = 1;
            if (!isBlank(frameworkId)) p.setObject(idx++, UUID.fromString(frameworkId));
            if (!isBlank(search)) { String like = "%"+search+"%"; p.setString(idx++, like); p.setString(idx++, like); }
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject c = new JSONObject();
                c.put("id",                 rs.getString("id"));
                c.put("code",               rs.getString("code"));
                c.put("title",              rs.getString("title"));
                c.put("type",               rs.getString("type"));
                c.put("frequency",          rs.getString("frequency"));
                c.put("description",        rs.getString("description"));
                c.put("owner_id",           rs.getString("owner_id"));
                c.put("owner_name",         rs.getString("owner_name"));
                c.put("attestation_status", rs.getString("attestation_status"));
                c.put("next_due_date",      rs.getString("next_due_date"));
                c.put("attested_at",        rs.getString("attested_at"));
                c.put("attestation_id",     rs.getString("attestation_id"));
                c.put("evidence_id",        rs.getString("evidence_id"));
                c.put("evidence_file_name", rs.getString("evidence_file_name"));
                c.put("frameworks",         rs.getString("frameworks"));
                controls.add(c);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("controls", controls); return result;
    }

    @SuppressWarnings("unchecked")
    private void addControl(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String code  = (String) input.get("code");
        String title = (String) input.get("title");
        String type  = (String) input.get("type");
        String freq  = (String) input.get("frequency");
        if (isBlank(code) || isBlank(title) || isBlank(type) || isBlank(freq)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "code, title, type, frequency required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("INSERT INTO controls (code,title,type,description,owner_id,frequency) VALUES (?,?,?,?,?,?) RETURNING id::text");
            p.setString(1, code); p.setString(2, title); p.setString(3, type);
            p.setString(4, (String) input.get("description"));
            String ownerId = (String) input.get("owner_id");
            if (isBlank(ownerId)) p.setNull(5, Types.OTHER); else p.setObject(5, UUID.fromString(ownerId));
            p.setString(6, freq);
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void updateControl(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("UPDATE controls SET title=COALESCE(?,title), type=COALESCE(?,type), frequency=COALESCE(?,frequency), description=COALESCE(?,description), owner_id=COALESCE(?,owner_id) WHERE id=?");
            p.setString(1, (String) input.get("title")); p.setString(2, (String) input.get("type"));
            p.setString(3, (String) input.get("frequency")); p.setString(4, (String) input.get("description"));
            String ownerId = (String) input.get("owner_id");
            if (isBlank(ownerId)) p.setNull(5, Types.OTHER); else p.setObject(5, UUID.fromString(ownerId));
            p.setObject(6, UUID.fromString(id)); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void attestControl(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String controlId = (String) input.get("control_id");
        String status    = (String) input.get("status");
        String nextDue   = (String) input.get("next_due_date");
        String evidenceLink = (String) input.get("evidence_file_link");
        if (isBlank(controlId) || isBlank(status) || isBlank(nextDue)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "control_id, status, next_due_date required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "INSERT INTO control_attestations (control_id, status, next_due_date, evidence_file_link, attested_at) " +
                "VALUES (?::uuid, ?, ?::date, ?, CURRENT_TIMESTAMP) RETURNING id::text"
            );
            p.setString(1, controlId); p.setString(2, status); p.setString(3, nextDue);
            p.setString(4, isBlank(evidenceLink) ? null : evidenceLink);
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private JSONObject listExceptions() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray exceptions = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT e.id::text, e.reason, e.compensating_controls, e.expiry_date::text, e.status, e.created_at::text, " +
                "pol.title AS policy_title, c.title AS control_title, c.code AS control_code, " +
                "req.username AS requester_name, apr.username AS approver_name " +
                "FROM exceptions e " +
                "LEFT JOIN policies pol ON pol.id = e.policy_id " +
                "LEFT JOIN controls c ON c.id = e.control_id " +
                "LEFT JOIN users req ON req.id = e.requested_by " +
                "LEFT JOIN users apr ON apr.id = e.approver_id " +
                "ORDER BY CASE e.status WHEN 'PENDING' THEN 1 WHEN 'APPROVED' THEN 2 ELSE 3 END, e.expiry_date ASC"
            );
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject ex = new JSONObject();
                ex.put("id",                    rs.getString("id"));
                ex.put("reason",                rs.getString("reason"));
                ex.put("compensating_controls", rs.getString("compensating_controls"));
                ex.put("expiry_date",           rs.getString("expiry_date"));
                ex.put("status",                rs.getString("status"));
                ex.put("created_at",            rs.getString("created_at"));
                ex.put("policy_title",          rs.getString("policy_title"));
                ex.put("control_title",         rs.getString("control_title"));
                ex.put("control_code",          rs.getString("control_code"));
                ex.put("requester_name",        rs.getString("requester_name"));
                ex.put("approver_name",         rs.getString("approver_name"));
                exceptions.add(ex);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("exceptions", exceptions); return result;
    }

    @SuppressWarnings("unchecked")
    private void addException(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String reason   = (String) input.get("reason");
        String comp     = (String) input.get("compensating_controls");
        String expiry   = (String) input.get("expiry_date");
        String reqById  = (String) input.get("requested_by");
        if (isBlank(reason) || isBlank(comp) || isBlank(expiry)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "reason, compensating_controls, expiry_date required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "INSERT INTO exceptions (policy_id,control_id,requested_by,reason,compensating_controls,expiry_date,status) " +
                "VALUES (?::uuid,?::uuid,?::uuid,?,?,?::date,'APPROVED') RETURNING id::text"
            );
            String polId  = (String) input.get("policy_id");
            String ctlId  = (String) input.get("control_id");
            p.setString(1, isBlank(polId)   ? null : polId);
            p.setString(2, isBlank(ctlId)   ? null : ctlId);
            p.setString(3, isBlank(reqById) ? null : reqById);
            p.setString(4, reason); p.setString(5, comp); p.setString(6, expiry);
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void updateExceptionStatus(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id     = (String) input.get("id");
        String status = (String) input.get("status");
        if (isBlank(id) || isBlank(status)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id and status required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("UPDATE exceptions SET status=? WHERE id=?");
            p.setString(1, status); p.setObject(2, UUID.fromString(id)); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private JSONObject listStaff() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray staff = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT id::text, username FROM users WHERE status='ACTIVE' ORDER BY username");
            rs = p.executeQuery();
            while (rs.next()) { JSONObject s=new JSONObject(); s.put("id",rs.getString(1)); s.put("username",rs.getString(2)); staff.add(s); }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("staff", staff); return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listPolicies() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray policies = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT id::text, title FROM policies WHERE status NOT IN ('ARCHIVED','DRAFT') ORDER BY title");
            rs = p.executeQuery();
            while (rs.next()) { JSONObject pol=new JSONObject(); pol.put("id",rs.getString(1)); pol.put("title",rs.getString(2)); policies.add(pol); }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("policies", policies); return result;
    }

    // -------------------------------------------------------------------------
    // List requirements for a framework, flagging which already have a control
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject listFrameworkRequirements(JSONObject input) throws Exception {
        String frameworkId = (String) input.get("framework_id");
        if (isBlank(frameworkId)) throw new Exception("framework_id is required");
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray requirements = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT fr.id::text, fr.section_code, fr.title, fr.description, " +
                "EXISTS (SELECT 1 FROM control_requirement_mappings crm WHERE crm.requirement_id = fr.id) AS has_control " +
                "FROM framework_requirements fr " +
                "WHERE fr.framework_id = ?::uuid " +
                "ORDER BY fr.section_code"
            );
            p.setString(1, frameworkId);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject req = new JSONObject();
                req.put("id",           rs.getString("id"));
                req.put("section_code", rs.getString("section_code"));
                req.put("title",        rs.getString("title"));
                req.put("description",  rs.getString("description"));
                req.put("has_control",  rs.getBoolean("has_control"));
                requirements.add(req);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch (Exception i) {} }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("requirements", requirements);
        return result;
    }

    // -------------------------------------------------------------------------
    // Import selected framework requirements as controls
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void importFrameworkControls(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String frameworkId = (String) input.get("framework_id");
        JSONArray reqIds   = (JSONArray) input.get("requirement_ids");
        if (isBlank(frameworkId) || reqIds == null || reqIds.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "framework_id and requirement_ids are required", req.getRequestURI());
            return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            int imported = 0;
            for (Object obj : reqIds) {
                String reqId = (String) obj;

                // Fetch requirement details
                p = conn.prepareStatement("SELECT section_code, title, description FROM framework_requirements WHERE id = ?::uuid");
                p.setString(1, reqId);
                rs = p.executeQuery();
                if (!rs.next()) { try { rs.close(); p.close(); } catch (Exception i) {} rs = null; p = null; continue; }
                String code  = rs.getString("section_code");
                String title = rs.getString("title");
                String desc  = rs.getString("description");
                try { rs.close(); p.close(); } catch (Exception i) {} rs = null; p = null;

                // Find existing control by code, or create a new one
                p = conn.prepareStatement("SELECT id::text FROM controls WHERE code = ?");
                p.setString(1, code);
                rs = p.executeQuery();
                String controlId;
                if (rs.next()) {
                    controlId = rs.getString(1);
                    try { rs.close(); p.close(); } catch (Exception i) {} rs = null; p = null;
                } else {
                    try { rs.close(); p.close(); } catch (Exception i) {} rs = null; p = null;
                    p = conn.prepareStatement(
                        "INSERT INTO controls (code, title, description, type, frequency) " +
                        "VALUES (?, ?, ?, 'ADMINISTRATIVE', 'ANNUALLY') RETURNING id::text"
                    );
                    p.setString(1, code);
                    p.setString(2, title);
                    p.setString(3, desc);
                    rs = p.executeQuery();
                    rs.next();
                    controlId = rs.getString(1);
                    try { rs.close(); p.close(); } catch (Exception i) {} rs = null; p = null;
                    imported++;
                }

                // Link to requirement (idempotent)
                p = conn.prepareStatement(
                    "INSERT INTO control_requirement_mappings (control_id, requirement_id) " +
                    "VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING"
                );
                p.setString(1, controlId);
                p.setString(2, reqId);
                p.executeUpdate();
                try { p.close(); } catch (Exception i) {} p = null;
            }
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("imported", imported);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(rs, p, conn); } catch (Exception i) {}
        }
    }

    @SuppressWarnings("unchecked")
    private void deleteException(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("DELETE FROM exceptions WHERE id = ?::uuid");
            p.setString(1, id); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(null, p, conn); } catch (Exception i) {}
        }
    }

    @SuppressWarnings("unchecked")
    private void deleteControl(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI());
            return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("DELETE FROM control_requirement_mappings WHERE control_id = ?::uuid");
            p.setString(1, id); p.executeUpdate();
            try { p.close(); } catch (Exception i) {} p = null;
            p = conn.prepareStatement("DELETE FROM controls WHERE id = ?::uuid");
            p.setString(1, id); p.executeUpdate();
            JSONObject result = new JSONObject();
            result.put("success", true);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(null, p, conn); } catch (Exception i) {}
        }
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
