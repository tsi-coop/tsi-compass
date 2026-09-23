package org.tsicoop.compass.service.v1;

import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.EventLog;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.OutputProcessor;
import org.tsicoop.compass.framework.PoolDB;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.sql.*;

// 2d. Data Flow Mapping - records how data moves between systems/parties, including the
// ad-hoc channels (WhatsApp, personal email, USB) nobody formally manages. See
// docs/data-register-phase2-plan.md.
public class DataFlows implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");
            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing _func", req.getRequestURI()); return;
            }
            switch (func.toLowerCase()) {
                case "get_flow_metrics":  OutputProcessor.send(res, 200, getFlowMetrics());      break;
                case "list_data_flows":   OutputProcessor.send(res, 200, listDataFlows(input));  break;
                case "add_data_flow":     addDataFlow(req, res, input);                          break;
                case "update_data_flow":  updateDataFlow(req, res, input);                       break;
                case "delete_data_flow":  deleteDataFlow(req, res, input);                       break;
                case "list_data_assets":  OutputProcessor.send(res, 200, listDataAssets());       break;
                case "list_staff":        OutputProcessor.send(res, 200, listStaff());            break;
                default: OutputProcessor.errorResponse(res, 400, "Bad Request", "Unknown function: "+func, req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Internal Error", e.getMessage(), req.getRequestURI());
        }
    }

    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse r) { return "POST".equalsIgnoreCase(m); }

    @SuppressWarnings("unchecked")
    private JSONObject getFlowMetrics() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT COUNT(*) FROM data_flows"); rs = p.executeQuery();
            result.put("total_flows", rs.next() ? rs.getLong(1) : 0L);
            pool.cleanup(rs, p, null); rs = null; p = null;

            p = conn.prepareStatement("SELECT COUNT(*) FROM data_flows WHERE is_approved_channel = false");
            rs = p.executeQuery();
            result.put("unapproved_count", rs.next() ? rs.getLong(1) : 0L);
            pool.cleanup(rs, p, null); rs = null; p = null;

            p = conn.prepareStatement("SELECT COUNT(*) FROM data_flows WHERE is_approved_channel = true");
            rs = p.executeQuery();
            result.put("approved_count", rs.next() ? rs.getLong(1) : 0L);
            pool.cleanup(rs, p, null); rs = null; p = null;

            p = conn.prepareStatement("SELECT COUNT(*) FROM data_flows WHERE is_cross_border = true");
            rs = p.executeQuery();
            result.put("cross_border_count", rs.next() ? rs.getLong(1) : 0L);

        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        result.put("success", true); return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listDataFlows(JSONObject input) throws Exception {
        String channel = (String) input.get("channel");
        String linkedAssetId = (String) input.get("linked_asset_id");
        Boolean unapprovedOnly = Boolean.TRUE.equals(input.get("unapproved_only")) || "true".equalsIgnoreCase(String.valueOf(input.get("unapproved_only")));
        Boolean crossBorderOnly = Boolean.TRUE.equals(input.get("cross_border_only")) || "true".equalsIgnoreCase(String.valueOf(input.get("cross_border_only")));
        String search = (String) input.get("search");

        long page = 1L, limit = 20L;
        Object pageObj = input.get("page"); Object limitObj = input.get("limit");
        if (pageObj instanceof Long) page = (Long) pageObj;
        if (limitObj instanceof Long) limit = (Long) limitObj;
        if (limit > 100) limit = 100;
        if (page < 1) page = 1;
        long offset = (page - 1) * limit;

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (!isBlank(channel)) where.append(" AND f.channel = ?");
        if (!isBlank(linkedAssetId)) where.append(" AND (f.source_data_asset_id = ?::uuid OR f.destination_data_asset_id = ?::uuid)");
        if (unapprovedOnly) where.append(" AND f.is_approved_channel = false");
        if (crossBorderOnly) where.append(" AND f.is_cross_border = true");
        if (!isBlank(search)) where.append(" AND (f.name ILIKE ? OR f.source_label ILIKE ? OR f.destination_label ILIKE ?)");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        long total = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            String countSql = "SELECT COUNT(*) FROM data_flows f" + where;
            p = conn.prepareStatement(countSql);
            bindFilters(p, 1, channel, linkedAssetId, search);
            rs = p.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
            rs = null; p = null;

            String dataSql =
                "SELECT f.id::text, f.name, f.channel, f.is_approved_channel, f.is_cross_border, f.destination_country, " +
                "f.data_elements, f.frequency, f.description, f.created_at::text, " +
                "f.source_data_asset_id::text, sa.name AS source_asset_name, f.source_label, " +
                "f.destination_data_asset_id::text, da.name AS destination_asset_name, f.destination_label, " +
                "f.owner_id::text, u.username AS owner_name " +
                "FROM data_flows f " +
                "LEFT JOIN data_assets sa ON sa.id = f.source_data_asset_id " +
                "LEFT JOIN data_assets da ON da.id = f.destination_data_asset_id " +
                "LEFT JOIN users u ON u.id = f.owner_id" + where +
                " ORDER BY f.is_approved_channel ASC, f.created_at DESC LIMIT ? OFFSET ?";
            p = conn.prepareStatement(dataSql);
            int idx = bindFilters(p, 1, channel, linkedAssetId, search);
            p.setLong(idx++, limit); p.setLong(idx++, offset);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject f = new JSONObject();
                f.put("id", rs.getString("id"));
                f.put("name", rs.getString("name"));
                f.put("channel", rs.getString("channel"));
                f.put("is_approved_channel", rs.getBoolean("is_approved_channel"));
                f.put("is_cross_border", rs.getBoolean("is_cross_border"));
                f.put("destination_country", rs.getString("destination_country"));
                f.put("data_elements", rs.getString("data_elements"));
                f.put("frequency", rs.getString("frequency"));
                f.put("description", rs.getString("description"));
                f.put("created_at", rs.getString("created_at"));
                f.put("source_data_asset_id", rs.getString("source_data_asset_id"));
                f.put("source_asset_name", rs.getString("source_asset_name"));
                f.put("source_label", rs.getString("source_label"));
                f.put("destination_data_asset_id", rs.getString("destination_data_asset_id"));
                f.put("destination_asset_name", rs.getString("destination_asset_name"));
                f.put("destination_label", rs.getString("destination_label"));
                f.put("owner_id", rs.getString("owner_id"));
                f.put("owner_name", rs.getString("owner_name"));
                list.add(f);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("data_flows", list);
        result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
    }

    private int bindFilters(PreparedStatement p, int idx, String channel, String linkedAssetId, String search) throws SQLException {
        if (!isBlank(channel)) p.setString(idx++, channel);
        if (!isBlank(linkedAssetId)) { p.setString(idx++, linkedAssetId); p.setString(idx++, linkedAssetId); }
        if (!isBlank(search)) { String s = "%"+search+"%"; p.setString(idx++, s); p.setString(idx++, s); p.setString(idx++, s); }
        return idx;
    }

    @SuppressWarnings("unchecked")
    private void addDataFlow(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String name = (String) input.get("name");
        String channel = (String) input.get("channel");
        String sourceAssetId = (String) input.get("source_data_asset_id");
        String sourceLabel = (String) input.get("source_label");
        String destAssetId = (String) input.get("destination_data_asset_id");
        String destLabel = (String) input.get("destination_label");
        if (isBlank(name) || isBlank(channel)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "name and channel required", req.getRequestURI()); return;
        }
        if (isBlank(sourceAssetId) && isBlank(sourceLabel)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "source_data_asset_id or source_label required", req.getRequestURI()); return;
        }
        if (isBlank(destAssetId) && isBlank(destLabel)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "destination_data_asset_id or destination_label required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "INSERT INTO data_flows (name, source_data_asset_id, source_label, destination_data_asset_id, destination_label, " +
                "channel, is_approved_channel, data_elements, frequency, owner_id, description, is_cross_border, destination_country) " +
                "VALUES (?,?::uuid,?,?::uuid,?,?,COALESCE(?,false),?,?,?::uuid,?,COALESCE(?,false),?) RETURNING id::text");
            p.setString(1, name);
            p.setString(2, isBlank(sourceAssetId) ? null : sourceAssetId);
            p.setString(3, sourceLabel);
            p.setString(4, isBlank(destAssetId) ? null : destAssetId);
            p.setString(5, destLabel);
            p.setString(6, channel);
            p.setObject(7, input.get("is_approved_channel"));
            p.setString(8, (String) input.get("data_elements"));
            p.setString(9, (String) input.get("frequency"));
            String ownerId = (String) input.get("owner_id");
            p.setString(10, isBlank(ownerId) ? null : ownerId);
            p.setString(11, (String) input.get("description"));
            p.setObject(12, input.get("is_cross_border"));
            p.setString(13, (String) input.get("destination_country"));
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void updateDataFlow(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "UPDATE data_flows SET name=COALESCE(?,name), channel=COALESCE(?,channel), " +
                "source_data_asset_id=COALESCE(?::uuid,source_data_asset_id), source_label=COALESCE(?,source_label), " +
                "destination_data_asset_id=COALESCE(?::uuid,destination_data_asset_id), destination_label=COALESCE(?,destination_label), " +
                "is_approved_channel=COALESCE(?,is_approved_channel), data_elements=COALESCE(?,data_elements), " +
                "frequency=COALESCE(?,frequency), owner_id=COALESCE(?::uuid,owner_id), description=COALESCE(?,description), " +
                "is_cross_border=COALESCE(?,is_cross_border), destination_country=COALESCE(?,destination_country) WHERE id=?::uuid");
            p.setString(1, (String) input.get("name"));
            p.setString(2, (String) input.get("channel"));
            String sourceAssetId = (String) input.get("source_data_asset_id"); p.setString(3, isBlank(sourceAssetId) ? null : sourceAssetId);
            p.setString(4, (String) input.get("source_label"));
            String destAssetId = (String) input.get("destination_data_asset_id"); p.setString(5, isBlank(destAssetId) ? null : destAssetId);
            p.setString(6, (String) input.get("destination_label"));
            p.setObject(7, input.get("is_approved_channel"));
            p.setString(8, (String) input.get("data_elements"));
            p.setString(9, (String) input.get("frequency"));
            String ownerId = (String) input.get("owner_id"); p.setString(10, isBlank(ownerId) ? null : ownerId);
            p.setString(11, (String) input.get("description"));
            p.setObject(12, input.get("is_cross_border"));
            p.setString(13, (String) input.get("destination_country"));
            p.setString(14, id);
            p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void deleteDataFlow(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        if (!"ADMIN".equals(InputProcessor.getRole(req))) {
            OutputProcessor.errorResponse(res, 403, "Forbidden", "Admin role required", req.getRequestURI()); return;
        }
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT name FROM data_flows WHERE id=?::uuid");
            p.setString(1, id); rs = p.executeQuery();
            String name = rs.next() ? rs.getString("name") : id;
            pool.cleanup(rs, p, null); rs = null; p = null;
            p = conn.prepareStatement("DELETE FROM data_flows WHERE id=?::uuid");
            p.setString(1, id); p.executeUpdate();
            JSONObject ctx = new JSONObject(); ctx.put("data_flow_id", id); ctx.put("data_flow_name", name);
            EventLog.log(InputProcessor.getEmail(req), "DATA_FLOW_DELETED", ctx.toJSONString());
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private JSONObject listDataAssets() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray assets = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT id::text, name FROM data_assets ORDER BY name");
            rs = p.executeQuery();
            while (rs.next()) { JSONObject a=new JSONObject(); a.put("id",rs.getString(1)); a.put("name",rs.getString(2)); assets.add(a); }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("data_assets", assets); return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listStaff() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray staff = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT id::text, username FROM users WHERE status='ACTIVE' AND role NOT IN ('USER','SUPERVISOR') ORDER BY username");
            rs = p.executeQuery();
            while (rs.next()) { JSONObject s=new JSONObject(); s.put("id",rs.getString(1)); s.put("username",rs.getString(2)); staff.add(s); }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("staff", staff); return result;
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
