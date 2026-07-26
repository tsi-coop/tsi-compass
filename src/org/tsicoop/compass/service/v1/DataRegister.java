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
import java.util.UUID;

public class DataRegister implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");
            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing _func", req.getRequestURI()); return;
            }
            switch (func.toLowerCase()) {
                case "get_data_metrics":     OutputProcessor.send(res, 200, getDataMetrics());      break;
                case "list_data_assets":      OutputProcessor.send(res, 200, listDataAssets(input)); break;
                case "add_data_asset":        addDataAsset(req, res, input);                         break;
                case "update_data_asset":     updateDataAsset(req, res, input);                      break;
                case "update_classification":  updateClassification(req, res, input);                 break;
                case "delete_data_asset":     deleteDataAsset(req, res, input);                      break;
                case "import_data_assets":    importDataAssets(req, res, input);                     break;
                case "list_staff":            OutputProcessor.send(res, 200, listStaff());           break;
                case "list_it_assets":        OutputProcessor.send(res, 200, listItAssets());        break;
                case "list_vendors":          OutputProcessor.send(res, 200, listVendors());         break;
                default: OutputProcessor.errorResponse(res, 400, "Bad Request", "Unknown function: "+func, req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Internal Error", e.getMessage(), req.getRequestURI());
        }
    }

    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse r) { return "POST".equalsIgnoreCase(m); }

    @SuppressWarnings("unchecked")
    private JSONObject getDataMetrics() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT COUNT(*) FROM data_assets"); rs = p.executeQuery();
            result.put("total_data_assets", rs.next() ? rs.getLong(1) : 0L);
            pool.cleanup(rs, p, null); rs = null; p = null;

            p = conn.prepareStatement("SELECT COUNT(*) FROM data_assets WHERE sensitivity IN ('CONFIDENTIAL','RESTRICTED')");
            rs = p.executeQuery();
            result.put("high_sensitivity_count", rs.next() ? rs.getLong(1) : 0L);
            pool.cleanup(rs, p, null); rs = null; p = null;

            p = conn.prepareStatement("SELECT COUNT(*) FROM data_assets WHERE discovery_status = 'DISCOVERED'");
            rs = p.executeQuery();
            result.put("discovered_count", rs.next() ? rs.getLong(1) : 0L);
            pool.cleanup(rs, p, null); rs = null; p = null;

            p = conn.prepareStatement("SELECT COUNT(*) FROM data_assets WHERE discovery_status = 'REVIEWED'");
            rs = p.executeQuery();
            result.put("reviewed_count", rs.next() ? rs.getLong(1) : 0L);

        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        result.put("success", true); return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listDataAssets(JSONObject input) throws Exception {
        String category    = (String) input.get("category");
        String sensitivity = (String) input.get("sensitivity");
        String search       = (String) input.get("search");

        long page  = 1L;
        long limit = 20L;
        Object pageObj  = input.get("page");
        Object limitObj = input.get("limit");
        if (pageObj  instanceof Long) page  = (Long) pageObj;
        if (limitObj instanceof Long) limit = (Long) limitObj;
        if (limit > 100) limit = 100;
        if (page < 1) page = 1;
        long offset = (page - 1) * limit;

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (!isBlank(category))    where.append(" AND d.category = ?");
        if (!isBlank(sensitivity)) where.append(" AND d.sensitivity = ?");
        if (!isBlank(search))      where.append(" AND d.name ILIKE ?");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        long total = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            String countSql = "SELECT COUNT(*) FROM data_assets d" + where;
            p = conn.prepareStatement(countSql);
            int idx = 1;
            if (!isBlank(category))    p.setString(idx++, category);
            if (!isBlank(sensitivity)) p.setString(idx++, sensitivity);
            if (!isBlank(search))      p.setString(idx++, "%"+search+"%");
            rs = p.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
            rs = null; p = null;

            String dataSql =
                "SELECT d.id::text, d.name, d.system_type, d.category, d.sensitivity, d.location, d.volume_estimate, " +
                "d.description, d.discovery_status, d.last_reviewed_at::text, d.created_at::text, " +
                "d.owner_id::text, o.username AS owner_name, " +
                "d.reviewed_by::text, r.username AS reviewer_name, " +
                "d.linked_asset_id::text, a.name AS linked_asset_name, " +
                "d.linked_vendor_id::text, v.name AS linked_vendor_name " +
                "FROM data_assets d " +
                "LEFT JOIN users o ON o.id = d.owner_id " +
                "LEFT JOIN users r ON r.id = d.reviewed_by " +
                "LEFT JOIN assets a ON a.id = d.linked_asset_id " +
                "LEFT JOIN vendors v ON v.id = d.linked_vendor_id" + where +
                " ORDER BY CASE d.sensitivity WHEN 'RESTRICTED' THEN 1 WHEN 'CONFIDENTIAL' THEN 2 WHEN 'INTERNAL' THEN 3 ELSE 4 END, d.name" +
                " LIMIT ? OFFSET ?";
            p = conn.prepareStatement(dataSql);
            idx = 1;
            if (!isBlank(category))    p.setString(idx++, category);
            if (!isBlank(sensitivity)) p.setString(idx++, sensitivity);
            if (!isBlank(search))      p.setString(idx++, "%"+search+"%");
            p.setLong(idx++, limit); p.setLong(idx++, offset);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject d = new JSONObject();
                d.put("id",                  rs.getString("id"));
                d.put("name",                rs.getString("name"));
                d.put("system_type",         rs.getString("system_type"));
                d.put("category",            rs.getString("category"));
                d.put("sensitivity",         rs.getString("sensitivity"));
                d.put("location",            rs.getString("location"));
                d.put("volume_estimate",     rs.getString("volume_estimate"));
                d.put("description",         rs.getString("description"));
                d.put("discovery_status",    rs.getString("discovery_status"));
                d.put("last_reviewed_at",    rs.getString("last_reviewed_at"));
                d.put("created_at",          rs.getString("created_at"));
                d.put("owner_id",            rs.getString("owner_id"));
                d.put("owner_name",          rs.getString("owner_name"));
                d.put("reviewed_by",         rs.getString("reviewed_by"));
                d.put("reviewer_name",       rs.getString("reviewer_name"));
                d.put("linked_asset_id",     rs.getString("linked_asset_id"));
                d.put("linked_asset_name",   rs.getString("linked_asset_name"));
                d.put("linked_vendor_id",    rs.getString("linked_vendor_id"));
                d.put("linked_vendor_name",  rs.getString("linked_vendor_name"));
                list.add(d);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("data_assets", list);
        result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void addDataAsset(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String name   = (String) input.get("name");
        String sysType = (String) input.get("system_type");
        String category = (String) input.get("category");
        if (isBlank(name) || isBlank(sysType) || isBlank(category)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "name, system_type, category required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "INSERT INTO data_assets (name, system_type, category, owner_id, linked_asset_id, linked_vendor_id, location, volume_estimate, description) " +
                "VALUES (?,?,?,?::uuid,?::uuid,?::uuid,?,?,?) RETURNING id::text"
            );
            p.setString(1, name); p.setString(2, sysType); p.setString(3, category);
            String ownerId = (String) input.get("owner_id");
            p.setString(4, isBlank(ownerId) ? null : ownerId);
            String assetId = (String) input.get("linked_asset_id");
            p.setString(5, isBlank(assetId) ? null : assetId);
            String vendorId = (String) input.get("linked_vendor_id");
            p.setString(6, isBlank(vendorId) ? null : vendorId);
            p.setString(7, (String) input.get("location"));
            p.setString(8, (String) input.get("volume_estimate"));
            p.setString(9, (String) input.get("description"));
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void updateDataAsset(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "UPDATE data_assets SET name=COALESCE(?,name), system_type=COALESCE(?,system_type), " +
                "owner_id=COALESCE(?::uuid,owner_id), linked_asset_id=COALESCE(?::uuid,linked_asset_id), " +
                "linked_vendor_id=COALESCE(?::uuid,linked_vendor_id), location=COALESCE(?,location), " +
                "volume_estimate=COALESCE(?,volume_estimate), description=COALESCE(?,description) WHERE id=?::uuid"
            );
            p.setString(1, (String) input.get("name")); p.setString(2, (String) input.get("system_type"));
            String ownerId = (String) input.get("owner_id"); p.setString(3, isBlank(ownerId) ? null : ownerId);
            String assetId = (String) input.get("linked_asset_id"); p.setString(4, isBlank(assetId) ? null : assetId);
            String vendorId = (String) input.get("linked_vendor_id"); p.setString(5, isBlank(vendorId) ? null : vendorId);
            p.setString(6, (String) input.get("location"));
            p.setString(7, (String) input.get("volume_estimate"));
            p.setString(8, (String) input.get("description"));
            p.setString(9, id);
            p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void updateClassification(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        String category  = (String) input.get("category");
        String sensitivity = (String) input.get("sensitivity");
        String status     = (String) input.get("discovery_status");
        String reviewedBy  = (String) input.get("reviewed_by");
        boolean movingToReviewed = "REVIEWED".equalsIgnoreCase(status);
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            if (movingToReviewed) {
                p = conn.prepareStatement(
                    "UPDATE data_assets SET category=COALESCE(?,category), sensitivity=COALESCE(?,sensitivity), " +
                    "discovery_status=COALESCE(?,discovery_status), reviewed_by=?::uuid, last_reviewed_at=CURRENT_TIMESTAMP WHERE id=?::uuid"
                );
                p.setString(1, category); p.setString(2, sensitivity); p.setString(3, status);
                p.setString(4, isBlank(reviewedBy) ? null : reviewedBy);
                p.setString(5, id);
            } else {
                p = conn.prepareStatement(
                    "UPDATE data_assets SET category=COALESCE(?,category), sensitivity=COALESCE(?,sensitivity), " +
                    "discovery_status=COALESCE(?,discovery_status) WHERE id=?::uuid"
                );
                p.setString(1, category); p.setString(2, sensitivity); p.setString(3, status);
                p.setString(4, id);
            }
            p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void deleteDataAsset(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        if (!"ADMIN".equals(InputProcessor.getRole(req))) {
            OutputProcessor.errorResponse(res, 403, "Forbidden", "Admin role required", req.getRequestURI()); return;
        }
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT name FROM data_assets WHERE id=?::uuid");
            p.setString(1, id); rs = p.executeQuery();
            String name = rs.next() ? rs.getString("name") : id;
            pool.cleanup(rs, p, null); rs = null; p = null;
            p = conn.prepareStatement("DELETE FROM data_assets WHERE id=?::uuid");
            p.setString(1, id); p.executeUpdate();
            JSONObject ctx = new JSONObject(); ctx.put("data_asset_id", id); ctx.put("data_asset_name", name);
            EventLog.log(InputProcessor.getEmail(req), "DATA_ASSET_DELETED", ctx.toJSONString());
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void importDataAssets(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        JSONArray rows = (JSONArray) input.get("rows");
        if (rows == null || rows.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "rows array required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        int imported = 0; JSONArray errors = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "INSERT INTO data_assets (name, system_type, category, sensitivity, location, volume_estimate, description) VALUES (?, ?, ?, ?, ?, ?, ?)"
            );
            for (Object obj : rows) {
                JSONObject row = (JSONObject) obj;
                String name = strVal(row, "name"); String sysType = strVal(row, "system_type"); String category = strVal(row, "category");
                if (isBlank(name) || isBlank(sysType) || isBlank(category)) { errors.add("Row skipped: name, system_type and category required"); continue; }
                try {
                    p.setString(1, name); p.setString(2, sysType.toUpperCase()); p.setString(3, category.toUpperCase());
                    String sens = strVal(row, "sensitivity");
                    p.setString(4, isBlank(sens) ? "INTERNAL" : sens.toUpperCase());
                    p.setString(5, strVal(row, "location"));
                    p.setString(6, strVal(row, "volume_estimate"));
                    p.setString(7, strVal(row, "description"));
                    p.executeUpdate(); imported++;
                } catch (Exception ex) { errors.add("Row '"+name+"': "+ex.getMessage()); }
            }
            EventLog.log(InputProcessor.getEmail(req), "DATA_ASSETS_IMPORTED", "{\"count\":"+imported+"}");
            JSONObject result = new JSONObject(); result.put("success", true);
            result.put("imported", (long) imported); result.put("errors", errors);
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private JSONObject listStaff() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray staff = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT id::text, username FROM users WHERE status='ACTIVE' AND role != 'USER' ORDER BY username");
            rs = p.executeQuery();
            while (rs.next()) { JSONObject s=new JSONObject(); s.put("id",rs.getString(1)); s.put("username",rs.getString(2)); staff.add(s); }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("staff", staff); return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listItAssets() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray assets = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT id::text, name FROM assets ORDER BY name");
            rs = p.executeQuery();
            while (rs.next()) { JSONObject a=new JSONObject(); a.put("id",rs.getString(1)); a.put("name",rs.getString(2)); assets.add(a); }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("assets", assets); return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listVendors() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray vendors = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT id::text, name FROM vendors ORDER BY name");
            rs = p.executeQuery();
            while (rs.next()) { JSONObject v=new JSONObject(); v.put("id",rs.getString(1)); v.put("name",rs.getString(2)); vendors.add(v); }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("vendors", vendors); return result;
    }

    private String strVal(JSONObject obj, String key) {
        Object v = obj.get(key);
        return v == null ? null : v.toString().trim();
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
