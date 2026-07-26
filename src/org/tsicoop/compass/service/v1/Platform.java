package org.tsicoop.compass.service.v1;

import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.EventLog;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.OutputProcessor;
import org.tsicoop.compass.framework.PasswordHasher;
import org.tsicoop.compass.framework.PoolDB;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.sql.*;
import java.util.UUID;

/**
 * Platform handles GRC platform administration: users, organizations,
 * departments, designations, and platform-wide metrics.
 */
public class Platform implements Action {

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
                case "get_platform_metrics":
                    OutputProcessor.send(res, 200, getPlatformMetrics());
                    break;
                case "list_users":
                    OutputProcessor.send(res, 200, listUsers(input));
                    break;
                case "provision_user":
                    provisionUser(req, res, input);
                    break;
                case "update_user":
                    updateUser(req, res, input);
                    break;
                case "set_user_status":
                    setUserStatus(req, res, input);
                    break;
                case "reset_user_password":
                    resetUserPassword(req, res, input);
                    break;
                case "set_recovery_key":
                    setRecoveryKey(req, res, input);
                    break;
                case "list_roles_summary":
                    OutputProcessor.send(res, 200, listRolesSummary());
                    break;
                case "get_role_permissions":
                    OutputProcessor.send(res, 200, getRolePermissions());
                    break;
                case "save_role_permissions":
                    saveRolePermissions(req, res, input);
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
    // Platform Metrics
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject getPlatformMetrics() throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            // user counts by status
            pstmt = conn.prepareStatement(
                "SELECT status, COUNT(*) FROM users GROUP BY status"
            );
            rs = pstmt.executeQuery();
            long activeUsers = 0L;
            long pendingUsers = 0L;
            long suspendedUsers = 0L;
            while (rs.next()) {
                String status = rs.getString(1);
                long count = rs.getLong(2);
                if ("ACTIVE".equals(status)) activeUsers = count;
                else if ("PENDING".equals(status)) pendingUsers = count;
                else if ("SUSPENDED".equals(status)) suspendedUsers = count;
            }
            result.put("active_users", activeUsers);
            result.put("pending_users", pendingUsers);
            result.put("suspended_users", suspendedUsers);

        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }

        result.put("success", true);
        return result;
    }

    // -------------------------------------------------------------------------
    // Users
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject listUsers(JSONObject input) throws Exception {
        String role   = (String) input.get("role");
        String status = (String) input.get("status");
        String search = (String) input.get("search");

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
        if (!isBlank(role))   where.append(" AND role = ?");
        if (!isBlank(status)) where.append(" AND status = ?");
        if (!isBlank(search)) where.append(" AND (username ILIKE ? OR email ILIKE ?)");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        JSONArray users = new JSONArray();
        long total = 0;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            String countSql = "SELECT COUNT(*) FROM users" + where;
            pstmt = conn.prepareStatement(countSql);
            int idx = 1;
            if (!isBlank(role))   pstmt.setString(idx++, role);
            if (!isBlank(status)) pstmt.setString(idx++, status);
            if (!isBlank(search)) {
                String like = "%" + search + "%";
                pstmt.setString(idx++, like);
                pstmt.setString(idx++, like);
            }
            rs = pstmt.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            String dataSql =
                "SELECT id::text, email, username, role, status, created_at::text FROM users" + where +
                " ORDER BY created_at DESC LIMIT ? OFFSET ?";
            pstmt = conn.prepareStatement(dataSql);
            idx = 1;
            if (!isBlank(role))   pstmt.setString(idx++, role);
            if (!isBlank(status)) pstmt.setString(idx++, status);
            if (!isBlank(search)) {
                String like = "%" + search + "%";
                pstmt.setString(idx++, like);
                pstmt.setString(idx++, like);
            }
            pstmt.setLong(idx++, limit);
            pstmt.setLong(idx++, offset);

            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject u = new JSONObject();
                u.put("id",         rs.getString(1));
                u.put("email",      rs.getString(2));
                u.put("username",   rs.getString(3));
                u.put("role",       rs.getString(4));
                u.put("status",     rs.getString(5));
                u.put("created_at", rs.getString(6));
                users.add(u);
            }
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("users", users);
        result.put("total_count", total);
        result.put("page", page);
        result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void provisionUser(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String username = (String) input.get("username");
        String email    = (String) input.get("email");
        String password = (String) input.get("password");
        String role     = (String) input.get("role");

        if (isBlank(username) || isBlank(email) || isBlank(password) || isBlank(role)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "username, email, password and role are required", req.getRequestURI());
            return;
        }
        if (password.length() < 10) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "Password must be at least 10 characters", req.getRequestURI());
            return;
        }

        String passwordHash = new PasswordHasher().hashPassword(password);

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "INSERT INTO users (email, password_hash, username, role, status) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE') RETURNING id::text"
            );
            int idx = 1;
            pstmt.setString(idx++, email.toLowerCase().trim());
            pstmt.setString(idx++, passwordHash);
            pstmt.setString(idx++, username);
            pstmt.setString(idx++, role);

            rs = pstmt.executeQuery();
            if (!rs.next()) {
                OutputProcessor.errorResponse(res, 500, "Internal Error", "Failed to create user", req.getRequestURI());
                return;
            }
            String newId = rs.getString(1);

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("id", newId);
            result.put("message", "User provisioned successfully");
            OutputProcessor.send(res, 200, result);

            JSONObject ctx = new JSONObject();
            ctx.put("new_user_id", newId);
            ctx.put("email", email.toLowerCase().trim());
            ctx.put("username", username);
            ctx.put("role", role);
            EventLog.log(InputProcessor.getEmail(req), "USER_PROVISIONED", ctx.toJSONString());

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("unique") || msg.contains("duplicate")) {
                OutputProcessor.errorResponse(res, 409, "Conflict", "A user with that email already exists", req.getRequestURI());
            } else {
                throw e;
            }
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void updateUser(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id       = (String) input.get("id");
        String username = (String) input.get("username");
        String email    = (String) input.get("email");
        String role     = (String) input.get("role");
        String status   = (String) input.get("status");

        if (isBlank(id)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI());
            return;
        }

        StringBuilder sql = new StringBuilder("UPDATE users SET");
        java.util.List<Object> params = new java.util.ArrayList<>();
        boolean first = true;

        if (!isBlank(username)) {
            sql.append(first ? " " : ", ").append("username = ?");
            params.add(username);
            first = false;
        }
        if (!isBlank(email)) {
            sql.append(first ? " " : ", ").append("email = ?");
            params.add(email.toLowerCase().trim());
            first = false;
        }
        if (!isBlank(role)) {
            sql.append(first ? " " : ", ").append("role = ?");
            params.add(role);
            first = false;
        }
        if (!isBlank(status)) {
            sql.append(first ? " " : ", ").append("status = ?");
            params.add(status);
            first = false;
        }

        if (first) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "No fields to update", req.getRequestURI());
            return;
        }

        sql.append(" WHERE id = ?");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;

        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sql.toString());

            int idx = 1;
            for (int i = 0; i < params.size(); i++) {
                pstmt.setString(idx++, (String) params.get(i));
            }
            pstmt.setObject(idx++, UUID.fromString(id));

            pstmt.executeUpdate();

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "User updated successfully");
            OutputProcessor.send(res, 200, result);

            JSONObject ctx = new JSONObject();
            ctx.put("user_id", id);
            if (!isBlank(username)) ctx.put("username", username);
            if (!isBlank(email))    ctx.put("email", email.toLowerCase().trim());
            if (!isBlank(role))     ctx.put("role", role);
            if (!isBlank(status))   ctx.put("status", status);
            EventLog.log(InputProcessor.getEmail(req), "USER_UPDATED", ctx.toJSONString());

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("unique") || msg.contains("duplicate")) {
                OutputProcessor.errorResponse(res, 409, "Conflict", "A user with that email already exists", req.getRequestURI());
            } else {
                throw e;
            }
        } finally {
            if (pool != null) {
                try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void setUserStatus(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id     = (String) input.get("id");
        String status = (String) input.get("status");

        if (isBlank(id) || isBlank(status)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id and status are required", req.getRequestURI());
            return;
        }

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            // Guard: cannot deactivate the last active admin
            if (!"ACTIVE".equalsIgnoreCase(status)) {
                pstmt = conn.prepareStatement("SELECT role FROM users WHERE id = ?");
                pstmt.setObject(1, UUID.fromString(id));
                rs = pstmt.executeQuery();
                String targetRole = rs.next() ? rs.getString(1) : null;
                try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
                rs = null; pstmt = null;

                if ("ADMIN".equals(targetRole)) {
                    pstmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE'"
                    );
                    rs = pstmt.executeQuery();
                    long adminCount = rs.next() ? rs.getLong(1) : 0;
                    try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
                    rs = null; pstmt = null;

                    if (adminCount <= 1) {
                        OutputProcessor.errorResponse(res, 409, "Conflict",
                            "Cannot deactivate the only active administrator", req.getRequestURI());
                        return;
                    }
                }
            }

            pstmt = conn.prepareStatement("UPDATE users SET status = ? WHERE id = ?");
            pstmt.setString(1, status);
            pstmt.setObject(2, UUID.fromString(id));
            pstmt.executeUpdate();

            JSONObject result = new JSONObject();
            result.put("success", true);
            OutputProcessor.send(res, 200, result);

            JSONObject ctx = new JSONObject();
            ctx.put("user_id", id);
            ctx.put("new_status", status);
            EventLog.log(InputProcessor.getEmail(req), "USER_STATUS_CHANGED", ctx.toJSONString());

        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void resetUserPassword(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id          = (String) input.get("id");
        String newPassword = (String) input.get("new_password");

        if (isBlank(id) || isBlank(newPassword)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id and new_password are required", req.getRequestURI());
            return;
        }
        if (newPassword.length() < 10) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "Password must be at least 10 characters", req.getRequestURI());
            return;
        }

        String passwordHash = new PasswordHasher().hashPassword(newPassword);

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;

        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement("UPDATE users SET password_hash = ? WHERE id = ?");
            pstmt.setString(1, passwordHash);
            pstmt.setObject(2, UUID.fromString(id));
            pstmt.executeUpdate();

            JSONObject result = new JSONObject();
            result.put("success", true);
            OutputProcessor.send(res, 200, result);

            JSONObject ctx = new JSONObject();
            ctx.put("user_id", id);
            EventLog.log(InputProcessor.getEmail(req), "USER_PASSWORD_RESET", ctx.toJSONString());

        } finally {
            if (pool != null) {
                try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
            }
        }
    }

    private static final String[] WORD_LIST = {
        "amber","apple","arrow","atlas","azure","badge","basin","batch","birch","blade",
        "blaze","bloom","board","brake","brave","bravo","brick","bridge","brook","brush",
        "cabin","cable","cedar","chalk","chase","chess","chief","chisel","chord","civic",
        "clamp","cloak","cloud","coast","comet","coral","crest","crisp","crown","curve",
        "delta","depot","depot","drift","dune","eagle","ember","epoch","fable","falcon",
        "fence","field","fjord","flame","flare","fleet","flint","flora","flume","focal",
        "forge","forte","frost","funnel","gable","glade","gleam","globe","gloom","grain",
        "grand","grant","graph","gravel","grove","guide","guild","guile","haven","hawk",
        "heath","hedge","herald","hinge","holly","honor","hyena","index","inlet","ivory",
        "jade","jaguar","jewel","joint","joust","karma","kayak","kestrel","knoll","larch"
    };

    @SuppressWarnings("unchecked")
    private void setRecoveryKey(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        if (!"ADMIN".equals(InputProcessor.getRole(req))) {
            OutputProcessor.errorResponse(res, 403, "Forbidden", "ADMIN role required", req.getRequestURI());
            return;
        }
        String userId = (String) input.get("id");
        if (isBlank(userId)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI());
            return;
        }

        java.util.Random rng = new java.util.Random();
        String[] words = new String[5];
        for (int i = 0; i < 5; i++) {
            words[i] = WORD_LIST[rng.nextInt(WORD_LIST.length)];
        }
        String passphrase = String.join("-", words);
        String keyHash = sha256hex(passphrase);

        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            pstmt = conn.prepareStatement("UPDATE users SET recovery_key_hash = ? WHERE id = ?");
            pstmt.setString(1, keyHash);
            pstmt.setObject(2, java.util.UUID.fromString(userId));
            pstmt.executeUpdate();
        } finally {
            if (pool != null) pool.cleanup(null, pstmt, conn);
        }

        JSONObject ctx = new JSONObject();
        ctx.put("user_id", userId);
        EventLog.log(InputProcessor.getEmail(req), "USER_RECOVERY_KEY_SET", ctx.toJSONString());

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("passphrase", passphrase);
        OutputProcessor.send(res, 200, result);
    }

    private static String sha256hex(String input) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) { String h = Integer.toHexString(0xff & b); if (h.length() == 1) sb.append('0'); sb.append(h); }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Roles & Permissions (section moved up; org/dept/designation removed)
    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // -------------------------------------------------------------------------
    // Roles & Permissions
    // -------------------------------------------------------------------------

    private static final String[] MODULES = {
        "platform", "governance", "risks", "controls",
        "evidence", "operations", "incidents", "reports", "helpdesk", "selfservice"
    };

    private static final String[] ROLES = {
        "ADMIN", "GRC_OFFICER", "IT_STAFF", "USER"
    };

    @SuppressWarnings("unchecked")
    private JSONObject listRolesSummary() throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        JSONArray roles = new JSONArray();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "SELECT role, COUNT(*) AS user_count FROM users WHERE status != 'SUSPENDED' GROUP BY role"
            );
            rs = pstmt.executeQuery();
            java.util.Map<String, Long> counts = new java.util.HashMap<>();
            while (rs.next()) counts.put(rs.getString("role"), rs.getLong("user_count"));
            for (String role : ROLES) {
                JSONObject r = new JSONObject();
                r.put("role", role);
                r.put("user_count", counts.getOrDefault(role, 0L));
                roles.add(r);
            }
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
        result.put("success", true);
        result.put("roles", roles);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject getRolePermissions() throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        JSONArray permissions = new JSONArray();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "SELECT role, module, permission_level FROM role_permissions ORDER BY role, module"
            );
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject p = new JSONObject();
                p.put("role", rs.getString("role"));
                p.put("module", rs.getString("module"));
                p.put("permission_level", rs.getString("permission_level"));
                permissions.add(p);
            }
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
        result.put("success", true);
        result.put("permissions", permissions);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void saveRolePermissions(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String role = (String) input.get("role");
        Object permsObj = input.get("permissions");

        if (isBlank(role) || !(permsObj instanceof JSONArray)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "role and permissions array are required", req.getRequestURI());
            return;
        }

        boolean validRole = false;
        for (String r : ROLES) { if (r.equals(role)) { validRole = true; break; } }
        if (!validRole) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "Invalid role: " + role, req.getRequestURI());
            return;
        }

        JSONArray perms = (JSONArray) permsObj;
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            conn.setAutoCommit(false);
            for (Object obj : perms) {
                if (!(obj instanceof JSONObject)) continue;
                JSONObject p = (JSONObject) obj;
                String module = (String) p.get("module");
                String level  = (String) p.get("permission_level");
                if (isBlank(module) || isBlank(level)) continue;
                pstmt = conn.prepareStatement(
                    "INSERT INTO role_permissions (role, module, permission_level, updated_at) VALUES (?, ?, ?, NOW()) " +
                    "ON CONFLICT (role, module) DO UPDATE SET permission_level = EXCLUDED.permission_level, updated_at = NOW()"
                );
                pstmt.setString(1, role);
                pstmt.setString(2, module);
                pstmt.setString(3, level);
                pstmt.executeUpdate();
                try { pool.cleanup(null, pstmt, null); } catch (Exception ignored) {}
                pstmt = null;
            }
            conn.commit();
            JSONObject result = new JSONObject();
            result.put("success", true);
            OutputProcessor.send(res, 200, result);
        } catch (Exception e) {
            try { if (conn != null) conn.rollback(); } catch (Exception ignored) {}
            throw e;
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
    }
}
