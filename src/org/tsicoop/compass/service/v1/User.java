package org.tsicoop.compass.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONObject;
import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.JWTUtil;
import org.tsicoop.compass.framework.LoginRateLimiter;
import org.tsicoop.compass.framework.PortalConfig;
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
                case "logout":
                    logout(req, res);
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

        // Throttle repeated attempts. Keyed on client address plus email so one noisy address cannot lock
        // everyone out when many users share a proxy, while a guessing run against one account is still stopped.
        String limiterKey = LoginRateLimiter.getClientIp(req) + "|" + email.toLowerCase().trim();
        if (!LoginRateLimiter.isAllowed(limiterKey)) {
            OutputProcessor.errorResponse(res, 429, "Too Many Requests", "Too many sign-in attempts. Please wait a few minutes and try again.", req.getRequestURI());
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
            if ("VENDOR".equals(role) && !PortalConfig.vendorPortalEnabled()) {
                // Same message as a failed login so the switch does not reveal which accounts exist.
                OutputProcessor.errorResponse(res, 401, "Unauthorized", "Invalid email or password", req.getRequestURI());
                return;
            }
            LoginRateLimiter.recordSuccess(limiterKey);
            String token = JWTUtil.generateToken(email.toLowerCase().trim(), username, role);

            // Load module permissions for this role on the same connection so the
            // frontend can enforce RBAC without a second round-trip.
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            JSONObject permissions = new JSONObject();
            pstmt = conn.prepareStatement(
                "SELECT module, permission_level FROM role_permissions WHERE role = ?"
            );
            pstmt.setString(1, role);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                permissions.put(rs.getString("module"), rs.getString("permission_level"));
            }

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("token", token);
            result.put("username", username);
            result.put("role", role);
            result.put("permissions", permissions);
            InputProcessor.setConsoleSessionCookie(res, token);
            OutputProcessor.send(res, 200, result);

        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Clears the console session cookie and revokes its token. Deliberately callable
     * without a valid Bearer token: the browser's cookie is all that is needed to sign out.
     */
    @SuppressWarnings("unchecked")
    private void logout(HttpServletRequest req, HttpServletResponse res) {
        jakarta.servlet.http.Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (jakarta.servlet.http.Cookie c : cookies) {
                if (InputProcessor.CONSOLE_SESSION_COOKIE.equals(c.getName()) && JWTUtil.isTokenValid(c.getValue())) {
                    String jti = JWTUtil.getJtiFromToken(c.getValue());
                    java.util.Date expiry = JWTUtil.getExpiryFromToken(c.getValue());
                    if (jti != null && expiry != null) org.tsicoop.compass.framework.TokenBlocklist.revoke(jti, expiry.getTime());
                }
            }
        }
        InputProcessor.clearConsoleSessionCookie(res);
        JSONObject out = new JSONObject();
        out.put("success", true);
        OutputProcessor.send(res, 200, out);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
