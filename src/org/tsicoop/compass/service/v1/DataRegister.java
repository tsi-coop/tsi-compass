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

    // Reusable gap-count expression: how many of the four gap conditions a row fails.
    // Kept in one place since get_data_metrics, list_data_assets, and get_gap_assessment
    // all need it and must agree on what counts as a gap.
    private static final String GAP_COUNT_SQL =
        "( (CASE WHEN d.discovery_status <> 'REVIEWED' THEN 1 ELSE 0 END)" +
        " + (CASE WHEN (d.sensitivity IN ('CONFIDENTIAL','RESTRICTED') OR d.category = 'PII') AND d.processing_basis IS NULL THEN 1 ELSE 0 END)" +
        " + (CASE WHEN d.retention_period IS NULL AND d.deletion_trigger IS NULL THEN 1 ELSE 0 END)" +
        " + (CASE WHEN NOT EXISTS (SELECT 1 FROM data_asset_requirement_mappings m WHERE m.data_asset_id = d.id) THEN 1 ELSE 0 END)" +
        " )";

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");
            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing _func", req.getRequestURI()); return;
            }
            switch (func.toLowerCase()) {
                case "get_data_metrics":      OutputProcessor.send(res, 200, getDataMetrics());      break;
                case "list_data_assets":      OutputProcessor.send(res, 200, listDataAssets(input)); break;
                case "get_data_asset_detail": OutputProcessor.send(res, 200, getDataAssetDetail(input)); break;
                case "add_data_asset":        addDataAsset(req, res, input);                         break;
                case "update_data_asset":     updateDataAsset(req, res, input);                      break;
                case "update_classification":  updateClassification(req, res, input);                 break;
                case "delete_data_asset":     deleteDataAsset(req, res, input);                      break;
                case "import_data_assets":    importDataAssets(req, res, input);                     break;
                case "list_staff":            OutputProcessor.send(res, 200, listStaff());           break;
                case "list_it_assets":        OutputProcessor.send(res, 200, listItAssets());        break;
                case "list_vendors":          OutputProcessor.send(res, 200, listVendors());         break;

                // 2a - Data Principals
                case "list_data_principals":  OutputProcessor.send(res, 200, listDataPrincipals());  break;
                case "add_data_principal":    addDataPrincipal(req, res, input);                     break;
                case "update_data_principal": updateDataPrincipal(req, res, input);                  break;
                case "delete_data_principal": deleteDataPrincipal(req, res, input);                  break;
                case "add_data_asset_principal":    addDataAssetPrincipal(req, res, input);          break;
                case "delete_data_asset_principal": deleteDataAssetPrincipal(req, res, input);       break;

                // 2b - recipients / access
                case "add_data_asset_recipient":    addDataAssetRecipient(req, res, input);          break;
                case "delete_data_asset_recipient": deleteDataAssetRecipient(req, res, input);       break;
                case "add_data_asset_access":       addDataAssetAccess(req, res, input);             break;
                case "delete_data_asset_access":    deleteDataAssetAccess(req, res, input);          break;

                // 2c - gap assessment
                case "get_gap_assessment":            OutputProcessor.send(res, 200, getGapAssessment(input)); break;
                case "map_data_asset_requirement":    mapDataAssetRequirement(req, res, input);       break;
                case "unmap_data_asset_requirement":  unmapDataAssetRequirement(req, res, input);     break;

                // 2e - RoPA export
                case "get_ropa_export": OutputProcessor.send(res, 200, getRopaExport(input)); break;

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
            pool.cleanup(rs, p, null); rs = null; p = null;

            p = conn.prepareStatement("SELECT COUNT(*) FROM data_assets d WHERE " + GAP_COUNT_SQL + " > 0");
            rs = p.executeQuery();
            result.put("gap_count", rs.next() ? rs.getLong(1) : 0L);

        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        result.put("success", true); return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listDataAssets(JSONObject input) throws Exception {
        String category    = (String) input.get("category");
        String sensitivity = (String) input.get("sensitivity");
        String search       = (String) input.get("search");
        String principalId  = (String) input.get("principal_id");
        Boolean hasGaps      = Boolean.TRUE.equals(input.get("has_gaps")) || "true".equalsIgnoreCase(String.valueOf(input.get("has_gaps")));

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
        if (!isBlank(principalId)) where.append(" AND EXISTS (SELECT 1 FROM data_asset_principals dap WHERE dap.data_asset_id = d.id AND dap.principal_id = ?::uuid)");
        if (hasGaps)               where.append(" AND " + GAP_COUNT_SQL + " > 0");

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
            if (!isBlank(principalId)) p.setString(idx++, principalId);
            rs = p.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
            rs = null; p = null;

            String dataSql =
                "SELECT d.id::text, d.name, d.system_type, d.category, d.sensitivity, d.location, d.volume_estimate, " +
                "d.description, d.discovery_status, d.last_reviewed_at::text, d.created_at::text, " +
                "d.purpose, d.processing_basis, d.retention_period, d.deletion_trigger, d.controller_role, " +
                "d.owner_id::text, o.username AS owner_name, " +
                "d.reviewed_by::text, r.username AS reviewer_name, " +
                "d.linked_asset_id::text, a.name AS linked_asset_name, " +
                "d.linked_vendor_id::text, v.name AS linked_vendor_name, " +
                "(SELECT STRING_AGG(dp.name, ', ' ORDER BY dp.name) FROM data_asset_principals dap " +
                " JOIN data_principals dp ON dp.id = dap.principal_id WHERE dap.data_asset_id = d.id) AS principals, " +
                GAP_COUNT_SQL + " AS gap_count " +
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
            if (!isBlank(principalId)) p.setString(idx++, principalId);
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
                d.put("purpose",             rs.getString("purpose"));
                d.put("processing_basis",    rs.getString("processing_basis"));
                d.put("retention_period",    rs.getString("retention_period"));
                d.put("deletion_trigger",    rs.getString("deletion_trigger"));
                d.put("controller_role",     rs.getString("controller_role"));
                d.put("owner_id",            rs.getString("owner_id"));
                d.put("owner_name",          rs.getString("owner_name"));
                d.put("reviewed_by",         rs.getString("reviewed_by"));
                d.put("reviewer_name",       rs.getString("reviewer_name"));
                d.put("linked_asset_id",     rs.getString("linked_asset_id"));
                d.put("linked_asset_name",   rs.getString("linked_asset_name"));
                d.put("linked_vendor_id",    rs.getString("linked_vendor_id"));
                d.put("linked_vendor_name",  rs.getString("linked_vendor_name"));
                d.put("principals",          rs.getString("principals"));
                d.put("gap_count",           rs.getLong("gap_count"));
                list.add(d);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("data_assets", list);
        result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
    }

    // Bundles a data asset's principals/recipients/access rows in one call, for the edit modal.
    @SuppressWarnings("unchecked")
    private JSONObject getDataAssetDetail(JSONObject input) throws Exception {
        String id = (String) input.get("id");
        JSONObject result = new JSONObject();
        if (isBlank(id)) { result.put("success", false); result.put("error", "id required"); return result; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            JSONArray principals = new JSONArray();
            p = conn.prepareStatement(
                "SELECT dp.id::text, dp.name FROM data_asset_principals dap " +
                "JOIN data_principals dp ON dp.id = dap.principal_id WHERE dap.data_asset_id = ?::uuid ORDER BY dp.name");
            p.setString(1, id); rs = p.executeQuery();
            while (rs.next()) { JSONObject o = new JSONObject(); o.put("id", rs.getString(1)); o.put("name", rs.getString(2)); principals.add(o); }
            pool.cleanup(rs, p, null); rs = null; p = null;

            JSONArray recipients = new JSONArray();
            p = conn.prepareStatement(
                "SELECT id::text, recipient_type, linked_vendor_id::text, recipient_name, purpose_of_sharing " +
                "FROM data_asset_recipients WHERE data_asset_id = ?::uuid ORDER BY created_at");
            p.setString(1, id); rs = p.executeQuery();
            while (rs.next()) {
                JSONObject o = new JSONObject();
                o.put("id", rs.getString("id")); o.put("recipient_type", rs.getString("recipient_type"));
                o.put("linked_vendor_id", rs.getString("linked_vendor_id")); o.put("recipient_name", rs.getString("recipient_name"));
                o.put("purpose_of_sharing", rs.getString("purpose_of_sharing")); recipients.add(o);
            }
            pool.cleanup(rs, p, null); rs = null; p = null;

            JSONArray access = new JSONArray();
            p = conn.prepareStatement(
                "SELECT a.id::text, a.role, a.user_id::text, u.username, a.access_level " +
                "FROM data_asset_access a LEFT JOIN users u ON u.id = a.user_id WHERE a.data_asset_id = ?::uuid ORDER BY a.created_at");
            p.setString(1, id); rs = p.executeQuery();
            while (rs.next()) {
                JSONObject o = new JSONObject();
                o.put("id", rs.getString("id")); o.put("role", rs.getString("role"));
                o.put("user_id", rs.getString("user_id")); o.put("username", rs.getString("username"));
                o.put("access_level", rs.getString("access_level")); access.add(o);
            }
            pool.cleanup(rs, p, null); rs = null; p = null;

            JSONArray requirementIds = new JSONArray();
            p = conn.prepareStatement("SELECT requirement_id::text FROM data_asset_requirement_mappings WHERE data_asset_id = ?::uuid");
            p.setString(1, id); rs = p.executeQuery();
            while (rs.next()) requirementIds.add(rs.getString(1));

            result.put("principals", principals);
            result.put("recipients", recipients);
            result.put("access", access);
            result.put("requirement_ids", requirementIds);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        result.put("success", true); return result;
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
                "INSERT INTO data_assets (name, system_type, category, owner_id, linked_asset_id, linked_vendor_id, location, volume_estimate, description, " +
                "purpose, processing_basis, retention_period, deletion_trigger, controller_role) " +
                "VALUES (?,?,?,?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?,COALESCE(?,'CONTROLLER')) RETURNING id::text"
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
            p.setString(10, (String) input.get("purpose"));
            p.setString(11, (String) input.get("processing_basis"));
            p.setString(12, (String) input.get("retention_period"));
            p.setString(13, (String) input.get("deletion_trigger"));
            p.setString(14, (String) input.get("controller_role"));
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
                "volume_estimate=COALESCE(?,volume_estimate), description=COALESCE(?,description), " +
                "purpose=COALESCE(?,purpose), processing_basis=COALESCE(?,processing_basis), " +
                "retention_period=COALESCE(?,retention_period), deletion_trigger=COALESCE(?,deletion_trigger), " +
                "controller_role=COALESCE(?,controller_role) WHERE id=?::uuid"
            );
            p.setString(1, (String) input.get("name")); p.setString(2, (String) input.get("system_type"));
            String ownerId = (String) input.get("owner_id"); p.setString(3, isBlank(ownerId) ? null : ownerId);
            String assetId = (String) input.get("linked_asset_id"); p.setString(4, isBlank(assetId) ? null : assetId);
            String vendorId = (String) input.get("linked_vendor_id"); p.setString(5, isBlank(vendorId) ? null : vendorId);
            p.setString(6, (String) input.get("location"));
            p.setString(7, (String) input.get("volume_estimate"));
            p.setString(8, (String) input.get("description"));
            p.setString(9, (String) input.get("purpose"));
            p.setString(10, (String) input.get("processing_basis"));
            p.setString(11, (String) input.get("retention_period"));
            p.setString(12, (String) input.get("deletion_trigger"));
            p.setString(13, (String) input.get("controller_role"));
            p.setString(14, id);
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
            p = conn.prepareStatement("SELECT id::text, username FROM users WHERE status='ACTIVE' AND role NOT IN ('USER','SUPERVISOR') ORDER BY username");
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

    // ---------- 2a. Data Principals ----------

    @SuppressWarnings("unchecked")
    private JSONObject listDataPrincipals() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT dp.id::text, dp.name, dp.description, " +
                "(SELECT COUNT(*) FROM data_asset_principals dap WHERE dap.principal_id = dp.id) AS asset_count " +
                "FROM data_principals dp ORDER BY dp.name");
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject o = new JSONObject();
                o.put("id", rs.getString("id")); o.put("name", rs.getString("name"));
                o.put("description", rs.getString("description")); o.put("asset_count", rs.getLong("asset_count"));
                list.add(o);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("data_principals", list); return result;
    }

    @SuppressWarnings("unchecked")
    private void addDataPrincipal(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String name = (String) input.get("name");
        if (isBlank(name)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "name required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("INSERT INTO data_principals (name, description) VALUES (?,?) RETURNING id::text");
            p.setString(1, name); p.setString(2, (String) input.get("description"));
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void updateDataPrincipal(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("UPDATE data_principals SET name=COALESCE(?,name), description=COALESCE(?,description) WHERE id=?::uuid");
            p.setString(1, (String) input.get("name")); p.setString(2, (String) input.get("description")); p.setString(3, id);
            p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void deleteDataPrincipal(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        if (!"ADMIN".equals(InputProcessor.getRole(req))) {
            OutputProcessor.errorResponse(res, 403, "Forbidden", "Admin role required", req.getRequestURI()); return;
        }
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT COUNT(*) FROM data_asset_principals WHERE principal_id = ?::uuid");
            p.setString(1, id); rs = p.executeQuery();
            long taggedCount = rs.next() ? rs.getLong(1) : 0L;
            pool.cleanup(rs, p, null); rs = null; p = null;
            if (taggedCount > 0) {
                OutputProcessor.errorResponse(res, 409, "Conflict",
                    "This principal is tagged on " + taggedCount + " data asset(s). Remove those tags first.", req.getRequestURI());
                return;
            }
            p = conn.prepareStatement("DELETE FROM data_principals WHERE id=?::uuid");
            p.setString(1, id); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void addDataAssetPrincipal(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String assetId = (String) input.get("data_asset_id");
        String principalId = (String) input.get("principal_id");
        if (isBlank(assetId) || isBlank(principalId)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "data_asset_id and principal_id required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "INSERT INTO data_asset_principals (data_asset_id, principal_id) VALUES (?::uuid,?::uuid) " +
                "ON CONFLICT (data_asset_id, principal_id) DO NOTHING");
            p.setString(1, assetId); p.setString(2, principalId); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void deleteDataAssetPrincipal(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String assetId = (String) input.get("data_asset_id");
        String principalId = (String) input.get("principal_id");
        if (isBlank(assetId) || isBlank(principalId)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "data_asset_id and principal_id required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("DELETE FROM data_asset_principals WHERE data_asset_id=?::uuid AND principal_id=?::uuid");
            p.setString(1, assetId); p.setString(2, principalId); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    // ---------- 2b. Recipients / access ----------

    @SuppressWarnings("unchecked")
    private void addDataAssetRecipient(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String assetId = (String) input.get("data_asset_id");
        String recipientType = (String) input.get("recipient_type");
        if (isBlank(assetId) || isBlank(recipientType)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "data_asset_id and recipient_type required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "INSERT INTO data_asset_recipients (data_asset_id, recipient_type, linked_vendor_id, recipient_name, purpose_of_sharing) " +
                "VALUES (?::uuid,?,?::uuid,?,?) RETURNING id::text");
            p.setString(1, assetId); p.setString(2, recipientType);
            String vendorId = (String) input.get("linked_vendor_id");
            p.setString(3, isBlank(vendorId) ? null : vendorId);
            p.setString(4, (String) input.get("recipient_name"));
            p.setString(5, (String) input.get("purpose_of_sharing"));
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void deleteDataAssetRecipient(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("DELETE FROM data_asset_recipients WHERE id=?::uuid");
            p.setString(1, id); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void addDataAssetAccess(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String assetId = (String) input.get("data_asset_id");
        String role = (String) input.get("role");
        String userId = (String) input.get("user_id");
        if (isBlank(assetId) || (isBlank(role) && isBlank(userId))) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "data_asset_id and (role or user_id) required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "INSERT INTO data_asset_access (data_asset_id, role, user_id, access_level) VALUES (?::uuid,?,?::uuid,COALESCE(?,'READ')) RETURNING id::text");
            p.setString(1, assetId); p.setString(2, isBlank(role) ? null : role);
            p.setString(3, isBlank(userId) ? null : userId);
            p.setString(4, (String) input.get("access_level"));
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void deleteDataAssetAccess(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("DELETE FROM data_asset_access WHERE id=?::uuid");
            p.setString(1, id); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    // ---------- 2c. Gap assessment ----------

    @SuppressWarnings("unchecked")
    private JSONObject getGapAssessment(JSONObject input) throws Exception {
        String frameworkId = (String) input.get("framework_id");
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            String mappedCheck = isBlank(frameworkId)
                ? "EXISTS (SELECT 1 FROM data_asset_requirement_mappings m WHERE m.data_asset_id = d.id)"
                : "EXISTS (SELECT 1 FROM data_asset_requirement_mappings m JOIN framework_requirements fr ON fr.id = m.requirement_id " +
                  "WHERE m.data_asset_id = d.id AND fr.framework_id = ?::uuid)";
            String sql =
                "SELECT d.id::text, d.name, d.category, d.sensitivity, d.discovery_status, " +
                "(d.discovery_status <> 'REVIEWED') AS gap_reviewed, " +
                "((d.sensitivity IN ('CONFIDENTIAL','RESTRICTED') OR d.category = 'PII') AND d.processing_basis IS NULL) AS gap_basis, " +
                "(d.retention_period IS NULL AND d.deletion_trigger IS NULL) AS gap_retention, " +
                "(NOT " + mappedCheck + ") AS gap_mapping " +
                "FROM data_assets d ORDER BY d.name";
            p = conn.prepareStatement(sql);
            if (!isBlank(frameworkId)) p.setString(1, frameworkId);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject o = new JSONObject();
                o.put("id", rs.getString("id")); o.put("name", rs.getString("name"));
                o.put("category", rs.getString("category")); o.put("sensitivity", rs.getString("sensitivity"));
                o.put("discovery_status", rs.getString("discovery_status"));
                boolean gr = rs.getBoolean("gap_reviewed"), gb = rs.getBoolean("gap_basis"),
                        gt = rs.getBoolean("gap_retention"), gm = rs.getBoolean("gap_mapping");
                o.put("gap_reviewed", gr); o.put("gap_basis", gb); o.put("gap_retention", gt); o.put("gap_mapping", gm);
                o.put("gap_count", (long) ((gr?1:0)+(gb?1:0)+(gt?1:0)+(gm?1:0)));
                list.add(o);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("assessment", list); return result;
    }

    @SuppressWarnings("unchecked")
    private void mapDataAssetRequirement(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String assetId = (String) input.get("data_asset_id");
        String requirementId = (String) input.get("requirement_id");
        if (isBlank(assetId) || isBlank(requirementId)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "data_asset_id and requirement_id required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "INSERT INTO data_asset_requirement_mappings (data_asset_id, requirement_id) VALUES (?::uuid,?::uuid) " +
                "ON CONFLICT (data_asset_id, requirement_id) DO NOTHING");
            p.setString(1, assetId); p.setString(2, requirementId); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void unmapDataAssetRequirement(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String assetId = (String) input.get("data_asset_id");
        String requirementId = (String) input.get("requirement_id");
        if (isBlank(assetId) || isBlank(requirementId)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "data_asset_id and requirement_id required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("DELETE FROM data_asset_requirement_mappings WHERE data_asset_id=?::uuid AND requirement_id=?::uuid");
            p.setString(1, assetId); p.setString(2, requirementId); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    // ---------- 2e. RoPA export ----------

    @SuppressWarnings("unchecked")
    private JSONObject getRopaExport(JSONObject input) throws Exception {
        String frameworkId = (String) input.get("framework_id");
        String principalId = (String) input.get("principal_id");

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (!isBlank(principalId)) where.append(" AND EXISTS (SELECT 1 FROM data_asset_principals dap2 WHERE dap2.data_asset_id = d.id AND dap2.principal_id = ?::uuid)");
        if (!isBlank(frameworkId)) where.append(" AND EXISTS (SELECT 1 FROM data_asset_requirement_mappings m2 JOIN framework_requirements fr2 ON fr2.id = m2.requirement_id WHERE m2.data_asset_id = d.id AND fr2.framework_id = ?::uuid)");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            String sql =
                "SELECT d.id::text, d.name AS activity_name, d.purpose, d.processing_basis AS legal_basis, " +
                "d.retention_period, d.controller_role, o.username AS owner_name, d.description, " +
                "(SELECT STRING_AGG(dp.name, ', ' ORDER BY dp.name) FROM data_asset_principals dap " +
                " JOIN data_principals dp ON dp.id = dap.principal_id WHERE dap.data_asset_id = d.id) AS data_principals, " +
                "(SELECT STRING_AGG(r.recipient_name || ' (' || r.recipient_type || ')', '; ') FROM data_asset_recipients r " +
                " WHERE r.data_asset_id = d.id) AS recipients, " +
                "(SELECT STRING_AGG(DISTINCT f.data_elements, '; ') FROM data_flows f " +
                " WHERE (f.source_data_asset_id = d.id OR f.destination_data_asset_id = d.id) AND f.data_elements IS NOT NULL) AS flow_elements, " +
                "(EXISTS (SELECT 1 FROM data_flows f WHERE (f.source_data_asset_id = d.id OR f.destination_data_asset_id = d.id) AND f.is_cross_border = true)) AS has_cross_border, " +
                "(SELECT STRING_AGG(DISTINCT f.destination_country, ', ') FROM data_flows f " +
                " WHERE (f.source_data_asset_id = d.id OR f.destination_data_asset_id = d.id) AND f.is_cross_border = true) AS cross_border_countries, " +
                "(SELECT STRING_AGG(DISTINCT fr.section_code || ' - ' || fr.title, '; ') FROM data_asset_requirement_mappings m " +
                " JOIN framework_requirements fr ON fr.id = m.requirement_id WHERE m.data_asset_id = d.id) AS security_measures " +
                "FROM data_assets d LEFT JOIN users o ON o.id = d.owner_id" + where +
                " ORDER BY d.name";
            p = conn.prepareStatement(sql);
            int idx = 1;
            if (!isBlank(principalId)) p.setString(idx++, principalId);
            if (!isBlank(frameworkId)) p.setString(idx++, frameworkId);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject o = new JSONObject();
                o.put("id", rs.getString("id"));
                o.put("activity_name", rs.getString("activity_name"));
                o.put("purpose", rs.getString("purpose"));
                o.put("legal_basis", rs.getString("legal_basis"));
                o.put("data_principals", rs.getString("data_principals"));
                String elements = rs.getString("flow_elements");
                o.put("personal_data_elements", isBlank(elements) ? rs.getString("description") : elements);
                o.put("recipients", rs.getString("recipients"));
                o.put("retention_period", rs.getString("retention_period"));
                boolean crossBorder = rs.getBoolean("has_cross_border");
                o.put("cross_border_transfers", crossBorder ? ("Yes - " + rs.getString("cross_border_countries")) : "None");
                o.put("security_measures", rs.getString("security_measures"));
                o.put("controller_role", rs.getString("controller_role"));
                o.put("owner", rs.getString("owner_name"));
                list.add(o);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("ropa", list); return result;
    }

    private String strVal(JSONObject obj, String key) {
        Object v = obj.get(key);
        return v == null ? null : v.toString().trim();
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
