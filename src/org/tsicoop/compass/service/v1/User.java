package org.tsicoop.compass.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONObject;
import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.JWTUtil;
import org.tsicoop.compass.framework.OutputProcessor;
import org.tsicoop.compass.framework.PasswordHasher;
import org.tsicoop.compass.framework.PoolDB;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class User implements Action {

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
                case "login":
                    login(req, res, input);
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
    private void login(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String email = (String) input.get("email");
        String password = (String) input.get("password");

        if (isBlank(email) || isBlank(password)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "Email and password are required", req.getRequestURI());
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
                "SELECT password_hash, username, role FROM users WHERE email = ? AND status = 'ACTIVE'"
            );
            pstmt.setString(1, email.toLowerCase().trim());
            rs = pstmt.executeQuery();

            // Use the same error message for both "not found" and "wrong password" to prevent user enumeration
            if (!rs.next() || !new PasswordHasher().checkPassword(password, rs.getString("password_hash"))) {
                OutputProcessor.errorResponse(res, 401, "Unauthorized", "Invalid email or password", req.getRequestURI());
                return;
            }

            String username = rs.getString("username");
            String role = rs.getString("role");
            String token = JWTUtil.generateToken(email.toLowerCase().trim(), username, role);

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("token", token);
            result.put("username", username);
            result.put("role", role);
            OutputProcessor.send(res, 200, result);

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
