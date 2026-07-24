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

public class Risk implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");

            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing _func", req.getRequestURI());
                return;
            }

            switch (func.toLowerCase()) {
                case "get_risk_metrics":
                    OutputProcessor.send(res, 200, getRiskMetrics());
                    break;
                case "list_risks":
                    OutputProcessor.send(res, 200, listRisks(input));
                    break;
                case "add_risk":
                    addRisk(req, res, input);
                    break;
                case "update_risk":
                    updateRisk(req, res, input);
                    break;
                case "list_vulnerabilities":
                    OutputProcessor.send(res, 200, listVulnerabilities(input));
                    break;
                case "add_vulnerability":
                    addVulnerability(req, res, input);
                    break;
                case "update_vulnerability":
                    updateVulnerability(req, res, input);
                    break;
                case "list_engagements":
                    OutputProcessor.send(res, 200, listEngagements());
                    break;
                case "add_engagement":
                    addEngagement(req, res, input);
                    break;
                case "list_staff":
                    OutputProcessor.send(res, 200, listStaff());
                    break;
                case "bulk_add_risks":
                    bulkAddRisks(req, res, input);
                    break;
                case "bulk_add_vulnerabilities":
                    bulkAddVulnerabilities(req, res, input);
                    break;
                case "delete_risk":
                    deleteRisk(req, res, input);
                    break;
                case "delete_vulnerability":
                    deleteVulnerability(req, res, input);
                    break;
                default:
                    OutputProcessor.errorResponse(res, 400, "Bad Request", "Unknown function: " + func, req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Internal Error", e.getMessage(), req.getRequestURI());
        }
    }

    @Override
    public boolean validate(String method, HttpServletRequest req, HttpServletResponse res) {
        return "POST".equalsIgnoreCase(method);
    }

    // -------------------------------------------------------------------------
    // Hub Metrics
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject getRiskMetrics() throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            pstmt = conn.prepareStatement(
                "SELECT " +
                "COUNT(*) FILTER (WHERE status != 'RETIRED') AS open_risks, " +
                "COUNT(*) FILTER (WHERE inherent_risk_score >= 15 AND status != 'RETIRED') AS high_severity, " +
                "COUNT(*) FILTER (WHERE status = 'TREATED') AS treated, " +
                "COUNT(*) FILTER (WHERE status = 'MONITORED') AS monitored " +
                "FROM risks"
            );
            rs = pstmt.executeQuery();
            if (rs.next()) {
                result.put("open_risks",         rs.getLong("open_risks"));
                result.put("high_severity_risks", rs.getLong("high_severity"));
                result.put("treated_risks",       rs.getLong("treated"));
                result.put("monitored_risks",     rs.getLong("monitored"));
            }
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            pstmt = conn.prepareStatement(
                "SELECT " +
                "COUNT(*) FILTER (WHERE status NOT IN ('RESOLVED','FALSE_POSITIVE')) AS open_vulns, " +
                "COUNT(*) FILTER (WHERE sla_deadline < CURRENT_TIMESTAMP AND status NOT IN ('RESOLVED','FALSE_POSITIVE')) AS overdue, " +
                "COUNT(*) FILTER (WHERE severity = 'CRITICAL' AND status NOT IN ('RESOLVED','FALSE_POSITIVE')) AS critical, " +
                "COUNT(*) FILTER (WHERE severity = 'HIGH' AND status NOT IN ('RESOLVED','FALSE_POSITIVE')) AS high, " +
                "COUNT(*) FILTER (WHERE severity IN ('MEDIUM','LOW') AND status NOT IN ('RESOLVED','FALSE_POSITIVE')) AS medium_low, " +
                "COUNT(*) FILTER (WHERE status = 'RESOLVED' AND DATE_TRUNC('year', created_at) = DATE_TRUNC('year', CURRENT_DATE)) AS resolved_ytd " +
                "FROM vulnerabilities"
            );
            rs = pstmt.executeQuery();
            if (rs.next()) {
                result.put("vapt_findings",    rs.getLong("open_vulns"));
                result.put("overdue_vulns",    rs.getLong("overdue"));
                result.put("critical_vulns",   rs.getLong("critical"));
                result.put("high_vulns",       rs.getLong("high"));
                result.put("medium_low_vulns", rs.getLong("medium_low"));
                result.put("resolved_ytd",     rs.getLong("resolved_ytd"));
            }

        } finally {
            if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
        }
        result.put("success", true);
        return result;
    }

    // -------------------------------------------------------------------------
    // Risks
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject listRisks(JSONObject input) throws Exception {
        String status = (String) input.get("status");
        StringBuilder sql = new StringBuilder(
            "SELECT r.id::text, r.risk_code, r.title, r.description, r.category, " +
            "r.inherent_impact, r.inherent_likelihood, r.inherent_risk_score, " +
            "r.treatment_strategy, r.mitigating_controls, " +
            "r.residual_impact, r.residual_likelihood, " +
            "r.status, r.created_at::text, " +
            "r.owner_id::text, u.username AS owner_name " +
            "FROM risks r " +
            "LEFT JOIN users u ON u.id = r.owner_id " +
            "WHERE 1=1"
        );
        if (!isBlank(status)) sql.append(" AND r.status = ?");
        else sql.append(" AND r.status != 'RETIRED'");
        sql.append(" ORDER BY r.inherent_risk_score DESC, r.created_at DESC");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONArray risks = new JSONArray();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sql.toString());
            if (!isBlank(status)) pstmt.setString(1, status);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject r = new JSONObject();
                r.put("id",                  rs.getString("id"));
                r.put("risk_code",           rs.getString("risk_code"));
                r.put("title",               rs.getString("title"));
                r.put("description",         rs.getString("description"));
                r.put("category",            rs.getString("category"));
                r.put("inherent_impact",     (long) rs.getInt("inherent_impact"));
                r.put("inherent_likelihood", (long) rs.getInt("inherent_likelihood"));
                r.put("inherent_risk_score", (long) rs.getInt("inherent_risk_score"));
                r.put("treatment_strategy",  rs.getString("treatment_strategy"));
                r.put("mitigating_controls", rs.getString("mitigating_controls"));
                Object ri = rs.getObject("residual_impact");
                Object rl = rs.getObject("residual_likelihood");
                r.put("residual_impact",     ri != null ? (long) rs.getInt("residual_impact") : null);
                r.put("residual_likelihood", rl != null ? (long) rs.getInt("residual_likelihood") : null);
                r.put("status",              rs.getString("status"));
                r.put("created_at",          rs.getString("created_at"));
                r.put("owner_id",            rs.getString("owner_id"));
                r.put("owner_name",          rs.getString("owner_name"));
                risks.add(r);
            }
        } finally {
            if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("risks", risks);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void addRisk(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String title = (String) input.get("title");
        String description = (String) input.get("description");
        String category = (String) input.get("category");
        Object impactObj = input.get("inherent_impact");
        Object likelihoodObj = input.get("inherent_likelihood");
        String treatment = (String) input.get("treatment_strategy");
        String ownerId = (String) input.get("owner_id");

        if (isBlank(title) || isBlank(category) || impactObj == null || likelihoodObj == null) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "title, category, inherent_impact, and inherent_likelihood are required", req.getRequestURI());
            return;
        }
        int impact = ((Number) impactObj).intValue();
        int likelihood = ((Number) likelihoodObj).intValue();

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "INSERT INTO risks (title, description, category, inherent_impact, inherent_likelihood, treatment_strategy, owner_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id::text, risk_code"
            );
            pstmt.setString(1, title);
            pstmt.setString(2, isBlank(description) ? null : description);
            pstmt.setString(3, category);
            pstmt.setInt(4, impact);
            pstmt.setInt(5, likelihood);
            pstmt.setString(6, isBlank(treatment) ? null : treatment);
            if (isBlank(ownerId)) pstmt.setNull(7, Types.OTHER);
            else pstmt.setObject(7, UUID.fromString(ownerId));
            rs = pstmt.executeQuery();
            JSONObject result = new JSONObject();
            result.put("success", true);
            if (rs.next()) {
                result.put("id",        rs.getString(1));
                result.put("risk_code", rs.getString(2));
            }
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private void updateRisk(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI());
            return;
        }
        String title = (String) input.get("title");
        String description = (String) input.get("description");
        String category = (String) input.get("category");
        Object impactObj = input.get("inherent_impact");
        Object likelihoodObj = input.get("inherent_likelihood");
        String treatment = (String) input.get("treatment_strategy");
        String mitigatingControls = (String) input.get("mitigating_controls");
        Object resImpact = input.get("residual_impact");
        Object resLikelihood = input.get("residual_likelihood");
        String ownerId = (String) input.get("owner_id");
        String status = (String) input.get("status");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "UPDATE risks SET " +
                "title = COALESCE(?, title), " +
                "description = COALESCE(?, description), " +
                "category = COALESCE(?, category), " +
                "inherent_impact = COALESCE(?, inherent_impact), " +
                "inherent_likelihood = COALESCE(?, inherent_likelihood), " +
                "treatment_strategy = COALESCE(?, treatment_strategy), " +
                "mitigating_controls = COALESCE(?, mitigating_controls), " +
                "residual_impact = COALESCE(?, residual_impact), " +
                "residual_likelihood = COALESCE(?, residual_likelihood), " +
                "owner_id = COALESCE(?, owner_id), " +
                "status = COALESCE(?, status) " +
                "WHERE id = ?"
            );
            pstmt.setString(1, isBlank(title) ? null : title);
            pstmt.setString(2, isBlank(description) ? null : description);
            pstmt.setString(3, isBlank(category) ? null : category);
            if (impactObj != null) pstmt.setInt(4, ((Number) impactObj).intValue());
            else pstmt.setNull(4, Types.INTEGER);
            if (likelihoodObj != null) pstmt.setInt(5, ((Number) likelihoodObj).intValue());
            else pstmt.setNull(5, Types.INTEGER);
            pstmt.setString(6, isBlank(treatment) ? null : treatment);
            pstmt.setString(7, isBlank(mitigatingControls) ? null : mitigatingControls);
            if (resImpact != null) pstmt.setInt(8, ((Number) resImpact).intValue());
            else pstmt.setNull(8, Types.INTEGER);
            if (resLikelihood != null) pstmt.setInt(9, ((Number) resLikelihood).intValue());
            else pstmt.setNull(9, Types.INTEGER);
            if (isBlank(ownerId)) pstmt.setNull(10, Types.OTHER);
            else pstmt.setObject(10, UUID.fromString(ownerId));
            pstmt.setString(11, isBlank(status) ? null : status);
            pstmt.setObject(12, UUID.fromString(id));
            pstmt.executeUpdate();
            JSONObject result = new JSONObject();
            result.put("success", true);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Vulnerabilities
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject listVulnerabilities(JSONObject input) throws Exception {
        String engagementId = (String) input.get("engagement_id");
        String severity = (String) input.get("severity");
        String status = (String) input.get("status");
        String search = (String) input.get("search");

        StringBuilder sql = new StringBuilder(
            "SELECT v.id::text, v.vuln_code, v.title, v.description, v.source, v.severity, v.status, " +
            "v.affected_asset, v.sla_deadline::text, v.mitigation_details, v.created_at::text, " +
            "v.assignee_id::text, u.username AS assignee_name, " +
            "v.engagement_id::text, e.name AS engagement_name, " +
            "v.linked_risk_id::text " +
            "FROM vulnerabilities v " +
            "LEFT JOIN users u ON u.id = v.assignee_id " +
            "LEFT JOIN vapt_engagements e ON e.id = v.engagement_id " +
            "WHERE 1=1"
        );
        if (!isBlank(engagementId)) sql.append(" AND v.engagement_id = ?");
        if (!isBlank(severity)) sql.append(" AND v.severity = ?");
        if (!isBlank(status)) sql.append(" AND v.status = ?");
        if (!isBlank(search)) sql.append(" AND (v.title ILIKE ? OR v.description ILIKE ? OR v.affected_asset ILIKE ?)");
        sql.append(" ORDER BY CASE v.severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 WHEN 'LOW' THEN 4 END, v.sla_deadline ASC NULLS LAST LIMIT 100");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONArray vulns = new JSONArray();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sql.toString());
            int idx = 1;
            if (!isBlank(engagementId)) pstmt.setObject(idx++, UUID.fromString(engagementId));
            if (!isBlank(severity)) pstmt.setString(idx++, severity.toUpperCase());
            if (!isBlank(status)) pstmt.setString(idx++, status);
            if (!isBlank(search)) {
                String like = "%" + search + "%";
                pstmt.setString(idx++, like);
                pstmt.setString(idx++, like);
                pstmt.setString(idx++, like);
            }
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject v = new JSONObject();
                v.put("id",               rs.getString("id"));
                v.put("vuln_code",        rs.getString("vuln_code"));
                v.put("title",            rs.getString("title"));
                v.put("description",      rs.getString("description"));
                v.put("source",           rs.getString("source"));
                v.put("severity",         rs.getString("severity"));
                v.put("status",           rs.getString("status"));
                v.put("affected_asset",   rs.getString("affected_asset"));
                v.put("sla_deadline",     rs.getString("sla_deadline"));
                v.put("mitigation_details", rs.getString("mitigation_details"));
                v.put("created_at",       rs.getString("created_at"));
                v.put("assignee_id",      rs.getString("assignee_id"));
                v.put("assignee_name",    rs.getString("assignee_name"));
                v.put("engagement_id",    rs.getString("engagement_id"));
                v.put("engagement_name",  rs.getString("engagement_name"));
                v.put("linked_risk_id",   rs.getString("linked_risk_id"));
                vulns.add(v);
            }
        } finally {
            if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("vulnerabilities", vulns);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void addVulnerability(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String title = (String) input.get("title");
        String description = (String) input.get("description");
        String source = (String) input.get("source");
        String severity = (String) input.get("severity");
        String engagementId = (String) input.get("engagement_id");
        String affectedAsset = (String) input.get("affected_asset");
        String slaDeadline = (String) input.get("sla_deadline");
        String assigneeId = (String) input.get("assignee_id");

        if (isBlank(title) || isBlank(severity) || isBlank(slaDeadline)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "title, severity, and sla_deadline are required", req.getRequestURI());
            return;
        }

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "INSERT INTO vulnerabilities (title, description, source, severity, engagement_id, affected_asset, sla_deadline, assignee_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?::date, ?) RETURNING id::text, vuln_code"
            );
            pstmt.setString(1, title);
            pstmt.setString(2, isBlank(description) ? null : description);
            pstmt.setString(3, isBlank(source) ? "Manual" : source);
            pstmt.setString(4, severity.toUpperCase());
            if (isBlank(engagementId)) pstmt.setNull(5, Types.OTHER);
            else pstmt.setObject(5, UUID.fromString(engagementId));
            pstmt.setString(6, isBlank(affectedAsset) ? null : affectedAsset);
            pstmt.setString(7, slaDeadline);
            if (isBlank(assigneeId)) pstmt.setNull(8, Types.OTHER);
            else pstmt.setObject(8, UUID.fromString(assigneeId));
            rs = pstmt.executeQuery();
            JSONObject result = new JSONObject();
            result.put("success", true);
            if (rs.next()) {
                result.put("id",        rs.getString(1));
                result.put("vuln_code", rs.getString(2));
            }
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private void updateVulnerability(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI());
            return;
        }
        String title = (String) input.get("title");
        String severity = (String) input.get("severity");
        String status = (String) input.get("status");
        String assigneeId = (String) input.get("assignee_id");
        String mitigationDetails = (String) input.get("mitigation_details");
        String affectedAsset = (String) input.get("affected_asset");
        String engagementId = (String) input.get("engagement_id");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "UPDATE vulnerabilities SET " +
                "title = COALESCE(?, title), " +
                "severity = COALESCE(?, severity), " +
                "status = COALESCE(?, status), " +
                "assignee_id = COALESCE(?, assignee_id), " +
                "mitigation_details = COALESCE(?, mitigation_details), " +
                "affected_asset = COALESCE(?, affected_asset), " +
                "engagement_id = COALESCE(?, engagement_id) " +
                "WHERE id = ?"
            );
            pstmt.setString(1, isBlank(title) ? null : title);
            pstmt.setString(2, isBlank(severity) ? null : severity.toUpperCase());
            pstmt.setString(3, isBlank(status) ? null : status);
            if (isBlank(assigneeId)) pstmt.setNull(4, Types.OTHER);
            else pstmt.setObject(4, UUID.fromString(assigneeId));
            pstmt.setString(5, isBlank(mitigationDetails) ? null : mitigationDetails);
            pstmt.setString(6, isBlank(affectedAsset) ? null : affectedAsset);
            if (isBlank(engagementId)) pstmt.setNull(7, Types.OTHER);
            else pstmt.setObject(7, UUID.fromString(engagementId));
            pstmt.setObject(8, UUID.fromString(id));
            pstmt.executeUpdate();
            JSONObject result = new JSONObject();
            result.put("success", true);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Engagements
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject listEngagements() throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONArray engagements = new JSONArray();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "SELECT e.id::text, e.name, e.type, e.vendor, e.scope, " +
                "e.start_date::text, e.end_date::text, e.status, " +
                "COUNT(v.id) FILTER (WHERE v.severity = 'CRITICAL' AND v.status NOT IN ('RESOLVED','FALSE_POSITIVE')) AS critical_count, " +
                "COUNT(v.id) FILTER (WHERE v.severity = 'HIGH' AND v.status NOT IN ('RESOLVED','FALSE_POSITIVE')) AS high_count, " +
                "COUNT(v.id) FILTER (WHERE v.severity IN ('MEDIUM','LOW') AND v.status NOT IN ('RESOLVED','FALSE_POSITIVE')) AS medium_low_count " +
                "FROM vapt_engagements e " +
                "LEFT JOIN vulnerabilities v ON v.engagement_id = e.id " +
                "GROUP BY e.id, e.name, e.type, e.vendor, e.scope, e.start_date, e.end_date, e.status " +
                "ORDER BY e.start_date DESC NULLS LAST"
            );
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject e = new JSONObject();
                e.put("id",               rs.getString("id"));
                e.put("name",             rs.getString("name"));
                e.put("type",             rs.getString("type"));
                e.put("vendor",           rs.getString("vendor"));
                e.put("scope",            rs.getString("scope"));
                e.put("start_date",       rs.getString("start_date"));
                e.put("end_date",         rs.getString("end_date"));
                e.put("status",           rs.getString("status"));
                e.put("critical_count",   rs.getLong("critical_count"));
                e.put("high_count",       rs.getLong("high_count"));
                e.put("medium_low_count", rs.getLong("medium_low_count"));
                engagements.add(e);
            }
        } finally {
            if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("engagements", engagements);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void addEngagement(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String name = (String) input.get("name");
        String type = (String) input.get("type");
        String vendor = (String) input.get("vendor");
        String scope = (String) input.get("scope");
        String startDate = (String) input.get("start_date");
        String endDate = (String) input.get("end_date");

        if (isBlank(name) || isBlank(type)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "name and type are required", req.getRequestURI());
            return;
        }

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "INSERT INTO vapt_engagements (name, type, vendor, scope, start_date, end_date) " +
                "VALUES (?, ?, ?, ?, ?::date, ?::date) RETURNING id::text"
            );
            pstmt.setString(1, name);
            pstmt.setString(2, type.toUpperCase().replace(' ', '_'));
            pstmt.setString(3, isBlank(vendor) ? null : vendor);
            pstmt.setString(4, isBlank(scope) ? null : scope);
            pstmt.setString(5, isBlank(startDate) ? null : startDate);
            pstmt.setString(6, isBlank(endDate) ? null : endDate);
            rs = pstmt.executeQuery();
            JSONObject result = new JSONObject();
            result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Staff
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject listStaff() throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONArray staff = new JSONArray();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "SELECT id::text, username FROM users WHERE status = 'ACTIVE' AND role != 'USER' ORDER BY username"
            );
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject s = new JSONObject();
                s.put("id", rs.getString(1));
                s.put("username", rs.getString(2));
                staff.add(s);
            }
        } finally {
            if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("staff", staff);
        return result;
    }

    // -------------------------------------------------------------------------
    // Bulk Import — Risk Register
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void bulkAddRisks(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        JSONArray items = (JSONArray) input.get("items");
        if (items == null || items.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "items array is required", req.getRequestURI());
            return;
        }
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            int inserted = 0;
            for (Object obj : items) {
                JSONObject row = (JSONObject) obj;
                String title    = (String) row.get("title");
                String category = (String) row.get("category");
                Object impactObj     = row.get("inherent_impact");
                Object likelihoodObj = row.get("inherent_likelihood");
                if (isBlank(title) || isBlank(category) || impactObj == null || likelihoodObj == null) continue;
                int impact     = ((Number) impactObj).intValue();
                int likelihood = ((Number) likelihoodObj).intValue();
                String description = (String) row.get("description");
                String treatment   = (String) row.get("treatment_strategy");
                String ownerId     = (String) row.get("owner_id");
                pstmt = conn.prepareStatement(
                    "INSERT INTO risks (title, description, category, inherent_impact, inherent_likelihood, treatment_strategy, owner_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)"
                );
                pstmt.setString(1, title);
                pstmt.setString(2, isBlank(description) ? null : description);
                pstmt.setString(3, category);
                pstmt.setInt(4, impact);
                pstmt.setInt(5, likelihood);
                pstmt.setString(6, isBlank(treatment) ? null : treatment);
                if (isBlank(ownerId)) pstmt.setNull(7, Types.OTHER);
                else pstmt.setObject(7, UUID.fromString(ownerId));
                pstmt.executeUpdate();
                try { pstmt.close(); } catch (Exception ignored) {}
                pstmt = null;
                inserted++;
            }
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("inserted", inserted);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Bulk Import — VAPT Findings
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void bulkAddVulnerabilities(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        JSONArray items = (JSONArray) input.get("items");
        if (items == null || items.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "items array is required", req.getRequestURI());
            return;
        }
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            int inserted = 0;
            for (Object obj : items) {
                JSONObject row = (JSONObject) obj;
                String title       = (String) row.get("title");
                String severity    = (String) row.get("severity");
                String slaDeadline = (String) row.get("sla_deadline");
                if (isBlank(title) || isBlank(severity) || isBlank(slaDeadline)) continue;
                String description  = (String) row.get("description");
                String source       = (String) row.get("source");
                String affectedAsset = (String) row.get("affected_asset");
                String assigneeId   = (String) row.get("assignee_id");
                pstmt = conn.prepareStatement(
                    "INSERT INTO vulnerabilities (title, description, source, severity, affected_asset, sla_deadline, assignee_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?::date, ?)"
                );
                pstmt.setString(1, title);
                pstmt.setString(2, isBlank(description) ? null : description);
                pstmt.setString(3, isBlank(source) ? "Excel Import" : source);
                pstmt.setString(4, severity.toUpperCase());
                pstmt.setString(5, isBlank(affectedAsset) ? null : affectedAsset);
                pstmt.setString(6, slaDeadline);
                if (isBlank(assigneeId)) pstmt.setNull(7, Types.OTHER);
                else pstmt.setObject(7, UUID.fromString(assigneeId));
                pstmt.executeUpdate();
                try { pstmt.close(); } catch (Exception ignored) {}
                pstmt = null;
                inserted++;
            }
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("inserted", inserted);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Delete — Risk
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void deleteRisk(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI());
            return;
        }
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement("DELETE FROM risks WHERE id = ?::uuid");
            pstmt.setString(1, id);
            pstmt.executeUpdate();
            JSONObject result = new JSONObject();
            result.put("success", true);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Delete — Vulnerability
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void deleteVulnerability(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI());
            return;
        }
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement("DELETE FROM vulnerabilities WHERE id = ?::uuid");
            pstmt.setString(1, id);
            pstmt.executeUpdate();
            JSONObject result = new JSONObject();
            result.put("success", true);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
