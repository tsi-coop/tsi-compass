package org.tsicoop.compass.service.v1;

import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.OutputProcessor;
import org.tsicoop.compass.framework.PoolDB;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.sql.*;
import java.util.UUID;

/**
 * Self-service portal for org employees (role USER): submit/track helpdesk tickets and
 * change requests, and view/act on their own policy attestations and assigned training.
 *
 * Every func here is scoped to the caller's own authenticated identity — ids are
 * resolved server-side via InputProcessor.getAuthenticatedUserId(req), never taken
 * from the request body, so one USER can never read or write another user's records.
 */
public class SelfService implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
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
                case "create_ticket":         createTicket(req, res, input, selfId);                break;
                case "list_my_tickets":       OutputProcessor.send(res, 200, listMyTickets(selfId, input));       break;
                case "create_change_request": createChangeRequest(req, res, input, selfId);         break;
                case "list_my_changes":       OutputProcessor.send(res, 200, listMyChanges(selfId, input));       break;
                case "list_pending_policies": OutputProcessor.send(res, 200, listPendingPolicies(selfId, input)); break;
                case "list_my_attestations":  OutputProcessor.send(res, 200, listMyAttestations(selfId, input));  break;
                case "acknowledge_policy":    acknowledgePolicy(req, res, input, selfId);            break;
                case "list_my_trainings":     OutputProcessor.send(res, 200, listMyTrainings(selfId, input));     break;
                case "complete_training":     completeTraining(req, res, input, selfId);      break;
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
    // Tickets
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void createTicket(HttpServletRequest req, HttpServletResponse res, JSONObject input, UUID selfId) throws Exception {
        String title    = (String) input.get("title");
        String desc     = (String) input.get("description");
        String priority = (String) input.get("priority");
        if (isBlank(title) || isBlank(desc)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "title and description required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("INSERT INTO helpdesk_tickets (title,description,priority,created_by) VALUES (?,?,?,?) RETURNING id::text");
            p.setString(1, title); p.setString(2, desc); p.setString(3, isBlank(priority) ? "MEDIUM" : priority);
            p.setObject(4, selfId);
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            String newId = null;
            if (rs.next()) { newId = rs.getString(1); result.put("id", newId); }
            OutputProcessor.send(res, 200, result);
            if (newId != null) {
                Notification.emit("TICKET_CREATED", "helpdesk", "New helpdesk ticket",
                    "\"" + title + "\" was submitted", "operations-helpdesk.html", UUID.fromString(newId));
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private JSONObject listMyTickets(UUID selfId, JSONObject input) throws Exception {
        String search = (String) input.get("search");
        long[] pg = parsePaging(input); long page = pg[0], limit = pg[1]; long offset = (page - 1) * limit;

        StringBuilder where = new StringBuilder(" WHERE created_by = ?");
        if (!isBlank(search)) where.append(" AND (title ILIKE ? OR description ILIKE ?)");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray(); long total = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            p = conn.prepareStatement("SELECT COUNT(*) FROM helpdesk_tickets" + where);
            int idx = 1; p.setObject(idx++, selfId);
            if (!isBlank(search)) { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); }
            rs = p.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
            rs = null; p = null;

            p = conn.prepareStatement(
                "SELECT id::text, title, description, status, priority, created_at::text " +
                "FROM helpdesk_tickets" + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?"
            );
            idx = 1; p.setObject(idx++, selfId);
            if (!isBlank(search)) { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); }
            p.setLong(idx++, limit); p.setLong(idx++, offset);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject t = new JSONObject();
                t.put("id",          rs.getString("id"));
                t.put("title",       rs.getString("title"));
                t.put("description", rs.getString("description"));
                t.put("status",      rs.getString("status"));
                t.put("priority",    rs.getString("priority"));
                t.put("created_at",  rs.getString("created_at"));
                list.add(t);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("tickets", list);
        result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
    }

    // -------------------------------------------------------------------------
    // Change requests
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void createChangeRequest(HttpServletRequest req, HttpServletResponse res, JSONObject input, UUID selfId) throws Exception {
        String title = (String) input.get("title");
        String desc  = (String) input.get("description");
        if (isBlank(title) || isBlank(desc)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "title and description required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("INSERT INTO change_requests (title,description,requester_id,status) VALUES (?,?,?,'SUBMITTED') RETURNING id::text");
            p.setString(1, title); p.setString(2, desc); p.setObject(3, selfId);
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            String newId = null;
            if (rs.next()) { newId = rs.getString(1); result.put("id", newId); }
            OutputProcessor.send(res, 200, result);
            if (newId != null) {
                Notification.emit("CHANGE_REQUEST_CREATED", "operations", "New change request",
                    "\"" + title + "\" was submitted", "operations-changes.html", UUID.fromString(newId));
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private JSONObject listMyChanges(UUID selfId, JSONObject input) throws Exception {
        String search = (String) input.get("search");
        long[] pg = parsePaging(input); long page = pg[0], limit = pg[1]; long offset = (page - 1) * limit;

        StringBuilder where = new StringBuilder(" WHERE requester_id = ?");
        if (!isBlank(search)) where.append(" AND (title ILIKE ? OR description ILIKE ?)");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray(); long total = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            p = conn.prepareStatement("SELECT COUNT(*) FROM change_requests" + where);
            int idx = 1; p.setObject(idx++, selfId);
            if (!isBlank(search)) { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); }
            rs = p.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
            rs = null; p = null;

            p = conn.prepareStatement(
                "SELECT id::text, title, description, stage, status, created_at::text " +
                "FROM change_requests" + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?"
            );
            idx = 1; p.setObject(idx++, selfId);
            if (!isBlank(search)) { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); }
            p.setLong(idx++, limit); p.setLong(idx++, offset);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject c = new JSONObject();
                c.put("id",          rs.getString("id"));
                c.put("title",       rs.getString("title"));
                c.put("description", rs.getString("description"));
                c.put("stage",       rs.getString("stage"));
                c.put("status",      rs.getString("status"));
                c.put("created_at",  rs.getString("created_at"));
                list.add(c);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("changes", list);
        result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
    }

    // -------------------------------------------------------------------------
    // Policy attestations
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject listPendingPolicies(UUID selfId, JSONObject input) throws Exception {
        String search = (String) input.get("search");
        long[] pg = parsePaging(input); long page = pg[0], limit = pg[1]; long offset = (page - 1) * limit;

        StringBuilder where = new StringBuilder(
            " WHERE status = 'PUBLISHED' AND id NOT IN (SELECT policy_id FROM policy_attestations WHERE user_id = ?)"
        );
        if (!isBlank(search)) where.append(" AND (title ILIKE ? OR type ILIKE ?)");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray(); long total = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            p = conn.prepareStatement("SELECT COUNT(*) FROM policies" + where);
            int idx = 1; p.setObject(idx++, selfId);
            if (!isBlank(search)) { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); }
            rs = p.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
            rs = null; p = null;

            p = conn.prepareStatement(
                "SELECT id::text, title, type, version FROM policies" + where + " ORDER BY title LIMIT ? OFFSET ?"
            );
            idx = 1; p.setObject(idx++, selfId);
            if (!isBlank(search)) { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); }
            p.setLong(idx++, limit); p.setLong(idx++, offset);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject pol = new JSONObject();
                pol.put("id",      rs.getString("id"));
                pol.put("title",   rs.getString("title"));
                pol.put("type",    rs.getString("type"));
                pol.put("version", rs.getString("version"));
                list.add(pol);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("policies", list);
        result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listMyAttestations(UUID selfId, JSONObject input) throws Exception {
        String search = (String) input.get("search");
        long[] pg = parsePaging(input); long page = pg[0], limit = pg[1]; long offset = (page - 1) * limit;

        StringBuilder where = new StringBuilder(" WHERE pa.user_id = ?");
        if (!isBlank(search)) where.append(" AND (pol.title ILIKE ? OR pol.type ILIKE ?)");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray(); long total = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            p = conn.prepareStatement(
                "SELECT COUNT(*) FROM policy_attestations pa JOIN policies pol ON pol.id = pa.policy_id" + where
            );
            int idx = 1; p.setObject(idx++, selfId);
            if (!isBlank(search)) { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); }
            rs = p.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
            rs = null; p = null;

            p = conn.prepareStatement(
                "SELECT pol.id::text, pol.title, pol.type, pol.version, pa.acknowledged_at::text " +
                "FROM policy_attestations pa JOIN policies pol ON pol.id = pa.policy_id" + where +
                " ORDER BY pa.acknowledged_at DESC LIMIT ? OFFSET ?"
            );
            idx = 1; p.setObject(idx++, selfId);
            if (!isBlank(search)) { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); }
            p.setLong(idx++, limit); p.setLong(idx++, offset);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject a = new JSONObject();
                a.put("id",              rs.getString("id"));
                a.put("title",           rs.getString("title"));
                a.put("type",            rs.getString("type"));
                a.put("version",         rs.getString("version"));
                a.put("acknowledged_at", rs.getString("acknowledged_at"));
                list.add(a);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("attestations", list);
        result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void acknowledgePolicy(HttpServletRequest req, HttpServletResponse res, JSONObject input, UUID selfId) throws Exception {
        String policyId = (String) input.get("policy_id");
        if (isBlank(policyId)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "policy_id required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        boolean acknowledged = false;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "INSERT INTO policy_attestations (policy_id, user_id) VALUES (?::uuid, ?) ON CONFLICT (policy_id, user_id) DO NOTHING"
            );
            p.setString(1, policyId); p.setObject(2, selfId);
            acknowledged = p.executeUpdate() > 0;
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }

        if (acknowledged) {
            String policyTitle = getPolicyTitle(policyId);
            String name = InputProcessor.getName(req);
            Notification.emit("POLICY_AFFIRMED", "governance", "Policy affirmed",
                (isBlank(name) ? "An employee" : name) + " acknowledged \"" + (isBlank(policyTitle) ? "a policy" : policyTitle) + "\"",
                "governance-attestations.html", UUID.fromString(policyId));
        }
    }

    private String getPolicyTitle(String policyId) throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT title FROM policies WHERE id = ?::uuid");
            p.setString(1, policyId);
            rs = p.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    // -------------------------------------------------------------------------
    // Awareness training
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject listMyTrainings(UUID selfId, JSONObject input) throws Exception {
        String search = (String) input.get("search");
        long[] pg = parsePaging(input); long page = pg[0], limit = pg[1]; long offset = (page - 1) * limit;

        StringBuilder where = new StringBuilder(" WHERE ce.user_id = ?");
        if (!isBlank(search)) where.append(" AND (ac.name ILIKE ? OR ac.description ILIKE ? OR tm.title ILIKE ?)");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray(); long total = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            p = conn.prepareStatement(
                "SELECT COUNT(*) FROM campaign_enrollments ce " +
                "JOIN awareness_campaigns ac ON ac.id = ce.campaign_id " +
                "LEFT JOIN training_materials tm ON tm.id = ac.material_id" + where
            );
            int idx = 1; p.setObject(idx++, selfId);
            if (!isBlank(search)) { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); p.setString(idx++, like); }
            rs = p.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
            rs = null; p = null;

            p = conn.prepareStatement(
                "SELECT ac.id::text AS campaign_id, ac.name AS campaign_name, ac.description, " +
                "tm.title AS material_title, tm.content_url, ce.status, ce.completed_at::text " +
                "FROM campaign_enrollments ce " +
                "JOIN awareness_campaigns ac ON ac.id = ce.campaign_id " +
                "LEFT JOIN training_materials tm ON tm.id = ac.material_id" + where +
                " ORDER BY ac.scheduled_start DESC LIMIT ? OFFSET ?"
            );
            idx = 1; p.setObject(idx++, selfId);
            if (!isBlank(search)) { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); p.setString(idx++, like); }
            p.setLong(idx++, limit); p.setLong(idx++, offset);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject t = new JSONObject();
                t.put("campaign_id",     rs.getString("campaign_id"));
                t.put("campaign_name",   rs.getString("campaign_name"));
                t.put("description",     rs.getString("description"));
                t.put("material_title",  rs.getString("material_title"));
                t.put("content_url",     rs.getString("content_url"));
                t.put("status",          rs.getString("status"));
                t.put("completed_at",    rs.getString("completed_at"));
                list.add(t);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("trainings", list);
        result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void completeTraining(HttpServletRequest req, HttpServletResponse res, JSONObject input, UUID selfId) throws Exception {
        String campaignId = (String) input.get("campaign_id");
        if (isBlank(campaignId)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "campaign_id required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "UPDATE campaign_enrollments SET status = 'COMPLETED', completed_at = now() WHERE campaign_id = ?::uuid AND user_id = ?"
            );
            p.setString(1, campaignId); p.setObject(2, selfId);
            int updated = p.executeUpdate();
            if (updated == 0) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "No enrollment found for this campaign", req.getRequestURI()); return;
            }
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
