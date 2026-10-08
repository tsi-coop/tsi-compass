package org.tsicoop.compass.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONObject;
import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.OutputProcessor;
import org.tsicoop.compass.framework.PasswordHasher;
import org.tsicoop.compass.framework.PoolDB;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet; // used by checkSetup

public class Setup implements Action {

    // Shared secret gating complete_setup. No built-in fallback: setup stays disabled
    // (fails closed) until the deployer explicitly configures it.
    private static final String BOOTSTRAP_TOKEN_ENV = "TSI_BOOTSTRAP_TOKEN";

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
        String bootstrapToken = System.getenv(BOOTSTRAP_TOKEN_ENV);
        if (bootstrapToken == null || bootstrapToken.isEmpty()) {
            OutputProcessor.errorResponse(res, 503, "Setup Disabled", "Initial setup is disabled: " + BOOTSTRAP_TOKEN_ENV + " is not configured on the server.", req.getRequestURI());
            return;
        }
        Object provided = input.get("setup_token");
        if (!(provided instanceof String) || !MessageDigest.isEqual(
                ((String) provided).getBytes(StandardCharsets.UTF_8), bootstrapToken.getBytes(StandardCharsets.UTF_8))) {
            // Checked before any DB access so a caller without the token learns
            // nothing about whether the system has already been configured.
            OutputProcessor.errorResponse(res, 401, "Unauthorized", "Invalid or missing setup token.", req.getRequestURI());
            return;
        }

        String adminName     = (String) input.get("admin_name");
        String adminEmail    = (String) input.get("admin_email");
        String adminPassword = (String) input.get("admin_password");

        if (isBlank(adminName) || isBlank(adminEmail) || isBlank(adminPassword)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "Required fields are missing", req.getRequestURI());
            return;
        }
        if (adminPassword.length() < 10) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "Password must be at least 10 characters", req.getRequestURI());
            return;
        }

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            // SERIALIZABLE closes the check-then-insert race: two requests that both see
            // no ADMIN can no longer both succeed; Postgres aborts the loser (SQLState 40001).
            conn.setAutoCommit(false);
            conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

            pstmt = conn.prepareStatement("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'");
            rs = pstmt.executeQuery();
            if (rs.next() && rs.getLong(1) > 0) {
                conn.rollback();
                OutputProcessor.errorResponse(res, 409, "Conflict", "Setup has already been completed", req.getRequestURI());
                return;
            }
            pstmt.close();

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
            OutputProcessor.send(res, 200, result);

        } catch (Exception e) {
            if (conn != null) {
                try { conn.rollback(); } catch (Exception ignored) {}
            }
            if (e instanceof java.sql.SQLException && "40001".equals(((java.sql.SQLException) e).getSQLState())) {
                OutputProcessor.errorResponse(res, 409, "Conflict", "Setup has already been completed", req.getRequestURI());
                return;
            }
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("unique") || msg.contains("duplicate")) {
                OutputProcessor.errorResponse(res, 409, "Conflict", "A user with that email already exists", req.getRequestURI());
            } else {
                throw e;
            }
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                } catch (Exception ignored) {}
            }
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
