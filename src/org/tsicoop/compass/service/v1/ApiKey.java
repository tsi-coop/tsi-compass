package org.tsicoop.compass.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.tsicoop.compass.framework.*;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class ApiKey implements Action {

    @Override
    public boolean validate(String method, HttpServletRequest req, HttpServletResponse res) {
        return "POST".equalsIgnoreCase(method);
    }

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
                case "generate_api_key":
                    generateApiKey(req, res, input);
                    break;
                case "list_api_keys":
                    OutputProcessor.send(res, 200, listApiKeys());
                    break;
                case "revoke_api_key":
                    revokeApiKey(req, res, input);
                    break;
                default:
                    OutputProcessor.errorResponse(res, 400, "Bad Request", "Unknown _func: " + func, req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Server Error", e.getMessage(), req.getRequestURI());
        }
    }

    private void generateApiKey(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        if (!"ADMIN".equals(InputProcessor.getRole(req))) {
            OutputProcessor.errorResponse(res, 403, "Forbidden", "ADMIN role required to generate API keys", req.getRequestURI());
            return;
        }

        String name = strVal(input, "name");
        if (isBlank(name)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "name is required", req.getRequestURI());
            return;
        }
        String description = strVal(input, "description");

        String plainKey    = new RandomString().getString(32);
        String plainSecret = new RandomString().getString(32);
        String keyPrefix   = plainKey.substring(0, 12);
        String keyHash     = sha256(plainKey);
        String secretHash  = sha256(plainSecret);

        String actorEmail = InputProcessor.getEmail(req);

        PoolDB pool = null; Connection conn = null;
        PreparedStatement pstmt = null; ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "INSERT INTO api_keys (name, description, key_prefix, api_key_hash, api_secret_hash, created_by) " +
                "VALUES (?, ?, ?, ?, ?, (SELECT id FROM users WHERE email = ?)) RETURNING id"
            );
            pstmt.setString(1, name);
            pstmt.setString(2, isBlank(description) ? null : description);
            pstmt.setString(3, keyPrefix);
            pstmt.setString(4, keyHash);
            pstmt.setString(5, secretHash);
            pstmt.setString(6, actorEmail);
            rs = pstmt.executeQuery();
            rs.next();
            result.put("id", rs.getString("id"));
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }

        JSONObject ctx = new JSONObject();
        ctx.put("name", name);
        EventLog.log(actorEmail, "API_KEY_GENERATED", ctx.toJSONString());

        result.put("success", true);
        result.put("api_key", plainKey);
        result.put("api_secret", plainSecret);
        result.put("key_prefix", keyPrefix);
        OutputProcessor.send(res, 200, result);
    }

    @SuppressWarnings("unchecked")
    private JSONObject listApiKeys() throws Exception {
        PoolDB pool = null; Connection conn = null;
        PreparedStatement pstmt = null; ResultSet rs = null;
        JSONObject result = new JSONObject();
        JSONArray keys = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "SELECT k.id, k.name, k.description, k.key_prefix, k.created_at, k.last_used_at, k.status, " +
                "       u.email AS created_by_email, u.username AS created_by_name " +
                "FROM api_keys k " +
                "LEFT JOIN users u ON u.id = k.created_by " +
                "ORDER BY k.created_at DESC"
            );
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject row = new JSONObject();
                row.put("id",              rs.getString("id"));
                row.put("name",            rs.getString("name"));
                row.put("description",     rs.getString("description"));
                row.put("key_prefix",      rs.getString("key_prefix"));
                row.put("created_at",      rs.getString("created_at"));
                row.put("last_used_at",    rs.getString("last_used_at"));
                row.put("status",          rs.getString("status"));
                row.put("created_by_email", rs.getString("created_by_email"));
                row.put("created_by_name", rs.getString("created_by_name"));
                keys.add(row);
            }
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
        result.put("success", true);
        result.put("api_keys", keys);
        return result;
    }

    private void revokeApiKey(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        if (!"ADMIN".equals(InputProcessor.getRole(req))) {
            OutputProcessor.errorResponse(res, 403, "Forbidden", "ADMIN role required to revoke API keys", req.getRequestURI());
            return;
        }
        String id = strVal(input, "id");
        if (isBlank(id)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI());
            return;
        }

        String actorEmail = InputProcessor.getEmail(req);
        String keyName = null;

        PoolDB pool = null; Connection conn = null;
        PreparedStatement pstmt = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "UPDATE api_keys SET status = 'REVOKED' WHERE id = ?::uuid AND status = 'ACTIVE' RETURNING name"
            );
            pstmt.setString(1, id);
            rs = pstmt.executeQuery();
            if (rs.next()) keyName = rs.getString("name");
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }

        if (keyName == null) {
            OutputProcessor.errorResponse(res, 404, "Not Found", "API key not found or already revoked", req.getRequestURI());
            return;
        }

        JSONObject ctx = new JSONObject();
        ctx.put("id", id); ctx.put("name", keyName);
        EventLog.log(actorEmail, "API_KEY_REVOKED", ctx.toJSONString());

        JSONObject result = new JSONObject();
        result.put("success", true);
        OutputProcessor.send(res, 200, result);
    }

    private static String sha256(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) { String h = Integer.toHexString(0xff & b); if (h.length() == 1) sb.append('0'); sb.append(h); }
        return sb.toString();
    }

    private static String strVal(JSONObject obj, String key) {
        Object v = obj.get(key);
        return v == null ? null : v.toString().trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
