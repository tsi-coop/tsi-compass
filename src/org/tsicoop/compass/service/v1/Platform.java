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
                case "list_organizations":
                    OutputProcessor.send(res, 200, listOrganizations());
                    break;
                case "add_organization":
                    addOrganization(req, res, input);
                    break;
                case "update_organization":
                    updateOrganization(req, res, input);
                    break;
                case "list_departments":
                    OutputProcessor.send(res, 200, listDepartments());
                    break;
                case "add_department":
                    addDepartment(req, res, input);
                    break;
                case "update_department":
                    updateDepartment(req, res, input);
                    break;
                case "list_designations":
                    OutputProcessor.send(res, 200, listDesignations());
                    break;
                case "add_designation":
                    addDesignation(req, res, input);
                    break;
                case "update_designation":
                    updateDesignation(req, res, input);
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

            // org_count
            pstmt = conn.prepareStatement("SELECT COUNT(*) FROM organizations");
            rs = pstmt.executeQuery();
            result.put("org_count", rs.next() ? rs.getLong(1) : 0L);
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            // dept_count
            pstmt = conn.prepareStatement("SELECT COUNT(*) FROM departments");
            rs = pstmt.executeQuery();
            result.put("dept_count", rs.next() ? rs.getLong(1) : 0L);
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

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
        String departmentId = (String) input.get("department_id");
        String role = (String) input.get("role");
        String status = (String) input.get("status");
        String search = (String) input.get("search");

        StringBuilder sql = new StringBuilder(
            "SELECT u.id::text, u.email, u.username, u.role, u.status, " +
            "u.department_id::text, d.name AS department_name, u.created_at::text " +
            "FROM users u " +
            "LEFT JOIN departments d ON d.id = u.department_id " +
            "WHERE 1=1"
        );

        if (!isBlank(departmentId)) sql.append(" AND u.department_id = ?");
        if (!isBlank(role))         sql.append(" AND u.role = ?");
        if (!isBlank(status))       sql.append(" AND u.status = ?");
        if (!isBlank(search))       sql.append(" AND (u.username ILIKE ? OR u.email ILIKE ?)");

        sql.append(" ORDER BY u.created_at DESC");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        JSONArray users = new JSONArray();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sql.toString());

            int idx = 1;
            if (!isBlank(departmentId)) pstmt.setObject(idx++, UUID.fromString(departmentId));
            if (!isBlank(role))         pstmt.setString(idx++, role);
            if (!isBlank(status))       pstmt.setString(idx++, status);
            if (!isBlank(search)) {
                String like = "%" + search + "%";
                pstmt.setString(idx++, like);
                pstmt.setString(idx++, like);
            }

            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject u = new JSONObject();
                u.put("id",              rs.getString(1));
                u.put("email",           rs.getString(2));
                u.put("username",        rs.getString(3));
                u.put("role",            rs.getString(4));
                u.put("status",          rs.getString(5));
                u.put("department_id",   rs.getString(6));
                u.put("department_name", rs.getString(7));
                u.put("created_at",      rs.getString(8));
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
        return result;
    }

    @SuppressWarnings("unchecked")
    private void provisionUser(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String username     = (String) input.get("username");
        String email        = (String) input.get("email");
        String password     = (String) input.get("password");
        String role         = (String) input.get("role");
        String departmentId = (String) input.get("department_id");

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
                "INSERT INTO users (email, password_hash, username, role, department_id, status) " +
                "VALUES (?, ?, ?, ?, ?, 'ACTIVE') RETURNING id::text"
            );
            int idx = 1;
            pstmt.setString(idx++, email.toLowerCase().trim());
            pstmt.setString(idx++, passwordHash);
            pstmt.setString(idx++, username);
            pstmt.setString(idx++, role);
            if (isBlank(departmentId)) pstmt.setNull(idx++, java.sql.Types.OTHER);
            else pstmt.setObject(idx++, UUID.fromString(departmentId));

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
        String id           = (String) input.get("id");
        String username     = (String) input.get("username");
        String email        = (String) input.get("email");
        String role         = (String) input.get("role");
        String departmentId = (String) input.get("department_id");
        String status       = (String) input.get("status");

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
        if (input.containsKey("department_id")) {
            sql.append(first ? " " : ", ").append("department_id = ?");
            params.add(departmentId); // may be null/blank — handled below
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
                // The department_id slot needs special UUID handling
                if (input.containsKey("department_id") && params.get(i) == departmentId) {
                    if (isBlank(departmentId)) pstmt.setNull(idx++, java.sql.Types.OTHER);
                    else pstmt.setObject(idx++, UUID.fromString(departmentId));
                } else {
                    pstmt.setString(idx++, (String) params.get(i));
                }
            }
            pstmt.setObject(idx++, UUID.fromString(id));

            pstmt.executeUpdate();

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "User updated successfully");
            OutputProcessor.send(res, 200, result);

            JSONObject ctx = new JSONObject();
            ctx.put("user_id", id);
            if (!isBlank(username))     ctx.put("username", username);
            if (!isBlank(email))        ctx.put("email", email.toLowerCase().trim());
            if (!isBlank(role))         ctx.put("role", role);
            if (!isBlank(status))       ctx.put("status", status);
            if (!isBlank(departmentId)) ctx.put("department_id", departmentId);
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

        try {
            pool = new PoolDB();
            conn = pool.getConnection();
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
                try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
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

    // -------------------------------------------------------------------------
    // Organizations
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject listOrganizations() throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        JSONArray organizations = new JSONArray();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "SELECT o.id::text, o.name, o.type, o.parent_id::text, p.name AS parent_name, " +
                "  (SELECT COUNT(*) FROM departments d WHERE d.org_id = o.id) AS dept_count " +
                "FROM organizations o " +
                "LEFT JOIN organizations p ON p.id = o.parent_id " +
                "ORDER BY o.name"
            );
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject org = new JSONObject();
                org.put("id",          rs.getString(1));
                org.put("name",        rs.getString(2));
                org.put("type",        rs.getString(3));
                org.put("parent_id",   rs.getString(4));
                org.put("parent_name", rs.getString(5));
                org.put("dept_count",  rs.getLong(6));
                organizations.add(org);
            }
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("organizations", organizations);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void addOrganization(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String name     = (String) input.get("name");
        String type     = (String) input.get("type");
        String parentId = (String) input.get("parent_id");

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
                "INSERT INTO organizations (name, type, parent_id) VALUES (?, ?, ?) RETURNING id::text"
            );
            int idx = 1;
            pstmt.setString(idx++, name);
            pstmt.setString(idx++, type);
            if (isBlank(parentId)) pstmt.setNull(idx++, java.sql.Types.OTHER);
            else pstmt.setObject(idx++, UUID.fromString(parentId));

            rs = pstmt.executeQuery();
            if (!rs.next()) {
                OutputProcessor.errorResponse(res, 500, "Internal Error", "Failed to create organization", req.getRequestURI());
                return;
            }
            String newId = rs.getString(1);

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("id", newId);
            OutputProcessor.send(res, 200, result);

        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void updateOrganization(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id       = (String) input.get("id");
        String name     = (String) input.get("name");
        String type     = (String) input.get("type");
        String parentId = (String) input.get("parent_id");

        if (isBlank(id)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI());
            return;
        }

        StringBuilder sql = new StringBuilder("UPDATE organizations SET");
        boolean first = true;

        if (!isBlank(name)) { sql.append(first ? " " : ", ").append("name = ?"); first = false; }
        if (!isBlank(type)) { sql.append(first ? " " : ", ").append("type = ?"); first = false; }
        if (input.containsKey("parent_id")) { sql.append(first ? " " : ", ").append("parent_id = ?"); first = false; }

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
            if (!isBlank(name))                pstmt.setString(idx++, name);
            if (!isBlank(type))                pstmt.setString(idx++, type);
            if (input.containsKey("parent_id")) {
                if (isBlank(parentId)) pstmt.setNull(idx++, java.sql.Types.OTHER);
                else pstmt.setObject(idx++, UUID.fromString(parentId));
            }
            pstmt.setObject(idx++, UUID.fromString(id));

            pstmt.executeUpdate();

            JSONObject result = new JSONObject();
            result.put("success", true);
            OutputProcessor.send(res, 200, result);

        } finally {
            if (pool != null) {
                try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
            }
        }
    }

    // -------------------------------------------------------------------------
    // Departments
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject listDepartments() throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        JSONArray departments = new JSONArray();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "SELECT d.id::text, d.name, d.org_id::text, o.name AS org_name, " +
                "  (SELECT COUNT(*) FROM users u WHERE u.department_id = d.id AND u.status = 'ACTIVE') AS user_count " +
                "FROM departments d " +
                "JOIN organizations o ON o.id = d.org_id " +
                "ORDER BY o.name, d.name"
            );
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject dept = new JSONObject();
                dept.put("id",         rs.getString(1));
                dept.put("name",       rs.getString(2));
                dept.put("org_id",     rs.getString(3));
                dept.put("org_name",   rs.getString(4));
                dept.put("user_count", rs.getLong(5));
                departments.add(dept);
            }
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("departments", departments);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void addDepartment(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String name  = (String) input.get("name");
        String orgId = (String) input.get("org_id");

        if (isBlank(name) || isBlank(orgId)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "name and org_id are required", req.getRequestURI());
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
                "INSERT INTO departments (name, org_id) VALUES (?, ?) RETURNING id::text"
            );
            pstmt.setString(1, name);
            pstmt.setObject(2, UUID.fromString(orgId));

            rs = pstmt.executeQuery();
            if (!rs.next()) {
                OutputProcessor.errorResponse(res, 500, "Internal Error", "Failed to create department", req.getRequestURI());
                return;
            }
            String newId = rs.getString(1);

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("id", newId);
            OutputProcessor.send(res, 200, result);

        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void updateDepartment(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id    = (String) input.get("id");
        String name  = (String) input.get("name");
        String orgId = (String) input.get("org_id");

        if (isBlank(id)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI());
            return;
        }

        StringBuilder sql = new StringBuilder("UPDATE departments SET");
        boolean first = true;

        if (!isBlank(name))  { sql.append(first ? " " : ", ").append("name = ?");   first = false; }
        if (!isBlank(orgId)) { sql.append(first ? " " : ", ").append("org_id = ?"); first = false; }

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
            if (!isBlank(name))  pstmt.setString(idx++, name);
            if (!isBlank(orgId)) pstmt.setObject(idx++, UUID.fromString(orgId));
            pstmt.setObject(idx++, UUID.fromString(id));

            pstmt.executeUpdate();

            JSONObject result = new JSONObject();
            result.put("success", true);
            OutputProcessor.send(res, 200, result);

        } finally {
            if (pool != null) {
                try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
            }
        }
    }

    // -------------------------------------------------------------------------
    // Designations
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject listDesignations() throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        JSONArray designations = new JSONArray();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "SELECT des.id::text, des.title, " +
                "  STRING_AGG(k.kra_title, ', ' ORDER BY k.created_at) AS kra_titles " +
                "FROM designations des " +
                "LEFT JOIN designation_kras k ON k.designation_id = des.id " +
                "GROUP BY des.id, des.title " +
                "ORDER BY des.title"
            );
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject des = new JSONObject();
                des.put("id",         rs.getString(1));
                des.put("title",      rs.getString(2));
                des.put("kra_titles", rs.getString(3));
                designations.add(des);
            }
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("designations", designations);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void addDesignation(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String title = (String) input.get("title");
        String kras  = (String) input.get("kras");

        if (isBlank(title)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "title is required", req.getRequestURI());
            return;
        }

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            pstmt = conn.prepareStatement(
                "INSERT INTO designations (title) VALUES (?) RETURNING id::text"
            );
            pstmt.setString(1, title);
            rs = pstmt.executeQuery();
            if (!rs.next()) {
                conn.rollback();
                OutputProcessor.errorResponse(res, 500, "Internal Error", "Failed to create designation", req.getRequestURI());
                return;
            }
            String newId = rs.getString(1);
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            if (!isBlank(kras)) {
                String[] kraTitles = kras.split(",");
                for (String kraTitle : kraTitles) {
                    String trimmed = kraTitle.trim();
                    if (trimmed.isEmpty()) continue;
                    pstmt = conn.prepareStatement(
                        "INSERT INTO designation_kras (designation_id, kra_title, responsibility_description) VALUES (?, ?, ?)"
                    );
                    pstmt.setObject(1, UUID.fromString(newId));
                    pstmt.setString(2, trimmed);
                    pstmt.setString(3, "");
                    pstmt.executeUpdate();
                    try { pool.cleanup(null, pstmt, null); } catch (Exception ignored) {}
                    pstmt = null;
                }
            }

            conn.commit();

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("id", newId);
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

    @SuppressWarnings("unchecked")
    private void updateDesignation(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id    = (String) input.get("id");
        String title = (String) input.get("title");
        String kras  = (String) input.get("kras");

        if (isBlank(id) || isBlank(title)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id and title are required", req.getRequestURI());
            return;
        }

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            // Update title
            pstmt = conn.prepareStatement("UPDATE designations SET title = ? WHERE id = ?");
            pstmt.setString(1, title);
            pstmt.setObject(2, UUID.fromString(id));
            pstmt.executeUpdate();
            try { pool.cleanup(null, pstmt, null); } catch (Exception ignored) {}
            pstmt = null;

            // Delete existing KRAs
            pstmt = conn.prepareStatement("DELETE FROM designation_kras WHERE designation_id = ?");
            pstmt.setObject(1, UUID.fromString(id));
            pstmt.executeUpdate();
            try { pool.cleanup(null, pstmt, null); } catch (Exception ignored) {}
            pstmt = null;

            // Re-insert KRAs
            if (!isBlank(kras)) {
                String[] kraTitles = kras.split(",");
                for (String kraTitle : kraTitles) {
                    String trimmed = kraTitle.trim();
                    if (trimmed.isEmpty()) continue;
                    pstmt = conn.prepareStatement(
                        "INSERT INTO designation_kras (designation_id, kra_title, responsibility_description) VALUES (?, ?, ?)"
                    );
                    pstmt.setObject(1, UUID.fromString(id));
                    pstmt.setString(2, trimmed);
                    pstmt.setString(3, "");
                    pstmt.executeUpdate();
                    try { pool.cleanup(null, pstmt, null); } catch (Exception ignored) {}
                    pstmt = null;
                }
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
        "evidence", "operations", "incidents", "reports", "helpdesk"
    };

    private static final String[] ROLES = {
        "ADMIN", "RISK_OWNER", "COMPLIANCE_OFFICER",
        "INTERNAL_AUDITOR", "IT_STAFF", "USER"
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
