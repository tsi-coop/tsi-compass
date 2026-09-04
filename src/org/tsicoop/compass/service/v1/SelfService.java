package org.tsicoop.compass.service.v1;

import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.OutputProcessor;
import org.tsicoop.compass.framework.PoolDB;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
                case "list_ticket_categories": OutputProcessor.send(res, 200, listTicketCategories());            break;
                case "list_ticket_subcategories": OutputProcessor.send(res, 200, listTicketSubcategories(input)); break;
                case "upload_ticket_attachment": uploadTicketAttachment(req, res, input, selfId);     break;
                case "list_ticket_attachments":  listTicketAttachments(req, res, input, selfId);      break;
                case "get_ticket_attachment":    getTicketAttachment(req, res, input, selfId);        break;
                case "create_change_request": createChangeRequest(req, res, input, selfId);         break;
                case "list_my_changes":       OutputProcessor.send(res, 200, listMyChanges(selfId, input));       break;
                case "upload_change_attachment": uploadChangeAttachment(req, res, input, selfId);     break;
                case "list_change_attachments":  listChangeAttachments(req, res, input, selfId);      break;
                case "get_change_attachment":    getChangeAttachment(req, res, input, selfId);        break;
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

    // Whether business_settings.require_supervisor_approval is 'true'. Defaults to
    // false (existing behavior) if the row is missing.
    private boolean requireSupervisorApproval(Connection conn) throws Exception {
        PreparedStatement p = null; ResultSet rs = null;
        try {
            p = conn.prepareStatement("SELECT setting_value FROM business_settings WHERE setting_key = 'require_supervisor_approval'");
            rs = p.executeQuery();
            return rs.next() && "true".equalsIgnoreCase(rs.getString(1));
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignored) {}
            try { if (p != null) p.close(); } catch (Exception ignored) {}
        }
    }

    private UUID getSupervisorId(Connection conn, UUID userId) throws Exception {
        PreparedStatement p = null; ResultSet rs = null;
        try {
            p = conn.prepareStatement("SELECT supervisor_id FROM users WHERE id = ?");
            p.setObject(1, userId);
            rs = p.executeQuery();
            if (!rs.next()) return null;
            Object sup = rs.getObject("supervisor_id");
            return sup == null ? null : UUID.fromString(sup.toString());
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignored) {}
            try { if (p != null) p.close(); } catch (Exception ignored) {}
        }
    }

    // Every manager above userId in the reporting chain — direct supervisor
    // first, then that supervisor's supervisor, and so on up to whatever
    // Manager-N sits at the top (however many levels are actually configured).
    // Used so a "needs your approval" notification reaches every manager who
    // is allowed to act on it (see Supervisor.java's downlineUserIds, which
    // grants approval rights the same way, just walked from the other end).
    private List<UUID> managerChainIds(Connection conn, UUID userId) throws Exception {
        PreparedStatement p = null; ResultSet rs = null;
        try {
            p = conn.prepareStatement(
                "WITH RECURSIVE chain AS (" +
                "  SELECT supervisor_id AS id, 0 AS depth FROM users WHERE id = ? " +
                "  UNION ALL " +
                "  SELECT u.supervisor_id, c.depth + 1 FROM users u JOIN chain c ON u.id = c.id " +
                "  WHERE c.id IS NOT NULL AND c.depth < 6" +
                ") " +
                "SELECT id::text FROM chain WHERE id IS NOT NULL"
            );
            p.setObject(1, userId);
            rs = p.executeQuery();
            List<UUID> ids = new ArrayList<>();
            while (rs.next()) ids.add(UUID.fromString(rs.getString(1)));
            return ids;
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignored) {}
            try { if (p != null) p.close(); } catch (Exception ignored) {}
        }
    }

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
        String title         = (String) input.get("title");
        String desc          = (String) input.get("description");
        String priority      = (String) input.get("priority");
        String categoryId    = (String) input.get("category_id");
        String subcategoryId = (String) input.get("subcategory_id");
        if (isBlank(title) || isBlank(desc) || isBlank(categoryId)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "title, description and category_id required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            // A subcategory must belong to the chosen category and still be
            // active — the request body can't be trusted to keep them paired.
            if (!isBlank(subcategoryId) && !subcategoryBelongsToCategory(conn, subcategoryId, categoryId)) {
                OutputProcessor.errorResponse(res, 400, "Bad Request",
                    "subcategory_id does not belong to an active subcategory of category_id", req.getRequestURI());
                return;
            }

            boolean needsApproval = requireSupervisorApproval(conn);
            UUID supervisorId = null;
            if (needsApproval) {
                supervisorId = getSupervisorId(conn, selfId);
                if (supervisorId == null) {
                    // Employees must have a supervisor to route approval to. A
                    // manager submitting their own ticket may legitimately have no
                    // upline (e.g. top of the chain) - don't block them, just skip
                    // the approval gate for this submission.
                    if ("USER".equals(InputProcessor.getRole(req))) {
                        OutputProcessor.errorResponse(res, 400, "Bad Request",
                            "Your account has no assigned supervisor. Contact your administrator before submitting a ticket.",
                            req.getRequestURI());
                        return;
                    }
                    needsApproval = false;
                }
            }

            p = conn.prepareStatement(
                "INSERT INTO helpdesk_tickets (title,description,priority,category_id,subcategory_id,created_by,approval_status) " +
                "VALUES (?,?,?,?::uuid,?::uuid,?,?) RETURNING id::text"
            );
            p.setString(1, title); p.setString(2, desc); p.setString(3, isBlank(priority) ? "MEDIUM" : priority);
            p.setString(4, categoryId);
            p.setString(5, isBlank(subcategoryId) ? null : subcategoryId);
            p.setObject(6, selfId);
            p.setString(7, needsApproval ? "PENDING" : "NOT_REQUIRED");
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            String newId = null;
            if (rs.next()) { newId = rs.getString(1); result.put("id", newId); }
            OutputProcessor.send(res, 200, result);
            if (newId != null) {
                if (needsApproval) {
                    // Every manager in the chain can approve (see Supervisor.java),
                    // so every manager in the chain is notified — not just the
                    // direct supervisor.
                    for (UUID managerId : managerChainIds(conn, selfId)) {
                        Notification.emitToUser("TICKET_PENDING_APPROVAL", "Ticket awaiting your approval",
                            "\"" + title + "\" was submitted by a team member and needs your approval",
                            "approvals.html", UUID.fromString(newId), managerId);
                    }
                } else {
                    Notification.emit("TICKET_CREATED", "helpdesk", "New helpdesk ticket",
                        "\"" + title + "\" was submitted", "operations-helpdesk.html", UUID.fromString(newId));
                }
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private JSONObject listMyTickets(UUID selfId, JSONObject input) throws Exception {
        String search = (String) input.get("search");
        long[] pg = parsePaging(input); long page = pg[0], limit = pg[1]; long offset = (page - 1) * limit;

        StringBuilder where = new StringBuilder(" WHERE ht.created_by = ?");
        if (!isBlank(search)) where.append(" AND (ht.title ILIKE ? OR ht.description ILIKE ?)");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray(); long total = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            p = conn.prepareStatement("SELECT COUNT(*) FROM helpdesk_tickets ht" + where);
            int idx = 1; p.setObject(idx++, selfId);
            if (!isBlank(search)) { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); }
            rs = p.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
            rs = null; p = null;

            p = conn.prepareStatement(
                "SELECT ht.id::text, ht.title, ht.description, ht.status, ht.priority, " +
                "TO_CHAR(ht.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS created_at, tc.name AS category_name, " +
                "tsc.name AS subcategory_name, " +
                "ht.approval_status, ht.rejection_reason, ht.resolution_notes " +
                "FROM helpdesk_tickets ht LEFT JOIN ticket_categories tc ON tc.id = ht.category_id " +
                "LEFT JOIN ticket_subcategories tsc ON tsc.id = ht.subcategory_id" +
                where +
                " ORDER BY ht.created_at DESC LIMIT ? OFFSET ?"
            );
            idx = 1; p.setObject(idx++, selfId);
            if (!isBlank(search)) { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); }
            p.setLong(idx++, limit); p.setLong(idx++, offset);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject t = new JSONObject();
                t.put("id",            rs.getString("id"));
                t.put("title",         rs.getString("title"));
                t.put("description",   rs.getString("description"));
                t.put("status",        rs.getString("status"));
                t.put("priority",      rs.getString("priority"));
                t.put("created_at",    rs.getString("created_at"));
                t.put("category_name", rs.getString("category_name"));
                t.put("subcategory_name", rs.getString("subcategory_name"));
                t.put("approval_status", rs.getString("approval_status"));
                t.put("rejection_reason", rs.getString("rejection_reason"));
                t.put("resolution_notes", rs.getString("resolution_notes"));
                list.add(t);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("tickets", list);
        result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listTicketCategories() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray categories = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT id::text, name FROM ticket_categories WHERE is_active = TRUE ORDER BY name");
            rs = p.executeQuery();
            while (rs.next()) { JSONObject c=new JSONObject(); c.put("id",rs.getString(1)); c.put("name",rs.getString(2)); categories.add(c); }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("categories", categories); return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listTicketSubcategories(JSONObject input) throws Exception {
        String categoryId = (String) input.get("category_id");
        if (isBlank(categoryId)) {
            JSONObject result = new JSONObject(); result.put("success", true); result.put("subcategories", new JSONArray()); return result;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray subcategories = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT id::text, name FROM ticket_subcategories WHERE category_id = ?::uuid AND is_active = TRUE ORDER BY name"
            );
            p.setString(1, categoryId);
            rs = p.executeQuery();
            while (rs.next()) { JSONObject c=new JSONObject(); c.put("id",rs.getString(1)); c.put("name",rs.getString(2)); subcategories.add(c); }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("subcategories", subcategories); return result;
    }

    // Returns true only when subcategoryId names an active subcategory row
    // whose category_id equals categoryId — used to validate create_ticket
    // input server-side rather than trusting the paired ids from the client.
    private boolean subcategoryBelongsToCategory(Connection conn, String subcategoryId, String categoryId) throws Exception {
        PreparedStatement p = null; ResultSet rs = null;
        try {
            p = conn.prepareStatement(
                "SELECT 1 FROM ticket_subcategories WHERE id = ?::uuid AND category_id = ?::uuid AND is_active = TRUE"
            );
            p.setString(1, subcategoryId); p.setString(2, categoryId);
            rs = p.executeQuery();
            return rs.next();
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignored) {}
            try { if (p != null) p.close(); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Ticket attachments
    // -------------------------------------------------------------------------

    private static final Set<String> ATTACHMENT_EXTENSIONS = new HashSet<>(Arrays.asList(
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".png", ".jpg", ".jpeg", ".zip", ".txt", ".csv"
    ));

    // Decoded file size cap. This is the authoritative check — the client-side
    // check in new-ticket.html/index.html is just a UX shortcut and can't be
    // trusted on its own.
    private static final int MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024; // 10 MB

    private boolean ownsTicket(Connection conn, String ticketId, UUID selfId) throws Exception {
        PreparedStatement p = null; ResultSet rs = null;
        try {
            p = conn.prepareStatement("SELECT 1 FROM helpdesk_tickets WHERE id = ?::uuid AND created_by = ?");
            p.setString(1, ticketId); p.setObject(2, selfId);
            rs = p.executeQuery();
            return rs.next();
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignored) {}
            try { if (p != null) p.close(); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private void uploadTicketAttachment(HttpServletRequest req, HttpServletResponse res, JSONObject input, UUID selfId) throws Exception {
        String ticketId = (String) input.get("ticket_id");
        String fileName = (String) input.get("file_name");
        String fileData = (String) input.get("file_data");
        if (isBlank(ticketId) || isBlank(fileName) || isBlank(fileData)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "ticket_id, file_name and file_data are required", req.getRequestURI()); return;
        }
        String safeName = new File(fileName).getName();
        int dot = safeName.lastIndexOf('.');
        String ext = dot >= 0 ? safeName.substring(dot).toLowerCase() : "";
        if (!ATTACHMENT_EXTENSIONS.contains(ext)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "File type not allowed: " + ext, req.getRequestURI()); return;
        }
        String raw = fileData.contains(",") ? fileData.substring(fileData.indexOf(',') + 1) : fileData;
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(raw.trim()); }
        catch (Exception e) { OutputProcessor.errorResponse(res, 400, "Bad Request", "Invalid base64 content", req.getRequestURI()); return; }
        if (bytes.length > MAX_ATTACHMENT_BYTES) {
            OutputProcessor.errorResponse(res, 400, "Bad Request",
                "File exceeds the " + (MAX_ATTACHMENT_BYTES / (1024 * 1024)) + "MB attachment size limit", req.getRequestURI());
            return;
        }

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            if (!ownsTicket(conn, ticketId, selfId)) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Ticket not found", req.getRequestURI()); return;
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { String h = Integer.toHexString(0xff & b); if (h.length() == 1) sb.append('0'); sb.append(h); }
            String checksum = sb.toString();

            String uploadDir = System.getenv("TSI_EXPORT_PATH");
            if (isBlank(uploadDir)) uploadDir = System.getProperty("user.home") + "/.tsi-compass/exports";
            File dir = new File(uploadDir + "/ticket_attachments");
            if (!dir.exists() && !dir.mkdirs()) {
                OutputProcessor.errorResponse(res, 500, "Internal Error", "Cannot create upload directory", req.getRequestURI()); return;
            }
            File dest = new File(dir, UUID.randomUUID().toString() + ext);
            try (FileOutputStream fos = new FileOutputStream(dest)) { fos.write(bytes); }

            p = conn.prepareStatement(
                "INSERT INTO ticket_attachments (ticket_id, file_name, file_path, sha256_checksum, uploaded_by) " +
                "VALUES (?::uuid, ?, ?, ?, ?) RETURNING id::text"
            );
            p.setString(1, ticketId); p.setString(2, safeName);
            p.setString(3, dest.getAbsolutePath()); p.setString(4, checksum); p.setObject(5, selfId);
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void listTicketAttachments(HttpServletRequest req, HttpServletResponse res, JSONObject input, UUID selfId) throws Exception {
        String ticketId = (String) input.get("ticket_id");
        if (isBlank(ticketId)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "ticket_id is required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            if (!ownsTicket(conn, ticketId, selfId)) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Ticket not found", req.getRequestURI()); return;
            }
            p = conn.prepareStatement(
                "SELECT id::text, file_name, sha256_checksum, uploaded_at::text FROM ticket_attachments " +
                "WHERE ticket_id = ?::uuid ORDER BY uploaded_at DESC"
            );
            p.setString(1, ticketId);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject a = new JSONObject();
                a.put("id",              rs.getString("id"));
                a.put("file_name",       rs.getString("file_name"));
                a.put("sha256_checksum", rs.getString("sha256_checksum"));
                a.put("uploaded_at",     rs.getString("uploaded_at"));
                list.add(a);
            }
            JSONObject result = new JSONObject(); result.put("success", true); result.put("attachments", list);
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void getTicketAttachment(HttpServletRequest req, HttpServletResponse res, JSONObject input, UUID selfId) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT ta.file_name, ta.file_path FROM ticket_attachments ta " +
                "JOIN helpdesk_tickets ht ON ht.id = ta.ticket_id " +
                "WHERE ta.id = ?::uuid AND ht.created_by = ?"
            );
            p.setString(1, id); p.setObject(2, selfId);
            rs = p.executeQuery();
            if (!rs.next()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Attachment not found", req.getRequestURI()); return;
            }
            String fileName = rs.getString("file_name");
            String filePath = rs.getString("file_path");
            File file = new File(filePath);
            if (!file.exists() || !file.isFile()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "File not found on server", req.getRequestURI()); return;
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            JSONObject result = new JSONObject(); result.put("success", true);
            result.put("file_name", fileName);
            result.put("file_data", Base64.getEncoder().encodeToString(bytes));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    // -------------------------------------------------------------------------
    // Change request attachments — identical mechanics to ticket attachments
    // above, scoped to change_requests instead of helpdesk_tickets.
    // -------------------------------------------------------------------------

    private boolean ownsChangeRequest(Connection conn, String changeRequestId, UUID selfId) throws Exception {
        PreparedStatement p = null; ResultSet rs = null;
        try {
            p = conn.prepareStatement("SELECT 1 FROM change_requests WHERE id = ?::uuid AND requester_id = ?");
            p.setString(1, changeRequestId); p.setObject(2, selfId);
            rs = p.executeQuery();
            return rs.next();
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignored) {}
            try { if (p != null) p.close(); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private void uploadChangeAttachment(HttpServletRequest req, HttpServletResponse res, JSONObject input, UUID selfId) throws Exception {
        String changeRequestId = (String) input.get("change_request_id");
        String fileName = (String) input.get("file_name");
        String fileData = (String) input.get("file_data");
        if (isBlank(changeRequestId) || isBlank(fileName) || isBlank(fileData)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "change_request_id, file_name and file_data are required", req.getRequestURI()); return;
        }
        String safeName = new File(fileName).getName();
        int dot = safeName.lastIndexOf('.');
        String ext = dot >= 0 ? safeName.substring(dot).toLowerCase() : "";
        if (!ATTACHMENT_EXTENSIONS.contains(ext)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "File type not allowed: " + ext, req.getRequestURI()); return;
        }
        String raw = fileData.contains(",") ? fileData.substring(fileData.indexOf(',') + 1) : fileData;
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(raw.trim()); }
        catch (Exception e) { OutputProcessor.errorResponse(res, 400, "Bad Request", "Invalid base64 content", req.getRequestURI()); return; }
        if (bytes.length > MAX_ATTACHMENT_BYTES) {
            OutputProcessor.errorResponse(res, 400, "Bad Request",
                "File exceeds the " + (MAX_ATTACHMENT_BYTES / (1024 * 1024)) + "MB attachment size limit", req.getRequestURI());
            return;
        }

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            if (!ownsChangeRequest(conn, changeRequestId, selfId)) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Change request not found", req.getRequestURI()); return;
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { String h = Integer.toHexString(0xff & b); if (h.length() == 1) sb.append('0'); sb.append(h); }
            String checksum = sb.toString();

            String uploadDir = System.getenv("TSI_EXPORT_PATH");
            if (isBlank(uploadDir)) uploadDir = System.getProperty("user.home") + "/.tsi-compass/exports";
            File dir = new File(uploadDir + "/change_request_attachments");
            if (!dir.exists() && !dir.mkdirs()) {
                OutputProcessor.errorResponse(res, 500, "Internal Error", "Cannot create upload directory", req.getRequestURI()); return;
            }
            File dest = new File(dir, UUID.randomUUID().toString() + ext);
            try (FileOutputStream fos = new FileOutputStream(dest)) { fos.write(bytes); }

            p = conn.prepareStatement(
                "INSERT INTO change_request_attachments (change_request_id, file_name, file_path, sha256_checksum, uploaded_by) " +
                "VALUES (?::uuid, ?, ?, ?, ?) RETURNING id::text"
            );
            p.setString(1, changeRequestId); p.setString(2, safeName);
            p.setString(3, dest.getAbsolutePath()); p.setString(4, checksum); p.setObject(5, selfId);
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void listChangeAttachments(HttpServletRequest req, HttpServletResponse res, JSONObject input, UUID selfId) throws Exception {
        String changeRequestId = (String) input.get("change_request_id");
        if (isBlank(changeRequestId)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "change_request_id is required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            if (!ownsChangeRequest(conn, changeRequestId, selfId)) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Change request not found", req.getRequestURI()); return;
            }
            p = conn.prepareStatement(
                "SELECT id::text, file_name, sha256_checksum, uploaded_at::text FROM change_request_attachments " +
                "WHERE change_request_id = ?::uuid ORDER BY uploaded_at DESC"
            );
            p.setString(1, changeRequestId);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject a = new JSONObject();
                a.put("id",              rs.getString("id"));
                a.put("file_name",       rs.getString("file_name"));
                a.put("sha256_checksum", rs.getString("sha256_checksum"));
                a.put("uploaded_at",     rs.getString("uploaded_at"));
                list.add(a);
            }
            JSONObject result = new JSONObject(); result.put("success", true); result.put("attachments", list);
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void getChangeAttachment(HttpServletRequest req, HttpServletResponse res, JSONObject input, UUID selfId) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT ca.file_name, ca.file_path FROM change_request_attachments ca " +
                "JOIN change_requests cr ON cr.id = ca.change_request_id " +
                "WHERE ca.id = ?::uuid AND cr.requester_id = ?"
            );
            p.setString(1, id); p.setObject(2, selfId);
            rs = p.executeQuery();
            if (!rs.next()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Attachment not found", req.getRequestURI()); return;
            }
            String fileName = rs.getString("file_name");
            String filePath = rs.getString("file_path");
            File file = new File(filePath);
            if (!file.exists() || !file.isFile()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "File not found on server", req.getRequestURI()); return;
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            JSONObject result = new JSONObject(); result.put("success", true);
            result.put("file_name", fileName);
            result.put("file_data", Base64.getEncoder().encodeToString(bytes));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
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

            boolean needsApproval = requireSupervisorApproval(conn);
            UUID supervisorId = null;
            if (needsApproval) {
                supervisorId = getSupervisorId(conn, selfId);
                if (supervisorId == null) {
                    // Employees must have a supervisor to route approval to. A
                    // manager submitting their own change request may legitimately
                    // have no upline (e.g. top of the chain) - don't block them,
                    // just skip the approval gate for this submission.
                    if ("USER".equals(InputProcessor.getRole(req))) {
                        OutputProcessor.errorResponse(res, 400, "Bad Request",
                            "Your account has no assigned supervisor. Contact your administrator before submitting a change request.",
                            req.getRequestURI());
                        return;
                    }
                    needsApproval = false;
                }
            }

            p = conn.prepareStatement(
                "INSERT INTO change_requests (title,description,requester_id,status,approval_status) " +
                "VALUES (?,?,?,'SUBMITTED',?) RETURNING id::text"
            );
            p.setString(1, title); p.setString(2, desc); p.setObject(3, selfId);
            p.setString(4, needsApproval ? "PENDING" : "NOT_REQUIRED");
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            String newId = null;
            if (rs.next()) { newId = rs.getString(1); result.put("id", newId); }
            OutputProcessor.send(res, 200, result);
            if (newId != null) {
                if (needsApproval) {
                    // Every manager in the chain can approve (see Supervisor.java),
                    // so every manager in the chain is notified — not just the
                    // direct supervisor.
                    for (UUID managerId : managerChainIds(conn, selfId)) {
                        Notification.emitToUser("CHANGE_REQUEST_PENDING_APPROVAL", "Change request awaiting your approval",
                            "\"" + title + "\" was submitted by a team member and needs your approval",
                            "approvals.html", UUID.fromString(newId), managerId);
                    }
                } else {
                    Notification.emit("CHANGE_REQUEST_CREATED", "operations", "New change request",
                        "\"" + title + "\" was submitted", "operations-changes.html", UUID.fromString(newId));
                }
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
                "SELECT id::text, title, description, stage, status, " +
                "TO_CHAR(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS created_at, approval_status, rejection_reason " +
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
                c.put("approval_status", rs.getString("approval_status"));
                c.put("rejection_reason", rs.getString("rejection_reason"));
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
