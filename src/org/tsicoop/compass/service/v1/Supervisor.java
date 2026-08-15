package org.tsicoop.compass.service.v1;

import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.OutputProcessor;
import org.tsicoop.compass.framework.PasswordHasher;
import org.tsicoop.compass.framework.PoolDB;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * Supervisor portal: a business-side manager provisions/manages the USER
 * accounts that report to them and approves or rejects the tickets/change
 * requests those USERs submit, when business_settings.require_supervisor_approval
 * is enabled (see SelfService.createTicket/createChangeRequest).
 *
 * Every func here is scoped one-to-many by supervisor_id = the caller's own
 * authenticated id — never taken from the request body — so one Supervisor can
 * never read or act on another Supervisor's team. Because role_permissions only
 * gates access at module granularity (this service shares the "selfservice"
 * module with USER, which also holds WRITE there), post() additionally enforces
 * that the caller's role is SUPERVISOR or ADMIN before dispatching any _func.
 */
public class Supervisor implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            String role = InputProcessor.getRole(req);
            if (!"SUPERVISOR".equals(role) && !"ADMIN".equals(role)) {
                OutputProcessor.errorResponse(res, 403, "Forbidden", "SUPERVISOR role required", req.getRequestURI());
                return;
            }

            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");
            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing _func", req.getRequestURI()); return;
            }

            UUID selfId = InputProcessor.getAuthenticatedUserId(req);
            if (selfId == null) {
                OutputProcessor.errorResponse(res, 401, "Unauthorized", "Could not resolve authenticated user", req.getRequestURI()); return;
            }

            switch (func.toLowerCase()) {
                case "list_my_team":         OutputProcessor.send(res, 200, listMyTeam(selfId, input));        break;
                case "provision_team_user":  provisionTeamUser(req, res, input, selfId);                       break;
                case "update_team_user":     updateTeamUser(req, res, input, selfId);                          break;
                case "set_team_user_status": setTeamUserStatus(req, res, input, selfId);                       break;
                case "set_team_recovery_key": setTeamRecoveryKey(req, res, input, selfId);                      break;
                case "list_pending_tickets": OutputProcessor.send(res, 200, listPendingTickets(selfId, input)); break;
                case "list_pending_changes": OutputProcessor.send(res, 200, listPendingChanges(selfId, input)); break;
                case "approve_ticket":       approveTicket(req, res, input, selfId);                            break;
                case "reject_ticket":        rejectTicket(req, res, input, selfId);                             break;
                case "approve_change":       approveChange(req, res, input, selfId);                            break;
                case "reject_change":        rejectChange(req, res, input, selfId);                             break;
                default: OutputProcessor.errorResponse(res, 400, "Bad Request", "Unknown: " + func, req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Internal Error", e.getMessage(), req.getRequestURI());
        }
    }

    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse r) { return "POST".equalsIgnoreCase(m); }

    // page/limit -> {page, limit}, page 1-based, limit capped at 100, default 20
    private long[] parsePaging(JSONObject input) {
        long page = 1L, limit = 20L;
        Object pageObj  = input.get("page");
        Object limitObj = input.get("limit");
        if (pageObj  instanceof Long) page  = (Long) pageObj;
        if (limitObj instanceof Long) limit = (Long) limitObj;
        if (limit > 100) limit = 100;
        if (page < 1) page = 1;
        return new long[]{page, limit};
    }

    // -------------------------------------------------------------------------
    // Team management
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject listMyTeam(UUID selfId, JSONObject input) throws Exception {
        long[] pg = parsePaging(input); long page = pg[0], limit = pg[1]; long offset = (page - 1) * limit;

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray(); long total = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            // role='USER' excludes an account that was promoted away from USER but
            // still carries a stale supervisor_id — this list is "my USER reports", not
            // "every account that ever had me as supervisor_id".
            p = conn.prepareStatement("SELECT COUNT(*) FROM users WHERE supervisor_id = ? AND role = 'USER'");
            p.setObject(1, selfId);
            rs = p.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
            rs = null; p = null;

            p = conn.prepareStatement(
                "SELECT id::text, username, email, status, created_at::text FROM users " +
                "WHERE supervisor_id = ? AND role = 'USER' ORDER BY username LIMIT ? OFFSET ?"
            );
            p.setObject(1, selfId); p.setLong(2, limit); p.setLong(3, offset);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject u = new JSONObject();
                u.put("id",         rs.getString("id"));
                u.put("username",   rs.getString("username"));
                u.put("email",      rs.getString("email"));
                u.put("status",     rs.getString("status"));
                u.put("created_at", rs.getString("created_at"));
                list.add(u);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("users", list);
        result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void provisionTeamUser(HttpServletRequest req, HttpServletResponse res, JSONObject input, UUID selfId) throws Exception {
        String username = (String) input.get("username");
        String email    = (String) input.get("email");
        String password = (String) input.get("password");

        if (isBlank(username) || isBlank(email) || isBlank(password)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "username, email and password are required", req.getRequestURI()); return;
        }
        if (password.length() < 10) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "Password must be at least 10 characters", req.getRequestURI()); return;
        }

        String passwordHash = new PasswordHasher().hashPassword(password);

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            // role and supervisor_id are forced server-side — never trust the request body for either.
            p = conn.prepareStatement(
                "INSERT INTO users (email, password_hash, username, role, status, supervisor_id) " +
                "VALUES (?, ?, ?, 'USER', 'ACTIVE', ?) RETURNING id::text"
            );
            p.setString(1, email.toLowerCase().trim());
            p.setString(2, passwordHash);
            p.setString(3, username);
            p.setObject(4, selfId);
            rs = p.executeQuery();

            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            result.put("message", "Team member provisioned successfully");
            OutputProcessor.send(res, 200, result);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("unique") || msg.contains("duplicate")) {
                OutputProcessor.errorResponse(res, 409, "Conflict", "A user with that email already exists", req.getRequestURI());
            } else {
                throw e;
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void updateTeamUser(HttpServletRequest req, HttpServletResponse res, JSONObject input, UUID selfId) throws Exception {
        String id       = (String) input.get("id");
        String username = (String) input.get("username");
        String email    = (String) input.get("email");
        if (isBlank(id)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI()); return;
        }
        if (isBlank(username) && isBlank(email)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "No fields to update", req.getRequestURI()); return;
        }

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "UPDATE users SET username = COALESCE(?, username), email = COALESCE(?, email) " +
                "WHERE id = ? AND supervisor_id = ? AND role = 'USER'"
            );
            p.setString(1, isBlank(username) ? null : username);
            p.setString(2, isBlank(email) ? null : email.toLowerCase().trim());
            p.setObject(3, UUID.fromString(id));
            p.setObject(4, selfId);
            int updated = p.executeUpdate();
            if (updated == 0) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "No team member found with that id", req.getRequestURI()); return;
            }
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("unique") || msg.contains("duplicate")) {
                OutputProcessor.errorResponse(res, 409, "Conflict", "A user with that email already exists", req.getRequestURI());
            } else {
                throw e;
            }
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void setTeamUserStatus(HttpServletRequest req, HttpServletResponse res, JSONObject input, UUID selfId) throws Exception {
        String id     = (String) input.get("id");
        String status = (String) input.get("status");
        if (isBlank(id) || isBlank(status)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id and status are required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("UPDATE users SET status = ? WHERE id = ? AND supervisor_id = ? AND role = 'USER'");
            p.setString(1, status);
            p.setObject(2, UUID.fromString(id));
            p.setObject(3, selfId);
            int updated = p.executeUpdate();
            if (updated == 0) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "No team member found with that id", req.getRequestURI()); return;
            }
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    // Same 5-word list and hashing scheme as Platform.setRecoveryKey (the ADMIN
    // equivalent), so recovery passphrases set by a Manager are indistinguishable
    // from ones set by an ADMIN at /password-reset.html.
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
    private void setTeamRecoveryKey(HttpServletRequest req, HttpServletResponse res, JSONObject input, UUID selfId) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI()); return;
        }

        java.util.Random rng = new java.util.Random();
        String[] words = new String[5];
        for (int i = 0; i < 5; i++) words[i] = WORD_LIST[rng.nextInt(WORD_LIST.length)];
        String passphrase = String.join("-", words);
        String keyHash = sha256hex(passphrase);

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("UPDATE users SET recovery_key_hash = ? WHERE id = ? AND supervisor_id = ? AND role = 'USER'");
            p.setString(1, keyHash);
            p.setObject(2, UUID.fromString(id));
            p.setObject(3, selfId);
            int updated = p.executeUpdate();
            if (updated == 0) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "No team member found with that id", req.getRequestURI()); return;
            }
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }

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
    // Ticket approvals
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject listPendingTickets(UUID selfId, JSONObject input) throws Exception {
        long[] pg = parsePaging(input); long page = pg[0], limit = pg[1]; long offset = (page - 1) * limit;

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray(); long total = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            p = conn.prepareStatement(
                "SELECT COUNT(*) FROM helpdesk_tickets ht JOIN users u ON u.id = ht.created_by " +
                "WHERE u.supervisor_id = ? AND ht.approval_status = 'PENDING'"
            );
            p.setObject(1, selfId);
            rs = p.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
            rs = null; p = null;

            p = conn.prepareStatement(
                "SELECT ht.id::text, ht.title, ht.description, ht.priority, ht.created_at::text, u.username AS requester_name, " +
                "tc.name AS category_name, tsc.name AS subcategory_name " +
                "FROM helpdesk_tickets ht JOIN users u ON u.id = ht.created_by " +
                "LEFT JOIN ticket_categories tc ON tc.id = ht.category_id " +
                "LEFT JOIN ticket_subcategories tsc ON tsc.id = ht.subcategory_id " +
                "WHERE u.supervisor_id = ? AND ht.approval_status = 'PENDING' " +
                "ORDER BY ht.created_at ASC LIMIT ? OFFSET ?"
            );
            p.setObject(1, selfId); p.setLong(2, limit); p.setLong(3, offset);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject t = new JSONObject();
                t.put("id",             rs.getString("id"));
                t.put("title",          rs.getString("title"));
                t.put("description",    rs.getString("description"));
                t.put("priority",       rs.getString("priority"));
                t.put("created_at",     rs.getString("created_at"));
                t.put("requester_name", rs.getString("requester_name"));
                t.put("category_name",  rs.getString("category_name"));
                t.put("subcategory_name", rs.getString("subcategory_name"));
                list.add(t);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("tickets", list);
        result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void approveTicket(HttpServletRequest req, HttpServletResponse res, JSONObject input, UUID selfId) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI()); return; }

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "UPDATE helpdesk_tickets ht SET approval_status = 'APPROVED', approver_id = ?, approved_at = now() " +
                "FROM users u WHERE ht.id = ? AND ht.created_by = u.id AND u.supervisor_id = ? AND ht.approval_status = 'PENDING' " +
                "RETURNING ht.title"
            );
            p.setObject(1, selfId); p.setObject(2, UUID.fromString(id)); p.setObject(3, selfId);
            rs = p.executeQuery();
            if (!rs.next()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Ticket not found, not pending, or not submitted by your team", req.getRequestURI()); return;
            }
            String title = rs.getString(1);
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
            Notification.emit("TICKET_CREATED", "helpdesk", "New helpdesk ticket",
                "\"" + title + "\" was approved and is ready for triage", "operations-helpdesk.html", UUID.fromString(id));
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void rejectTicket(HttpServletRequest req, HttpServletResponse res, JSONObject input, UUID selfId) throws Exception {
        String id     = (String) input.get("id");
        String reason = (String) input.get("reason");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI()); return; }
        String rejectionReason = isBlank(reason) ? "No reason provided" : reason;

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "UPDATE helpdesk_tickets ht SET approval_status = 'REJECTED', approver_id = ?, approved_at = now(), rejection_reason = ? " +
                "FROM users u WHERE ht.id = ? AND ht.created_by = u.id AND u.supervisor_id = ? AND ht.approval_status = 'PENDING' " +
                "RETURNING ht.title, ht.created_by::text"
            );
            p.setObject(1, selfId); p.setString(2, rejectionReason); p.setObject(3, UUID.fromString(id)); p.setObject(4, selfId);
            rs = p.executeQuery();
            if (!rs.next()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Ticket not found, not pending, or not submitted by your team", req.getRequestURI()); return;
            }
            String title = rs.getString(1);
            UUID requesterId = UUID.fromString(rs.getString(2));
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
            Notification.emitToUser("TICKET_REJECTED", "Ticket rejected",
                "\"" + title + "\" was rejected by your supervisor: " + rejectionReason, "selfservice/index.html",
                UUID.fromString(id), requesterId);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    // -------------------------------------------------------------------------
    // Change request approvals
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject listPendingChanges(UUID selfId, JSONObject input) throws Exception {
        long[] pg = parsePaging(input); long page = pg[0], limit = pg[1]; long offset = (page - 1) * limit;

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray(); long total = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            p = conn.prepareStatement(
                "SELECT COUNT(*) FROM change_requests cr JOIN users u ON u.id = cr.requester_id " +
                "WHERE u.supervisor_id = ? AND cr.approval_status = 'PENDING'"
            );
            p.setObject(1, selfId);
            rs = p.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
            rs = null; p = null;

            p = conn.prepareStatement(
                "SELECT cr.id::text, cr.title, cr.description, cr.created_at::text, u.username AS requester_name " +
                "FROM change_requests cr JOIN users u ON u.id = cr.requester_id " +
                "WHERE u.supervisor_id = ? AND cr.approval_status = 'PENDING' " +
                "ORDER BY cr.created_at ASC LIMIT ? OFFSET ?"
            );
            p.setObject(1, selfId); p.setLong(2, limit); p.setLong(3, offset);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject c = new JSONObject();
                c.put("id",             rs.getString("id"));
                c.put("title",          rs.getString("title"));
                c.put("description",    rs.getString("description"));
                c.put("created_at",     rs.getString("created_at"));
                c.put("requester_name", rs.getString("requester_name"));
                list.add(c);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("changes", list);
        result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void approveChange(HttpServletRequest req, HttpServletResponse res, JSONObject input, UUID selfId) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI()); return; }

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "UPDATE change_requests cr SET approval_status = 'APPROVED', approver_id = ?, approved_at = now() " +
                "FROM users u WHERE cr.id = ? AND cr.requester_id = u.id AND u.supervisor_id = ? AND cr.approval_status = 'PENDING' " +
                "RETURNING cr.title"
            );
            p.setObject(1, selfId); p.setObject(2, UUID.fromString(id)); p.setObject(3, selfId);
            rs = p.executeQuery();
            if (!rs.next()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Change request not found, not pending, or not submitted by your team", req.getRequestURI()); return;
            }
            String title = rs.getString(1);
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
            Notification.emit("CHANGE_REQUEST_CREATED", "operations", "New change request",
                "\"" + title + "\" was approved and is ready for review", "operations-changes.html", UUID.fromString(id));
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void rejectChange(HttpServletRequest req, HttpServletResponse res, JSONObject input, UUID selfId) throws Exception {
        String id     = (String) input.get("id");
        String reason = (String) input.get("reason");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI()); return; }
        String rejectionReason = isBlank(reason) ? "No reason provided" : reason;

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "UPDATE change_requests cr SET approval_status = 'REJECTED', approver_id = ?, approved_at = now(), rejection_reason = ? " +
                "FROM users u WHERE cr.id = ? AND cr.requester_id = u.id AND u.supervisor_id = ? AND cr.approval_status = 'PENDING' " +
                "RETURNING cr.title, cr.requester_id::text"
            );
            p.setObject(1, selfId); p.setString(2, rejectionReason); p.setObject(3, UUID.fromString(id)); p.setObject(4, selfId);
            rs = p.executeQuery();
            if (!rs.next()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Change request not found, not pending, or not submitted by your team", req.getRequestURI()); return;
            }
            String title = rs.getString(1);
            UUID requesterId = UUID.fromString(rs.getString(2));
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
            Notification.emitToUser("CHANGE_REQUEST_REJECTED", "Change request rejected",
                "\"" + title + "\" was rejected by your supervisor: " + rejectionReason, "selfservice/index.html",
                UUID.fromString(id), requesterId);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
