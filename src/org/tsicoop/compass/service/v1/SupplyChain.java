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

public class SupplyChain implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");
            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing _func", req.getRequestURI()); return;
            }
            switch (func.toLowerCase()) {
                case "get_supplychain_metrics": OutputProcessor.send(res, 200, getSupplyChainMetrics());   break;
                case "list_sbom_components":    OutputProcessor.send(res, 200, listSbomComponents(input)); break;
                case "add_sbom_component":      addSbomComponent(req, res, input);                          break;
                case "update_sbom_component":   updateSbomComponent(req, res, input);                       break;
                case "delete_sbom_component":   deleteSbomComponent(req, res, input);                       break;
                case "import_sbom_components":  importSbomComponents(req, res, input);                      break;
                case "list_cbom_components":    OutputProcessor.send(res, 200, listCbomComponents(input)); break;
                case "add_cbom_component":      addCbomComponent(req, res, input);                          break;
                case "update_cbom_component":   updateCbomComponent(req, res, input);                       break;
                case "delete_cbom_component":   deleteCbomComponent(req, res, input);                       break;
                case "import_cbom_components":  importCbomComponents(req, res, input);                      break;
                case "list_it_assets":          OutputProcessor.send(res, 200, listItAssets());             break;
                default: OutputProcessor.errorResponse(res, 400, "Bad Request", "Unknown function: "+func, req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Internal Error", e.getMessage(), req.getRequestURI());
        }
    }

    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse r) { return "POST".equalsIgnoreCase(m); }

    @SuppressWarnings("unchecked")
    private JSONObject getSupplyChainMetrics() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT COUNT(*) FROM sbom_components"); rs = p.executeQuery();
            result.put("total_sbom_components", rs.next() ? rs.getLong(1) : 0L);
            pool.cleanup(rs, p, null); rs = null; p = null;

            p = conn.prepareStatement("SELECT COUNT(*) FROM cbom_components"); rs = p.executeQuery();
            result.put("total_cbom_components", rs.next() ? rs.getLong(1) : 0L);
            pool.cleanup(rs, p, null); rs = null; p = null;

            p = conn.prepareStatement("SELECT COUNT(*) FROM cbom_components WHERE certificate_expiry IS NOT NULL AND certificate_expiry <= CURRENT_DATE + INTERVAL '30 days'");
            rs = p.executeQuery();
            result.put("expiring_certificates_count", rs.next() ? rs.getLong(1) : 0L);
            pool.cleanup(rs, p, null); rs = null; p = null;

            p = conn.prepareStatement("SELECT COUNT(DISTINCT license) FROM sbom_components WHERE license IS NOT NULL AND license <> ''");
            rs = p.executeQuery();
            result.put("distinct_licenses_count", rs.next() ? rs.getLong(1) : 0L);

        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        result.put("success", true); return result;
    }

    // ===================== SBOM =====================

    @SuppressWarnings("unchecked")
    private JSONObject listSbomComponents(JSONObject input) throws Exception {
        String componentType = (String) input.get("component_type");
        String license       = (String) input.get("license");
        String search         = (String) input.get("search");

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
        if (!isBlank(componentType)) where.append(" AND s.component_type = ?");
        if (!isBlank(license))        where.append(" AND s.license = ?");
        if (!isBlank(search))         where.append(" AND s.component_name ILIKE ?");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        long total = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            String countSql = "SELECT COUNT(*) FROM sbom_components s" + where;
            p = conn.prepareStatement(countSql);
            int idx = 1;
            if (!isBlank(componentType)) p.setString(idx++, componentType);
            if (!isBlank(license))        p.setString(idx++, license);
            if (!isBlank(search))         p.setString(idx++, "%"+search+"%");
            rs = p.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
            rs = null; p = null;

            String dataSql =
                "SELECT s.id::text, s.component_name, s.version, s.component_type, s.supplier, s.license, " +
                "s.description, s.created_at::text, s.asset_id::text, a.name AS asset_name " +
                "FROM sbom_components s " +
                "LEFT JOIN assets a ON a.id = s.asset_id" + where +
                " ORDER BY s.component_name" +
                " LIMIT ? OFFSET ?";
            p = conn.prepareStatement(dataSql);
            idx = 1;
            if (!isBlank(componentType)) p.setString(idx++, componentType);
            if (!isBlank(license))        p.setString(idx++, license);
            if (!isBlank(search))         p.setString(idx++, "%"+search+"%");
            p.setLong(idx++, limit); p.setLong(idx++, offset);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject c = new JSONObject();
                c.put("id",              rs.getString("id"));
                c.put("component_name",  rs.getString("component_name"));
                c.put("version",         rs.getString("version"));
                c.put("component_type",  rs.getString("component_type"));
                c.put("supplier",        rs.getString("supplier"));
                c.put("license",         rs.getString("license"));
                c.put("description",     rs.getString("description"));
                c.put("created_at",      rs.getString("created_at"));
                c.put("asset_id",        rs.getString("asset_id"));
                c.put("asset_name",      rs.getString("asset_name"));
                list.add(c);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("sbom_components", list);
        result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void addSbomComponent(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String name = (String) input.get("component_name");
        String type = (String) input.get("component_type");
        if (isBlank(name) || isBlank(type)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "component_name, component_type required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "INSERT INTO sbom_components (component_name, version, component_type, supplier, license, description, asset_id) " +
                "VALUES (?,?,?,?,?,?,?::uuid) RETURNING id::text"
            );
            p.setString(1, name); p.setString(2, (String) input.get("version")); p.setString(3, type);
            p.setString(4, (String) input.get("supplier")); p.setString(5, (String) input.get("license"));
            p.setString(6, (String) input.get("description"));
            String assetId = (String) input.get("asset_id");
            p.setString(7, isBlank(assetId) ? null : assetId);
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void updateSbomComponent(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "UPDATE sbom_components SET component_name=COALESCE(?,component_name), version=COALESCE(?,version), " +
                "component_type=COALESCE(?,component_type), supplier=COALESCE(?,supplier), license=COALESCE(?,license), " +
                "description=COALESCE(?,description), asset_id=COALESCE(?::uuid,asset_id) WHERE id=?::uuid"
            );
            p.setString(1, (String) input.get("component_name")); p.setString(2, (String) input.get("version"));
            p.setString(3, (String) input.get("component_type")); p.setString(4, (String) input.get("supplier"));
            p.setString(5, (String) input.get("license")); p.setString(6, (String) input.get("description"));
            String assetId = (String) input.get("asset_id"); p.setString(7, isBlank(assetId) ? null : assetId);
            p.setString(8, id);
            p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void deleteSbomComponent(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        if (!"ADMIN".equals(InputProcessor.getRole(req))) {
            OutputProcessor.errorResponse(res, 403, "Forbidden", "Admin role required", req.getRequestURI()); return;
        }
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT component_name FROM sbom_components WHERE id=?::uuid");
            p.setString(1, id); rs = p.executeQuery();
            String name = rs.next() ? rs.getString(1) : id;
            pool.cleanup(rs, p, null); rs = null; p = null;
            p = conn.prepareStatement("DELETE FROM sbom_components WHERE id=?::uuid");
            p.setString(1, id); p.executeUpdate();
            JSONObject ctx = new JSONObject(); ctx.put("sbom_component_id", id); ctx.put("sbom_component_name", name);
            EventLog.log(InputProcessor.getEmail(req), "SBOM_COMPONENT_DELETED", ctx.toJSONString());
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void importSbomComponents(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        JSONArray rows = (JSONArray) input.get("rows");
        if (rows == null || rows.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "rows array required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        int imported = 0; JSONArray errors = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "INSERT INTO sbom_components (component_name, version, component_type, supplier, license, description) VALUES (?, ?, ?, ?, ?, ?)"
            );
            for (Object obj : rows) {
                JSONObject row = (JSONObject) obj;
                String name = strVal(row, "component_name"); String type = strVal(row, "component_type");
                if (isBlank(name) || isBlank(type)) { errors.add("Row skipped: component_name and component_type required"); continue; }
                try {
                    p.setString(1, name); p.setString(2, strVal(row, "version")); p.setString(3, type.toUpperCase());
                    p.setString(4, strVal(row, "supplier")); p.setString(5, strVal(row, "license"));
                    p.setString(6, strVal(row, "description"));
                    p.executeUpdate(); imported++;
                } catch (Exception ex) { errors.add("Row '"+name+"': "+ex.getMessage()); }
            }
            EventLog.log(InputProcessor.getEmail(req), "SBOM_COMPONENTS_IMPORTED", "{\"count\":"+imported+"}");
            JSONObject result = new JSONObject(); result.put("success", true);
            result.put("imported", (long) imported); result.put("errors", errors);
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    // ===================== CBOM =====================

    @SuppressWarnings("unchecked")
    private JSONObject listCbomComponents(JSONObject input) throws Exception {
        String cryptoType = (String) input.get("crypto_type");
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
        if (!isBlank(cryptoType)) where.append(" AND c.crypto_type = ?");
        if (!isBlank(search))      where.append(" AND c.name ILIKE ?");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        long total = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            String countSql = "SELECT COUNT(*) FROM cbom_components c" + where;
            p = conn.prepareStatement(countSql);
            int idx = 1;
            if (!isBlank(cryptoType)) p.setString(idx++, cryptoType);
            if (!isBlank(search))      p.setString(idx++, "%"+search+"%");
            rs = p.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
            rs = null; p = null;

            String dataSql =
                "SELECT c.id::text, c.name, c.crypto_type, c.algorithm, c.key_size, c.protocol, " +
                "c.certificate_expiry::text, c.standard, c.description, c.created_at::text, " +
                "c.asset_id::text, a.name AS asset_name " +
                "FROM cbom_components c " +
                "LEFT JOIN assets a ON a.id = c.asset_id" + where +
                " ORDER BY c.name" +
                " LIMIT ? OFFSET ?";
            p = conn.prepareStatement(dataSql);
            idx = 1;
            if (!isBlank(cryptoType)) p.setString(idx++, cryptoType);
            if (!isBlank(search))      p.setString(idx++, "%"+search+"%");
            p.setLong(idx++, limit); p.setLong(idx++, offset);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject c = new JSONObject();
                c.put("id",                  rs.getString("id"));
                c.put("name",                rs.getString("name"));
                c.put("crypto_type",         rs.getString("crypto_type"));
                c.put("algorithm",           rs.getString("algorithm"));
                long keySize = rs.getLong("key_size");
                c.put("key_size",            rs.wasNull() ? null : keySize);
                c.put("protocol",            rs.getString("protocol"));
                c.put("certificate_expiry",  rs.getString("certificate_expiry"));
                c.put("standard",            rs.getString("standard"));
                c.put("description",         rs.getString("description"));
                c.put("created_at",          rs.getString("created_at"));
                c.put("asset_id",            rs.getString("asset_id"));
                c.put("asset_name",          rs.getString("asset_name"));
                list.add(c);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("cbom_components", list);
        result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void addCbomComponent(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String name = (String) input.get("name");
        String type = (String) input.get("crypto_type");
        if (isBlank(name) || isBlank(type)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "name, crypto_type required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "INSERT INTO cbom_components (name, crypto_type, algorithm, key_size, protocol, certificate_expiry, standard, description, asset_id) " +
                "VALUES (?,?,?,?,?,?::date,?,?,?::uuid) RETURNING id::text"
            );
            p.setString(1, name); p.setString(2, type); p.setString(3, (String) input.get("algorithm"));
            setNullableInt(p, 4, input.get("key_size"));
            p.setString(5, (String) input.get("protocol"));
            String expiry = (String) input.get("certificate_expiry"); p.setString(6, isBlank(expiry) ? null : expiry);
            p.setString(7, (String) input.get("standard")); p.setString(8, (String) input.get("description"));
            String assetId = (String) input.get("asset_id");
            p.setString(9, isBlank(assetId) ? null : assetId);
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void updateCbomComponent(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "UPDATE cbom_components SET name=COALESCE(?,name), crypto_type=COALESCE(?,crypto_type), " +
                "algorithm=COALESCE(?,algorithm), key_size=COALESCE(?,key_size), protocol=COALESCE(?,protocol), " +
                "certificate_expiry=COALESCE(?::date,certificate_expiry), standard=COALESCE(?,standard), " +
                "description=COALESCE(?,description), asset_id=COALESCE(?::uuid,asset_id) WHERE id=?::uuid"
            );
            p.setString(1, (String) input.get("name")); p.setString(2, (String) input.get("crypto_type"));
            p.setString(3, (String) input.get("algorithm"));
            setNullableInt(p, 4, input.get("key_size"));
            p.setString(5, (String) input.get("protocol"));
            String expiry = (String) input.get("certificate_expiry"); p.setString(6, isBlank(expiry) ? null : expiry);
            p.setString(7, (String) input.get("standard")); p.setString(8, (String) input.get("description"));
            String assetId = (String) input.get("asset_id"); p.setString(9, isBlank(assetId) ? null : assetId);
            p.setString(10, id);
            p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void deleteCbomComponent(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        if (!"ADMIN".equals(InputProcessor.getRole(req))) {
            OutputProcessor.errorResponse(res, 403, "Forbidden", "Admin role required", req.getRequestURI()); return;
        }
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT name FROM cbom_components WHERE id=?::uuid");
            p.setString(1, id); rs = p.executeQuery();
            String name = rs.next() ? rs.getString(1) : id;
            pool.cleanup(rs, p, null); rs = null; p = null;
            p = conn.prepareStatement("DELETE FROM cbom_components WHERE id=?::uuid");
            p.setString(1, id); p.executeUpdate();
            JSONObject ctx = new JSONObject(); ctx.put("cbom_component_id", id); ctx.put("cbom_component_name", name);
            EventLog.log(InputProcessor.getEmail(req), "CBOM_COMPONENT_DELETED", ctx.toJSONString());
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void importCbomComponents(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        JSONArray rows = (JSONArray) input.get("rows");
        if (rows == null || rows.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "rows array required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        int imported = 0; JSONArray errors = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "INSERT INTO cbom_components (name, crypto_type, algorithm, key_size, protocol, certificate_expiry, standard, description) " +
                "VALUES (?, ?, ?, ?, ?, ?::date, ?, ?)"
            );
            for (Object obj : rows) {
                JSONObject row = (JSONObject) obj;
                String name = strVal(row, "name"); String type = strVal(row, "crypto_type");
                if (isBlank(name) || isBlank(type)) { errors.add("Row skipped: name and crypto_type required"); continue; }
                try {
                    p.setString(1, name); p.setString(2, type.toUpperCase()); p.setString(3, strVal(row, "algorithm"));
                    String keySize = strVal(row, "key_size");
                    if (isBlank(keySize)) p.setNull(4, Types.INTEGER); else p.setInt(4, Integer.parseInt(keySize));
                    p.setString(5, strVal(row, "protocol"));
                    String expiry = strVal(row, "certificate_expiry"); p.setString(6, isBlank(expiry) ? null : expiry);
                    p.setString(7, strVal(row, "standard")); p.setString(8, strVal(row, "description"));
                    p.executeUpdate(); imported++;
                } catch (Exception ex) { errors.add("Row '"+name+"': "+ex.getMessage()); }
            }
            EventLog.log(InputProcessor.getEmail(req), "CBOM_COMPONENTS_IMPORTED", "{\"count\":"+imported+"}");
            JSONObject result = new JSONObject(); result.put("success", true);
            result.put("imported", (long) imported); result.put("errors", errors);
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
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

    private void setNullableInt(PreparedStatement p, int idx, Object val) throws SQLException {
        if (val instanceof Long) { p.setInt(idx, ((Long) val).intValue()); return; }
        if (val instanceof Number) { p.setInt(idx, ((Number) val).intValue()); return; }
        if (val instanceof String && !isBlank((String) val)) { p.setInt(idx, Integer.parseInt(((String) val).trim())); return; }
        p.setNull(idx, Types.INTEGER);
    }

    private String strVal(JSONObject obj, String key) {
        Object v = obj.get(key);
        return v == null ? null : v.toString().trim();
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
