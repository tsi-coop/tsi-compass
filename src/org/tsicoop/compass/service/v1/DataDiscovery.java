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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

// Data Discovery. v1 matches column names from an uploaded information_schema export against
// discovery_rules and proposes Data Register entries. Nothing is written to data_assets until a
// finding is accepted. No connection is made to the source system and no values are read.
// See docs/guides/data-governance-guide.md and docs/architecture/system-design.md section 5.2.
public class DataDiscovery implements Action {

    private static final int MAX_COLUMNS = 50000;
    private static final String[] SOURCE_TYPES = {"POSTGRES", "MYSQL", "MSSQL", "ORACLE", "OTHER"};
    private static final Pattern CAMEL = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private static class Rule {
        String code, label, category, sensitivity, confidence;
        Pattern name, exclude;
    }

    private static class Hit {
        Rule rule;
        String confidence;
    }

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");
            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing _func", req.getRequestURI()); return;
            }
            switch (func.toLowerCase()) {
                case "get_discovery_metrics": OutputProcessor.send(res, 200, getMetrics());           break;
                case "list_sources":          OutputProcessor.send(res, 200, listSources());          break;
                case "update_source":         updateSource(req, res, input);                          break;
                case "delete_source":         deleteSource(req, res, input);                          break;
                case "import_schema":         importSchema(req, res, input);                          break;
                case "list_runs":             OutputProcessor.send(res, 200, listRuns(input));        break;
                case "list_findings":         OutputProcessor.send(res, 200, listFindings(input));    break;
                case "accept_findings":       acceptFindings(req, res, input);                        break;
                case "reject_findings":       setReview(req, res, input, "REJECTED");                 break;
                case "reopen_findings":       setReview(req, res, input, "NEW");                      break;
                case "list_rules":            OutputProcessor.send(res, 200, listRules());            break;
                case "update_rule":           updateRule(req, res, input);                            break;
                default: OutputProcessor.errorResponse(res, 400, "Bad Request", "Unknown function: " + func, req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Internal Error", e.getMessage(), req.getRequestURI());
        }
    }

    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse r) { return "POST".equalsIgnoreCase(m); }

    // ---------- Detection ----------

    /** camelCase to snake_case, lower-cased, non-alphanumerics collapsed to a single underscore. */
    static String normalise(String column) {
        String s = CAMEL.matcher(column.trim()).replaceAll("$1_$2").toLowerCase();
        s = NON_ALNUM.matcher(s).replaceAll("_");
        return s.replaceAll("^_+|_+$", "");
    }

    private List<Rule> loadRules(Connection conn) throws Exception {
        List<Rule> rules = new ArrayList<>();
        PreparedStatement p = null; ResultSet rs = null;
        try {
            p = conn.prepareStatement(
                "SELECT code, label, name_regex, exclude_regex, category, sensitivity, base_confidence " +
                "FROM discovery_rules WHERE enabled = TRUE ORDER BY priority");
            rs = p.executeQuery();
            while (rs.next()) {
                Rule r = new Rule();
                r.code = rs.getString("code"); r.label = rs.getString("label");
                r.category = rs.getString("category"); r.sensitivity = rs.getString("sensitivity");
                r.confidence = rs.getString("base_confidence");
                try {
                    r.name = Pattern.compile(rs.getString("name_regex"), Pattern.CASE_INSENSITIVE);
                    String ex = rs.getString("exclude_regex");
                    r.exclude = isBlank(ex) ? null : Pattern.compile(ex, Pattern.CASE_INSENSITIVE);
                } catch (Exception badRegex) {
                    continue; // a rule with an invalid regex is skipped rather than failing the whole import
                }
                rules.add(r);
            }
        } finally { closeQuietly(rs, p); }
        return rules;
    }

    private Hit detect(List<Rule> rules, String column, String dataType) {
        String type = dataType == null ? "" : dataType.toLowerCase();
        // A flag such as has_aadhaar is not itself personal data.
        if (type.equals("boolean") || type.equals("bool") || type.equals("bit")) return null;
        String name = normalise(column);
        for (Rule r : rules) {
            if (!r.name.matcher(name).find()) continue;
            if (r.exclude != null && r.exclude.matcher(name).find()) continue;
            Hit h = new Hit();
            h.rule = r;
            h.confidence = r.confidence;
            // A date of birth held in a numeric or binary column is less likely to be what the name suggests.
            if ("DOB".equals(r.code) && !type.isEmpty() && !(type.contains("date") || type.contains("time") || type.contains("char") || type.contains("text"))) {
                h.confidence = "LOW";
            }
            return h;
        }
        return null;
    }

    // ---------- Import / scan ----------

    @SuppressWarnings("unchecked")
    private void importSchema(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String sourceName = trim((String) input.get("source_name"));
        Object cols = input.get("columns");
        if (isBlank(sourceName) || !(cols instanceof JSONArray)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "source_name and columns required", req.getRequestURI()); return;
        }
        JSONArray columns = (JSONArray) cols;
        if (columns.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "No columns in the import", req.getRequestURI()); return;
        }
        if (columns.size() > MAX_COLUMNS) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "Import is limited to " + MAX_COLUMNS + " columns; split it by schema", req.getRequestURI()); return;
        }
        if (sourceName.length() > 200) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "source_name is too long", req.getRequestURI()); return;
        }
        String sourceType = input.get("source_type") == null ? null : ((String) input.get("source_type")).toUpperCase();
        if (!isBlank(sourceType) && !isSourceType(sourceType)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "source_type must be one of POSTGRES, MYSQL, MSSQL, ORACLE, OTHER", req.getRequestURI()); return;
        }
        String location = trim((String) input.get("location"));
        UUID user = InputProcessor.getAuthenticatedUserId(req);

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONObject result = new JSONObject();
        String sourceId, runId;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            conn.setAutoCommit(false);
            try {
                List<Rule> rules = loadRules(conn);

                p = conn.prepareStatement(
                    "INSERT INTO discovery_sources (name, source_type, location, owner_id) VALUES (?, COALESCE(?,'OTHER'), ?, ?::uuid) " +
                    "ON CONFLICT (name) DO UPDATE SET source_type = COALESCE(?, discovery_sources.source_type), " +
                    "location = COALESCE(?, discovery_sources.location) RETURNING id::text");
                p.setString(1, sourceName); p.setString(2, isBlank(sourceType) ? null : sourceType);
                p.setString(3, isBlank(location) ? null : location); p.setString(4, user == null ? null : user.toString());
                p.setString(5, isBlank(sourceType) ? null : sourceType); p.setString(6, isBlank(location) ? null : location);
                rs = p.executeQuery(); rs.next(); sourceId = rs.getString(1);
                closeQuietly(rs, p); rs = null; p = null;

                p = conn.prepareStatement(
                    "INSERT INTO discovery_runs (source_id, mode, status, started_by) VALUES (?::uuid, 'SCHEMA_IMPORT', 'RUNNING', ?::uuid) RETURNING id::text");
                p.setString(1, sourceId); p.setString(2, user == null ? null : user.toString());
                rs = p.executeQuery(); rs.next(); runId = rs.getString(1);
                closeQuietly(rs, p); rs = null; p = null;

                // Insert a new finding, refresh one still awaiting review, and leave accepted / rejected ones alone.
                // (xmax = 0) is true only for a freshly inserted row; no row comes back when the conflict update is skipped.
                String upsert =
                    "INSERT INTO discovery_findings (source_id, run_id, schema_name, table_name, column_name, data_type, detected_type, " +
                    "suggested_category, suggested_sensitivity, confidence) VALUES (?::uuid,?::uuid,?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT (source_id, schema_name, table_name, column_name) DO UPDATE SET run_id = EXCLUDED.run_id, " +
                    "data_type = EXCLUDED.data_type, detected_type = EXCLUDED.detected_type, suggested_category = EXCLUDED.suggested_category, " +
                    "suggested_sensitivity = EXCLUDED.suggested_sensitivity, confidence = EXCLUDED.confidence, last_seen_at = CURRENT_TIMESTAMP " +
                    "WHERE discovery_findings.status = 'NEW' RETURNING (xmax = 0) AS inserted";
                p = conn.prepareStatement(upsert);

                Set<String> tables = new HashSet<>();
                long scanned = 0, skipped = 0, created = 0, refreshed = 0, unchanged = 0;
                for (Object o : columns) {
                    if (!(o instanceof JSONObject)) { skipped++; continue; }
                    JSONObject c = (JSONObject) o;
                    String schema = trim((String) c.get("schema_name"));
                    String table = trim((String) c.get("table_name"));
                    String column = trim((String) c.get("column_name"));
                    String dataType = trim((String) c.get("data_type"));
                    if (schema == null) schema = "";
                    if (isBlank(table) || isBlank(column) || schema.length() > 255 || table.length() > 255 || column.length() > 255) { skipped++; continue; }
                    scanned++;
                    tables.add(schema + "\u0000" + table);
                    Hit hit = detect(rules, column, dataType);
                    if (hit == null) continue;
                    p.setString(1, sourceId); p.setString(2, runId); p.setString(3, schema); p.setString(4, table); p.setString(5, column);
                    p.setString(6, dataType == null ? null : (dataType.length() > 100 ? dataType.substring(0, 100) : dataType));
                    p.setString(7, hit.rule.code); p.setString(8, hit.rule.category); p.setString(9, hit.rule.sensitivity); p.setString(10, hit.confidence);
                    rs = p.executeQuery();
                    if (rs.next()) { if (rs.getBoolean(1)) created++; else refreshed++; } else unchanged++;
                    rs.close(); rs = null;
                }
                closeQuietly(rs, p); rs = null; p = null;

                p = conn.prepareStatement(
                    "UPDATE discovery_runs SET status='COMPLETED', tables_scanned=?, columns_scanned=?, findings_new=?, findings_updated=?, " +
                    "findings_unchanged=?, finished_at=CURRENT_TIMESTAMP WHERE id=?::uuid");
                p.setInt(1, tables.size()); p.setInt(2, (int) scanned); p.setInt(3, (int) created); p.setInt(4, (int) refreshed);
                p.setInt(5, (int) unchanged); p.setString(6, runId); p.executeUpdate();
                closeQuietly(null, p); p = null;

                p = conn.prepareStatement("UPDATE discovery_sources SET last_scanned_at = CURRENT_TIMESTAMP WHERE id=?::uuid");
                p.setString(1, sourceId); p.executeUpdate();
                closeQuietly(null, p); p = null;

                conn.commit();
                result.put("success", true); result.put("source_id", sourceId); result.put("run_id", runId);
                result.put("tables_scanned", (long) tables.size()); result.put("columns_scanned", scanned);
                result.put("findings_new", created); result.put("findings_updated", refreshed);
                result.put("findings_unchanged", unchanged); result.put("rows_skipped", skipped);
            } catch (Exception e) {
                try { conn.rollback(); } catch (Exception ignored) {}
                throw e;
            }
        } finally { if (pool != null) try { conn.setAutoCommit(true); } catch (Exception ignored) {} if (pool != null) try { pool.cleanup(rs, p, conn); } catch (Exception i) {} }
        JSONObject ctx = new JSONObject();
        ctx.put("source", sourceName); ctx.put("run_id", runId); ctx.put("mode", "SCHEMA_IMPORT");
        ctx.put("columns", result.get("columns_scanned")); ctx.put("new_findings", result.get("findings_new"));
        EventLog.log(InputProcessor.getEmail(req), "DISCOVERY_IMPORT", ctx.toJSONString());
        OutputProcessor.send(res, 200, result);
    }

    // ---------- Reads ----------

    @SuppressWarnings("unchecked")
    private JSONObject getMetrics() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT (SELECT COUNT(*) FROM discovery_sources), " +
                "(SELECT COUNT(*) FROM discovery_findings WHERE status='NEW'), " +
                "(SELECT COUNT(DISTINCT (source_id, schema_name, table_name)) FROM discovery_findings WHERE status='NEW'), " +
                "(SELECT COUNT(*) FROM discovery_findings WHERE status='ACCEPTED'), " +
                "(SELECT MAX(last_scanned_at)::text FROM discovery_sources)");
            rs = p.executeQuery();
            if (rs.next()) {
                result.put("source_count", rs.getLong(1)); result.put("new_findings", rs.getLong(2));
                result.put("new_tables", rs.getLong(3)); result.put("accepted_findings", rs.getLong(4));
                result.put("last_scanned_at", rs.getString(5));
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch (Exception i) {} }
        result.put("success", true);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listSources() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT s.id::text, s.name, s.source_type, s.location, s.description, s.last_scanned_at::text, s.created_at::text, u.username AS owner_name, " +
                "(SELECT COUNT(*) FROM discovery_findings f WHERE f.source_id = s.id AND f.status='NEW') AS new_count, " +
                "(SELECT COUNT(*) FROM discovery_findings f WHERE f.source_id = s.id AND f.status='ACCEPTED') AS accepted_count, " +
                "(SELECT COUNT(*) FROM discovery_findings f WHERE f.source_id = s.id AND f.status='REJECTED') AS rejected_count " +
                "FROM discovery_sources s LEFT JOIN users u ON u.id = s.owner_id ORDER BY s.name");
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject o = new JSONObject();
                o.put("id", rs.getString("id")); o.put("name", rs.getString("name")); o.put("source_type", rs.getString("source_type"));
                o.put("location", rs.getString("location")); o.put("description", rs.getString("description"));
                o.put("last_scanned_at", rs.getString("last_scanned_at")); o.put("created_at", rs.getString("created_at"));
                o.put("owner_name", rs.getString("owner_name"));
                o.put("new_count", rs.getLong("new_count")); o.put("accepted_count", rs.getLong("accepted_count"));
                o.put("rejected_count", rs.getLong("rejected_count"));
                list.add(o);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch (Exception i) {} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("sources", list);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listRuns(JSONObject input) throws Exception {
        String sourceId = (String) input.get("source_id");
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT r.id::text, r.source_id::text, s.name AS source_name, r.mode, r.status, r.tables_scanned, r.columns_scanned, " +
                "r.findings_new, r.findings_updated, r.findings_unchanged, r.error_message, r.started_at::text, r.finished_at::text, u.username AS started_by_name " +
                "FROM discovery_runs r JOIN discovery_sources s ON s.id = r.source_id LEFT JOIN users u ON u.id = r.started_by " +
                (isBlank(sourceId) ? "" : "WHERE r.source_id = ?::uuid ") + "ORDER BY r.started_at DESC LIMIT 100");
            if (!isBlank(sourceId)) p.setString(1, sourceId);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject o = new JSONObject();
                String[] strs = {"id", "source_id", "source_name", "mode", "status", "error_message", "started_at", "finished_at", "started_by_name"};
                for (String k : strs) o.put(k, rs.getString(k));
                String[] nums = {"tables_scanned", "columns_scanned", "findings_new", "findings_updated", "findings_unchanged"};
                for (String k : nums) o.put(k, rs.getLong(k));
                list.add(o);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch (Exception i) {} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("runs", list);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listFindings(JSONObject input) throws Exception {
        String sourceId = (String) input.get("source_id");
        String status = (String) input.get("status");
        String confidence = (String) input.get("confidence");
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (!isBlank(sourceId)) where.append(" AND f.source_id = ?::uuid");
        if (!isBlank(status)) where.append(" AND f.status = ?");
        if (!isBlank(confidence)) where.append(" AND f.confidence = ?");
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT f.id::text, f.source_id::text, s.name AS source_name, f.schema_name, f.table_name, f.column_name, f.data_type, " +
                "f.detected_type, COALESCE(r.label, f.detected_type) AS detected_label, f.suggested_category, f.suggested_sensitivity, " +
                "f.confidence, f.match_basis, f.status, f.data_asset_id::text, d.name AS asset_name, f.last_seen_at::text " +
                "FROM discovery_findings f JOIN discovery_sources s ON s.id = f.source_id " +
                "LEFT JOIN discovery_rules r ON r.code = f.detected_type LEFT JOIN data_assets d ON d.id = f.data_asset_id" + where +
                " ORDER BY CASE f.confidence WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END, " +
                "CASE f.suggested_sensitivity WHEN 'RESTRICTED' THEN 1 WHEN 'CONFIDENTIAL' THEN 2 WHEN 'INTERNAL' THEN 3 ELSE 4 END, " +
                "s.name, f.schema_name, f.table_name, f.column_name LIMIT 10000");
            int i = 1;
            if (!isBlank(sourceId)) p.setString(i++, sourceId);
            if (!isBlank(status)) p.setString(i++, status.toUpperCase());
            if (!isBlank(confidence)) p.setString(i++, confidence.toUpperCase());
            rs = p.executeQuery();
            String[] keys = {"id", "source_id", "source_name", "schema_name", "table_name", "column_name", "data_type", "detected_type",
                "detected_label", "suggested_category", "suggested_sensitivity", "confidence", "match_basis", "status", "data_asset_id",
                "asset_name", "last_seen_at"};
            while (rs.next()) {
                JSONObject o = new JSONObject();
                for (String k : keys) o.put(k, rs.getString(k));
                list.add(o);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch (Exception i) {} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("findings", list);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listRules() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT id::text, code, label, name_regex, exclude_regex, category, sensitivity, base_confidence, priority, enabled " +
                "FROM discovery_rules ORDER BY priority");
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject o = new JSONObject();
                String[] keys = {"id", "code", "label", "name_regex", "exclude_regex", "category", "sensitivity", "base_confidence"};
                for (String k : keys) o.put(k, rs.getString(k));
                o.put("priority", rs.getLong("priority")); o.put("enabled", rs.getBoolean("enabled"));
                list.add(o);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch (Exception i) {} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("rules", list);
        return result;
    }

    // ---------- Writes ----------

    @SuppressWarnings("unchecked")
    private void updateSource(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        String type = input.get("source_type") == null ? null : ((String) input.get("source_type")).toUpperCase();
        if (!isBlank(type) && !isSourceType(type)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "Invalid source_type", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        int n;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("UPDATE discovery_sources SET source_type=COALESCE(?,source_type), location=COALESCE(?,location), " +
                "description=COALESCE(?,description) WHERE id=?::uuid");
            p.setString(1, isBlank(type) ? null : type); p.setString(2, trim((String) input.get("location")));
            p.setString(3, trim((String) input.get("description"))); p.setString(4, id);
            n = p.executeUpdate();
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch (Exception i) {} }
        if (n == 0) { OutputProcessor.errorResponse(res, 404, "Not Found", "Source not found", req.getRequestURI()); return; }
        JSONObject ctx = new JSONObject(); ctx.put("source_id", id);
        EventLog.log(InputProcessor.getEmail(req), "DISCOVERY_SOURCE_UPDATED", ctx.toJSONString());
        JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
    }

    @SuppressWarnings("unchecked")
    private void deleteSource(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        int n;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            // Data Register entries created from this source are kept; only the source, its runs and findings go.
            p = conn.prepareStatement("DELETE FROM discovery_sources WHERE id=?::uuid");
            p.setString(1, id); n = p.executeUpdate();
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch (Exception i) {} }
        if (n == 0) { OutputProcessor.errorResponse(res, 404, "Not Found", "Source not found", req.getRequestURI()); return; }
        JSONObject ctx = new JSONObject(); ctx.put("source_id", id);
        EventLog.log(InputProcessor.getEmail(req), "DISCOVERY_SOURCE_DELETED", ctx.toJSONString());
        JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
    }

    private List<String> idList(JSONObject input) {
        List<String> ids = new ArrayList<>();
        Object o = input.get("ids");
        if (o instanceof JSONArray) {
            for (Object x : (JSONArray) o) if (x != null && !isBlank(x.toString())) ids.add(x.toString());
        }
        return ids;
    }

    private String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?::uuid" : ",?::uuid");
        return sb.toString();
    }

    /** Reject (NEW -> REJECTED) or reopen (REJECTED -> NEW) findings. Accepted findings are not changed here. */
    @SuppressWarnings("unchecked")
    private void setReview(HttpServletRequest req, HttpServletResponse res, JSONObject input, String target) throws Exception {
        List<String> ids = idList(input);
        if (ids.isEmpty()) { OutputProcessor.errorResponse(res, 400, "Bad Request", "ids required", req.getRequestURI()); return; }
        String from = "REJECTED".equals(target) ? "NEW" : "REJECTED";
        UUID user = InputProcessor.getAuthenticatedUserId(req);
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        int n;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("UPDATE discovery_findings SET status=?, reviewed_by=?::uuid, reviewed_at=CURRENT_TIMESTAMP " +
                "WHERE status=? AND id IN (" + placeholders(ids.size()) + ")");
            int i = 1;
            p.setString(i++, target); p.setString(i++, "NEW".equals(target) ? null : (user == null ? null : user.toString())); p.setString(i++, from);
            for (String id : ids) p.setString(i++, id);
            n = p.executeUpdate();
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch (Exception e) {} }
        JSONObject ctx = new JSONObject(); ctx.put("count", (long) n); ctx.put("status", target);
        EventLog.log(InputProcessor.getEmail(req), "REJECTED".equals(target) ? "DISCOVERY_FINDINGS_REJECTED" : "DISCOVERY_FINDINGS_REOPENED", ctx.toJSONString());
        JSONObject result = new JSONObject(); result.put("success", true); result.put("updated", (long) n);
        OutputProcessor.send(res, 200, result);
    }

    private static class TableGroup {
        String sourceName, sourceLocation, schema, table;
        List<String> findingIds = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        String category, sensitivity;
        int sensRank = -1, catRank = -1;
    }

    private static int sensRank(String s) {
        return "RESTRICTED".equals(s) ? 3 : "CONFIDENTIAL".equals(s) ? 2 : "INTERNAL".equals(s) ? 1 : 0;
    }
    private static int catRank(String c) {
        return "HEALTH".equals(c) ? 2 : "FINANCIAL".equals(c) ? 1 : 0;
    }

    /**
     * Turns NEW findings into Data Register entries, one per table. The entry is created as DISCOVERED with the
     * category and sensitivity of the most sensitive finding; if an entry with the same name already exists the
     * findings are linked to it and its description is extended instead.
     */
    @SuppressWarnings("unchecked")
    private void acceptFindings(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        List<String> ids = idList(input);
        if (ids.isEmpty()) { OutputProcessor.errorResponse(res, 400, "Bad Request", "ids required", req.getRequestURI()); return; }
        UUID user = InputProcessor.getAuthenticatedUserId(req);
        String userId = user == null ? null : user.toString();
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        long created = 0, linked = 0, accepted = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            conn.setAutoCommit(false);
            try {
                Map<String, TableGroup> groups = new LinkedHashMap<>();
                p = conn.prepareStatement(
                    "SELECT f.id::text, f.schema_name, f.table_name, f.column_name, f.detected_type, f.suggested_category, f.suggested_sensitivity, " +
                    "s.name AS source_name, s.location FROM discovery_findings f JOIN discovery_sources s ON s.id = f.source_id " +
                    "WHERE f.status = 'NEW' AND f.id IN (" + placeholders(ids.size()) + ") ORDER BY f.column_name");
                for (int i = 0; i < ids.size(); i++) p.setString(i + 1, ids.get(i));
                rs = p.executeQuery();
                while (rs.next()) {
                    String key = rs.getString("source_name") + "\u0000" + rs.getString("schema_name") + "\u0000" + rs.getString("table_name");
                    TableGroup g = groups.get(key);
                    if (g == null) {
                        g = new TableGroup();
                        g.sourceName = rs.getString("source_name"); g.sourceLocation = rs.getString("location");
                        g.schema = rs.getString("schema_name"); g.table = rs.getString("table_name");
                        groups.put(key, g);
                    }
                    g.findingIds.add(rs.getString("id"));
                    g.columns.add(rs.getString("column_name") + " (" + rs.getString("detected_type") + ")");
                    int sr = sensRank(rs.getString("suggested_sensitivity")), cr = catRank(rs.getString("suggested_category"));
                    if (sr > g.sensRank || (sr == g.sensRank && cr > g.catRank)) {
                        g.sensRank = sr; g.catRank = cr;
                        g.sensitivity = rs.getString("suggested_sensitivity"); g.category = rs.getString("suggested_category");
                    }
                }
                closeQuietly(rs, p); rs = null; p = null;

                if (groups.isEmpty()) {
                    conn.rollback();
                    OutputProcessor.errorResponse(res, 409, "Conflict", "No findings awaiting review among the selected ids", req.getRequestURI()); return;
                }

                for (TableGroup g : groups.values()) {
                    String assetName = g.sourceName + "." + (isBlank(g.schema) ? "" : g.schema + ".") + g.table;
                    if (assetName.length() > 255) assetName = assetName.substring(0, 255);
                    String note = "Discovered in " + g.sourceName + ", columns: " + String.join(", ", g.columns) + ".";

                    String assetId = null;
                    p = conn.prepareStatement("SELECT id::text FROM data_assets WHERE name = ? ORDER BY created_at LIMIT 1");
                    p.setString(1, assetName); rs = p.executeQuery();
                    if (rs.next()) assetId = rs.getString(1);
                    closeQuietly(rs, p); rs = null; p = null;

                    if (assetId == null) {
                        p = conn.prepareStatement(
                            "INSERT INTO data_assets (name, system_type, category, sensitivity, owner_id, location, description, discovery_status) " +
                            "VALUES (?, 'DATABASE', ?, ?, ?::uuid, ?, ?, 'DISCOVERED') RETURNING id::text");
                        p.setString(1, assetName); p.setString(2, g.category); p.setString(3, g.sensitivity);
                        p.setString(4, userId); p.setString(5, g.sourceLocation); p.setString(6, note);
                        rs = p.executeQuery(); rs.next(); assetId = rs.getString(1);
                        closeQuietly(rs, p); rs = null; p = null;
                        created++;
                    } else {
                        p = conn.prepareStatement("UPDATE data_assets SET description = CASE WHEN description IS NULL OR description = '' THEN ? ELSE description || E'\\n' || ? END WHERE id=?::uuid");
                        p.setString(1, note); p.setString(2, note); p.setString(3, assetId); p.executeUpdate();
                        closeQuietly(null, p); p = null;
                        linked++;
                    }

                    p = conn.prepareStatement("UPDATE discovery_findings SET status='ACCEPTED', data_asset_id=?::uuid, reviewed_by=?::uuid, reviewed_at=CURRENT_TIMESTAMP " +
                        "WHERE status='NEW' AND id IN (" + placeholders(g.findingIds.size()) + ")");
                    p.setString(1, assetId); p.setString(2, userId);
                    for (int i = 0; i < g.findingIds.size(); i++) p.setString(i + 3, g.findingIds.get(i));
                    accepted += p.executeUpdate();
                    closeQuietly(null, p); p = null;
                }
                conn.commit();
            } catch (Exception e) {
                try { conn.rollback(); } catch (Exception ignored) {}
                throw e;
            }
        } finally { if (pool != null) try { conn.setAutoCommit(true); } catch (Exception ignored) {} if (pool != null) try { pool.cleanup(rs, p, conn); } catch (Exception i) {} }
        JSONObject ctx = new JSONObject();
        ctx.put("findings", accepted); ctx.put("assets_created", created); ctx.put("assets_linked", linked);
        EventLog.log(InputProcessor.getEmail(req), "DISCOVERY_FINDINGS_ACCEPTED", ctx.toJSONString());
        JSONObject result = new JSONObject();
        result.put("success", true); result.put("accepted", accepted); result.put("assets_created", created); result.put("assets_linked", linked);
        OutputProcessor.send(res, 200, result);
    }

    @SuppressWarnings("unchecked")
    private void updateRule(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        if (!"ADMIN".equals(InputProcessor.getRole(req))) {
            OutputProcessor.errorResponse(res, 403, "Forbidden", "Only administrators can change detection rules.", req.getRequestURI()); return;
        }
        String id = (String) input.get("id");
        Object enabled = input.get("enabled");
        if (isBlank(id) || !(enabled instanceof Boolean)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id and enabled (boolean) required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        int n;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("UPDATE discovery_rules SET enabled=? WHERE id=?::uuid");
            p.setBoolean(1, (Boolean) enabled); p.setString(2, id); n = p.executeUpdate();
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch (Exception i) {} }
        if (n == 0) { OutputProcessor.errorResponse(res, 404, "Not Found", "Rule not found", req.getRequestURI()); return; }
        JSONObject ctx = new JSONObject(); ctx.put("rule_id", id); ctx.put("enabled", enabled);
        EventLog.log(InputProcessor.getEmail(req), "DISCOVERY_RULE_UPDATED", ctx.toJSONString());
        JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
    }

    // ---------- helpers ----------

    private boolean isSourceType(String v) {
        for (String t : SOURCE_TYPES) if (t.equals(v)) return true;
        return false;
    }

    private void closeQuietly(ResultSet rs, PreparedStatement p) {
        try { if (rs != null) rs.close(); } catch (Exception ignored) {}
        try { if (p != null) p.close(); } catch (Exception ignored) {}
    }

    private String trim(String s) { return s == null ? null : s.trim(); }
    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
