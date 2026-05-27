package org.tsicoop.compass.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONObject;
import org.tsicoop.compass.framework.*;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class Operator implements Action {

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
                case "verify_recovery_key":
                    verifyRecoveryKey(req, res, input);
                    break;
                case "reset_password_via_recovery":
                    resetPasswordViaRecovery(req, res, input);
                    break;
                default:
                    OutputProcessor.errorResponse(res, 400, "Bad Request", "Unknown _func: " + func, req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Server Error", e.getMessage(), req.getRequestURI());
        }
    }

    @SuppressWarnings("unchecked")
    private void verifyRecoveryKey(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String email      = strVal(input, "email");
        String passphrase = strVal(input, "passphrase");

        if (isBlank(email) || isBlank(passphrase)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "email and passphrase are required", req.getRequestURI());
            return;
        }

        String keyHash = sha256(passphrase);

        PoolDB pool = null; Connection conn = null;
        PreparedStatement pstmt = null; ResultSet rs = null;
        boolean valid = false;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "SELECT id FROM users WHERE email = ? AND recovery_key_hash = ? AND status = 'ACTIVE'"
            );
            pstmt.setString(1, email);
            pstmt.setString(2, keyHash);
            rs = pstmt.executeQuery();
            valid = rs.next();
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }

        if (!valid) {
            OutputProcessor.errorResponse(res, 401, "Unauthorized", "Invalid email or recovery key", req.getRequestURI());
            return;
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        OutputProcessor.send(res, 200, result);
    }

    @SuppressWarnings("unchecked")
    private void resetPasswordViaRecovery(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String email       = strVal(input, "email");
        String passphrase  = strVal(input, "passphrase");
        String newPassword = strVal(input, "new_password");

        if (isBlank(email) || isBlank(passphrase) || isBlank(newPassword)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "email, passphrase, and new_password are required", req.getRequestURI());
            return;
        }

        String keyHash = sha256(passphrase);

        PoolDB pool = null; Connection conn = null;
        PreparedStatement pstmt = null; ResultSet rs = null;
        boolean valid = false;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            // Verify recovery key
            pstmt = conn.prepareStatement(
                "SELECT id FROM users WHERE email = ? AND recovery_key_hash = ? AND status = 'ACTIVE'"
            );
            pstmt.setString(1, email);
            pstmt.setString(2, keyHash);
            rs = pstmt.executeQuery();
            valid = rs.next();
            pstmt.close(); rs.close();

            if (!valid) {
                OutputProcessor.errorResponse(res, 401, "Unauthorized", "Invalid email or recovery key", req.getRequestURI());
                return;
            }

            // Reset password with BCrypt
            String pwHash = new PasswordHasher().hashPassword(newPassword);
            pstmt = conn.prepareStatement("UPDATE users SET password_hash = ? WHERE email = ?");
            pstmt.setString(1, pwHash);
            pstmt.setString(2, email);
            pstmt.executeUpdate();
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }

        EventLog.log(email, "PASSWORD_RESET_VIA_RECOVERY", "{\"email\":\"" + email + "\"}");

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
