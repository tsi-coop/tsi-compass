package org.tsicoop.compass.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONObject;
import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.OutputProcessor;
import org.tsicoop.compass.framework.PasswordHasher;
import org.tsicoop.compass.framework.PoolDB;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class Setup implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");
            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing _func parameter", req.getRequestURI());
                return;
            }
            switch (func.toLowerCase()) {
                case "check_setup":
                    OutputProcessor.send(res, 200, checkSetup());
                    break;
                case "complete_setup":
                    completeSetup(req, res, input);
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

    @SuppressWarnings("unchecked")
    private JSONObject checkSetup() {
        JSONObject result = new JSONObject();
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'");
            rs = pstmt.executeQuery();
            if (rs.next() && rs.getLong(1) > 0) {
                result.put("setup_done", true);
            } else {
                result.put("setup_done", false);
            }
        } catch (Exception e) {
            result.put("setup_done", false);
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void completeSetup(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String orgName = (String) input.get("org_name");
        String adminName = (String) input.get("admin_name");
        String adminEmail = (String) input.get("admin_email");
        String adminPassword = (String) input.get("admin_password");

        if (isBlank(orgName) || isBlank(adminName) || isBlank(adminEmail) || isBlank(adminPassword)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "Required fields are missing", req.getRequestURI());
            return;
        }
        if (adminPassword.length() < 10) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "Password must be at least 10 characters", req.getRequestURI());
            return;
        }

        if (Boolean.TRUE.equals(checkSetup().get("setup_done"))) {
            OutputProcessor.errorResponse(res, 409, "Conflict", "Setup has already been completed", req.getRequestURI());
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
                "INSERT INTO organizations (name, type, parent_id) VALUES (?, 'HEAD_OFFICE', NULL) RETURNING id::text"
            );
            pstmt.setString(1, orgName);
            rs = pstmt.executeQuery();
            if (!rs.next()) {
                conn.rollback();
                OutputProcessor.errorResponse(res, 500, "Internal Error", "Failed to create organisation", req.getRequestURI());
                return;
            }
            String orgId = rs.getString(1);
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            String passwordHash = new PasswordHasher().hashPassword(adminPassword);

            pstmt = conn.prepareStatement(
                "INSERT INTO users (email, password_hash, username, role, status) VALUES (?, ?, ?, 'ADMIN', 'ACTIVE')"
            );
            pstmt.setString(1, adminEmail.toLowerCase().trim());
            pstmt.setString(2, passwordHash);
            pstmt.setString(3, adminName);
            pstmt.executeUpdate();

            conn.commit();

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "Setup completed successfully");
            result.put("org_id", orgId);
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

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
