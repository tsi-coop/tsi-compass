package org.tsicoop.compass.service.v1;

import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.OutputProcessor;
import org.tsicoop.compass.framework.JWTUtil;
import org.tsicoop.compass.framework.PoolDB;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.sql.*;
import java.util.Base64;
import java.util.UUID;

public class Governance implements Action {

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
                case "get_governance_metrics":
                    OutputProcessor.send(res, 200, getGovernanceMetrics());
                    break;
                case "list_committees":
                    OutputProcessor.send(res, 200, listCommittees());
                    break;
                case "list_meetings":
                    OutputProcessor.send(res, 200, listMeetings(input));
                    break;
                case "schedule_meeting":
                    scheduleMeeting(req, res, input);
                    break;
                case "update_meeting":
                    updateMeeting(req, res, input);
                    break;
                case "log_minutes":
                    logMinutes(req, res, input);
                    break;
                case "list_action_items":
                    OutputProcessor.send(res, 200, listActionItems(input));
                    break;
                case "add_action_item":
                    addActionItem(req, res, input);
                    break;
                case "bulk_add_action_items":
                    bulkAddActionItems(req, res, input);
                    break;
                case "update_action_item_status":
                    updateActionItemStatus(req, res, input);
                    break;
                case "list_policies":
                    OutputProcessor.send(res, 200, listPolicies(input));
                    break;
                case "create_policy":
                    createPolicy(req, res, input);
                    break;
                case "update_policy":
                    updatePolicy(req, res, input);
                    break;
                case "set_policy_status":
                    setPolicyStatus(req, res, input);
                    break;
                case "list_staff":
                    OutputProcessor.send(res, 200, listStaff());
                    break;
                case "upload_policy_document":
                    uploadPolicyDocument(req, res, input);
                    break;
                case "get_policy_document":
                    getPolicyDocument(req, res, input);
                    break;
                case "delete_meeting":
                    deleteMeeting(req, res, input);
                    break;
                case "delete_action_item":
                    deleteActionItem(req, res, input);
                    break;
                case "delete_policy":
                    deletePolicy(req, res, input);
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
    // Hub Metrics
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject getGovernanceMetrics() throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            pstmt = conn.prepareStatement("SELECT COUNT(*) FROM committees");
            rs = pstmt.executeQuery();
            result.put("committee_count", rs.next() ? rs.getLong(1) : 0L);
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            pstmt = conn.prepareStatement(
                "SELECT COUNT(*), " +
                "SUM(CASE WHEN status = 'CONDUCTED' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status IN ('SCHEDULED','TENTATIVE') THEN 1 ELSE 0 END) " +
                "FROM committee_meetings " +
                "WHERE DATE_TRUNC('month', scheduled_at) = DATE_TRUNC('month', CURRENT_DATE)"
            );
            rs = pstmt.executeQuery();
            if (rs.next()) {
                result.put("meetings_this_month", rs.getLong(1));
                result.put("meetings_conducted",  rs.getLong(2));
                result.put("meetings_scheduled",  rs.getLong(3));
            }
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            pstmt = conn.prepareStatement(
                "SELECT COUNT(*), " +
                "SUM(CASE WHEN status = 'UNDER_REVIEW' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'DRAFT' THEN 1 ELSE 0 END) " +
                "FROM policies WHERE status != 'ARCHIVED'"
            );
            rs = pstmt.executeQuery();
            if (rs.next()) {
                result.put("active_policies",       rs.getLong(1));
                result.put("policies_under_review", rs.getLong(2));
                result.put("policies_draft",        rs.getLong(3));
            }
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            pstmt = conn.prepareStatement(
                "SELECT COUNT(*) FROM committee_action_items WHERE status IN ('PENDING','IN_PROGRESS','OVERDUE')"
            );
            rs = pstmt.executeQuery();
            result.put("open_action_items", rs.next() ? rs.getLong(1) : 0L);

        } finally {
            if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
        }
        result.put("success", true);
        return result;
    }

    // -------------------------------------------------------------------------
    // Committees
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject listCommittees() throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONArray committees = new JSONArray();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "SELECT id::text, name, description FROM committees ORDER BY name"
            );
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject c = new JSONObject();
                c.put("id",          rs.getString(1));
                c.put("name",        rs.getString(2));
                c.put("description", rs.getString(3));
                committees.add(c);
            }
        } finally {
            if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("committees", committees);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listMeetings(JSONObject input) throws Exception {
        String status = (String) input.get("status");
        String search = (String) input.get("search");
        long[] pg = parsePaging(input); long page = pg[0], limit = pg[1]; long offset = (page - 1) * limit;

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (!isBlank(status)) where.append(" AND cm.status = ?");
        if (!isBlank(search)) where.append(" AND (cm.agenda ILIKE ? OR cm.venue ILIKE ? OR c.name ILIKE ?)");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONArray meetings = new JSONArray();
        long total = 0;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            String countSql = "SELECT COUNT(*) FROM committee_meetings cm JOIN committees c ON c.id = cm.committee_id" + where;
            pstmt = conn.prepareStatement(countSql);
            int idx = 1;
            if (!isBlank(status)) pstmt.setString(idx++, status);
            if (!isBlank(search)) { String like = "%" + search + "%"; pstmt.setString(idx++, like); pstmt.setString(idx++, like); pstmt.setString(idx++, like); }
            rs = pstmt.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            String dataSql =
                "SELECT cm.id::text, c.name AS committee_name, c.id::text AS committee_id, " +
                "cm.scheduled_at::text, cm.agenda, cm.venue, cm.status, " +
                "(SELECT COUNT(*) FROM committee_mom mom WHERE mom.meeting_id = cm.id) AS mom_count " +
                "FROM committee_meetings cm " +
                "JOIN committees c ON c.id = cm.committee_id" + where +
                " ORDER BY cm.scheduled_at DESC LIMIT ? OFFSET ?";
            pstmt = conn.prepareStatement(dataSql); idx = 1;
            if (!isBlank(status)) pstmt.setString(idx++, status);
            if (!isBlank(search)) { String like = "%" + search + "%"; pstmt.setString(idx++, like); pstmt.setString(idx++, like); pstmt.setString(idx++, like); }
            pstmt.setLong(idx++, limit); pstmt.setLong(idx++, offset);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject m = new JSONObject();
                m.put("id",             rs.getString(1));
                m.put("committee_name", rs.getString(2));
                m.put("committee_id",   rs.getString(3));
                m.put("scheduled_at",   rs.getString(4));
                m.put("agenda",         rs.getString(5));
                m.put("venue",          rs.getString(6));
                m.put("status",         rs.getString(7));
                m.put("has_minutes",    rs.getLong(8) > 0);
                meetings.add(m);
            }
        } finally {
            if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("meetings", meetings);
        result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void scheduleMeeting(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String committeeId = (String) input.get("committee_id");
        String scheduledAt = (String) input.get("scheduled_at");
        String venue       = (String) input.get("venue");
        String agenda      = (String) input.get("agenda");
        String status      = (String) input.get("status");

        if (isBlank(committeeId) || isBlank(scheduledAt)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "committee_id and scheduled_at are required", req.getRequestURI());
            return;
        }

        String st = isBlank(status) ? "SCHEDULED" : status;

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "INSERT INTO committee_meetings (committee_id, scheduled_at, venue, agenda, status) " +
                "VALUES (?, ?::timestamptz, ?, ?, ?) RETURNING id::text"
            );
            pstmt.setObject(1, UUID.fromString(committeeId));
            pstmt.setString(2, scheduledAt);
            pstmt.setString(3, venue);
            pstmt.setString(4, agenda);
            pstmt.setString(5, st);
            rs = pstmt.executeQuery();
            String newId = rs.next() ? rs.getString(1) : null;
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("id", newId);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private void updateMeeting(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id          = (String) input.get("id");
        String committeeId = (String) input.get("committee_id");
        String scheduledAt = (String) input.get("scheduled_at");
        String venue       = (String) input.get("venue");
        String agenda      = (String) input.get("agenda");
        String status      = (String) input.get("status");

        if (isBlank(id)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI());
            return;
        }

        StringBuilder sql = new StringBuilder("UPDATE committee_meetings SET");
        boolean first = true;

        if (!isBlank(committeeId)) { sql.append(first ? " " : ", ").append("committee_id = ?::uuid"); first = false; }
        if (!isBlank(scheduledAt)) { sql.append(first ? " " : ", ").append("scheduled_at = ?::timestamptz"); first = false; }
        if (input.containsKey("venue"))  { sql.append(first ? " " : ", ").append("venue = ?"); first = false; }
        if (!isBlank(agenda))    { sql.append(first ? " " : ", ").append("agenda = ?"); first = false; }
        if (!isBlank(status))    { sql.append(first ? " " : ", ").append("status = ?"); first = false; }

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
            if (!isBlank(committeeId)) pstmt.setString(idx++, committeeId);
            if (!isBlank(scheduledAt)) pstmt.setString(idx++, scheduledAt);
            if (input.containsKey("venue"))  pstmt.setString(idx++, venue);
            if (!isBlank(agenda))    pstmt.setString(idx++, agenda);
            if (!isBlank(status))    pstmt.setString(idx++, status);
            pstmt.setObject(idx, UUID.fromString(id));
            pstmt.executeUpdate();

            JSONObject result = new JSONObject();
            result.put("success", true);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private void logMinutes(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String meetingId = (String) input.get("meeting_id");
        String attendees = (String) input.get("attendees");
        String momText   = (String) input.get("mom_text");

        if (isBlank(meetingId) || isBlank(momText)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "meeting_id and mom_text are required", req.getRequestURI());
            return;
        }

        String fullText = isBlank(attendees) ? momText : "Attendees: " + attendees + "\n\n" + momText;

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            pstmt = conn.prepareStatement(
                "INSERT INTO committee_mom (meeting_id, mom_text, published_at) " +
                "VALUES (?, ?, NOW()) RETURNING id::text"
            );
            pstmt.setObject(1, UUID.fromString(meetingId));
            pstmt.setString(2, fullText);
            rs = pstmt.executeQuery();
            String momId = rs.next() ? rs.getString(1) : null;
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            pstmt = conn.prepareStatement(
                "UPDATE committee_meetings SET status = 'CONDUCTED' WHERE id = ?"
            );
            pstmt.setObject(1, UUID.fromString(meetingId));
            pstmt.executeUpdate();
            conn.commit();

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("mom_id", momId);
            OutputProcessor.send(res, 200, result);
        } catch (Exception e) {
            try { if (conn != null) conn.rollback(); } catch (Exception ignored) {}
            throw e;
        } finally {
            if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private JSONObject listActionItems(JSONObject input) throws Exception {
        String search = (String) input.get("search");
        long[] pg = parsePaging(input); long page = pg[0], limit = pg[1]; long offset = (page - 1) * limit;

        StringBuilder where = new StringBuilder(" WHERE cai.status != 'COMPLETED'");
        if (!isBlank(search)) where.append(" AND (cai.deliverable ILIKE ? OR c.name ILIKE ?)");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONArray items = new JSONArray();
        long total = 0;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            String countSql =
                "SELECT COUNT(*) " +
                "FROM committee_action_items cai " +
                "LEFT JOIN committee_meetings cm ON cm.id = cai.meeting_id " +
                "LEFT JOIN committee_mom mom ON mom.id = cai.mom_id " +
                "LEFT JOIN committee_meetings mcm ON mcm.id = mom.meeting_id " +
                "LEFT JOIN committees c ON c.id = COALESCE(cm.committee_id, mcm.committee_id)" + where;
            pstmt = conn.prepareStatement(countSql);
            int idx = 1;
            if (!isBlank(search)) { String like = "%" + search + "%"; pstmt.setString(idx++, like); pstmt.setString(idx++, like); }
            rs = pstmt.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            // Support both paths: direct meeting_id (UI-created) and mom_id (minutes-created)
            String dataSql =
                "SELECT cai.id::text, cai.deliverable, u.username AS assignee_name, " +
                "cai.due_date::text, cai.status, " +
                "c.name AS committee_name, " +
                "COALESCE(cm.scheduled_at, mcm.scheduled_at)::text AS meeting_date " +
                "FROM committee_action_items cai " +
                "LEFT JOIN users u ON u.id = cai.assignee_id " +
                "LEFT JOIN committee_meetings cm ON cm.id = cai.meeting_id " +
                "LEFT JOIN committee_mom mom ON mom.id = cai.mom_id " +
                "LEFT JOIN committee_meetings mcm ON mcm.id = mom.meeting_id " +
                "LEFT JOIN committees c ON c.id = COALESCE(cm.committee_id, mcm.committee_id)" + where +
                " ORDER BY cai.due_date ASC LIMIT ? OFFSET ?";
            pstmt = conn.prepareStatement(dataSql); idx = 1;
            if (!isBlank(search)) { String like = "%" + search + "%"; pstmt.setString(idx++, like); pstmt.setString(idx++, like); }
            pstmt.setLong(idx++, limit); pstmt.setLong(idx++, offset);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject item = new JSONObject();
                item.put("id",             rs.getString(1));
                item.put("deliverable",    rs.getString(2));
                item.put("assignee_name",  rs.getString(3));
                item.put("due_date",       rs.getString(4));
                item.put("status",         rs.getString(5));
                item.put("committee_name", rs.getString(6));
                item.put("meeting_date",   rs.getString(7));
                items.add(item);
            }
        } finally {
            if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("action_items", items);
        result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void addActionItem(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String deliverable = (String) input.get("deliverable");
        String assigneeId  = (String) input.get("assignee_id");
        String dueDate     = (String) input.get("due_date");
        String meetingId   = (String) input.get("meeting_id");

        if (isBlank(deliverable) || isBlank(dueDate)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "deliverable and due_date are required", req.getRequestURI());
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
                "INSERT INTO committee_action_items (deliverable, assignee_id, due_date, meeting_id, status) " +
                "VALUES (?, ?, ?::date, ?, 'PENDING') RETURNING id::text"
            );
            pstmt.setString(1, deliverable);
            if (isBlank(assigneeId)) pstmt.setNull(2, java.sql.Types.OTHER);
            else pstmt.setObject(2, UUID.fromString(assigneeId));
            pstmt.setString(3, dueDate);
            if (isBlank(meetingId)) pstmt.setNull(4, java.sql.Types.OTHER);
            else pstmt.setObject(4, UUID.fromString(meetingId));
            rs = pstmt.executeQuery();
            String newId = rs.next() ? rs.getString(1) : null;
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("id", newId);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private void bulkAddActionItems(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        Object itemsObj = input.get("items");
        if (!(itemsObj instanceof JSONArray)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "items array is required", req.getRequestURI());
            return;
        }
        JSONArray items = (JSONArray) itemsObj;

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        long imported = 0;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            for (Object obj : items) {
                if (!(obj instanceof JSONObject)) continue;
                JSONObject item = (JSONObject) obj;
                String deliverable = (String) item.get("deliverable");
                String assigneeId  = (String) item.get("assignee_id");
                String dueDate     = (String) item.get("due_date");
                if (isBlank(deliverable) || isBlank(dueDate)) continue;
                pstmt = conn.prepareStatement(
                    "INSERT INTO committee_action_items (deliverable, assignee_id, due_date, status) " +
                    "VALUES (?, ?, ?::date, 'PENDING')"
                );
                pstmt.setString(1, deliverable.trim());
                if (isBlank(assigneeId)) pstmt.setNull(2, java.sql.Types.OTHER);
                else pstmt.setObject(2, UUID.fromString(assigneeId));
                pstmt.setString(3, dueDate);
                pstmt.executeUpdate();
                try { pstmt.close(); } catch (Exception ignored) {}
                pstmt = null;
                imported++;
            }
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("imported", imported);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private void updateActionItemStatus(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
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
            pstmt = conn.prepareStatement("UPDATE committee_action_items SET status = ? WHERE id = ?");
            pstmt.setString(1, status);
            pstmt.setObject(2, UUID.fromString(id));
            pstmt.executeUpdate();
            JSONObject result = new JSONObject();
            result.put("success", true);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Policies
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject listPolicies(JSONObject input) throws Exception {
        String type   = (String) input.get("type");
        String status = (String) input.get("status");
        String search = (String) input.get("search");
        long[] pg = parsePaging(input); long page = pg[0], limit = pg[1]; long offset = (page - 1) * limit;

        StringBuilder where = new StringBuilder(" WHERE p.status != 'ARCHIVED'");
        if (!isBlank(type))   where.append(" AND p.type = ?");
        if (!isBlank(status)) where.append(" AND p.status = ?");
        if (!isBlank(search)) where.append(" AND (p.title ILIKE ? OR COALESCE(p.framework_tags,'') ILIKE ?)");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONArray policies = new JSONArray();
        long total = 0;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            String countSql = "SELECT COUNT(*) FROM policies p" + where;
            pstmt = conn.prepareStatement(countSql);
            int idx = 1;
            if (!isBlank(type))   pstmt.setString(idx++, type);
            if (!isBlank(status)) pstmt.setString(idx++, status);
            if (!isBlank(search)) { String like = "%" + search + "%"; pstmt.setString(idx++, like); pstmt.setString(idx++, like); }
            rs = pstmt.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            String dataSql =
                "SELECT p.id::text, p.title, p.type, p.version, p.status, " +
                "p.framework_tags, u.username AS owner_name, p.author_id::text AS owner_id, " +
                "p.review_date::text, p.created_at::text, p.document_name, " +
                "ap.username AS approved_by_name " +
                "FROM policies p " +
                "LEFT JOIN users u  ON u.id  = p.author_id " +
                "LEFT JOIN users ap ON ap.id = p.approved_by" + where +
                " ORDER BY p.created_at DESC LIMIT ? OFFSET ?";
            pstmt = conn.prepareStatement(dataSql);
            idx = 1;
            if (!isBlank(type))   pstmt.setString(idx++, type);
            if (!isBlank(status)) pstmt.setString(idx++, status);
            if (!isBlank(search)) {
                String like = "%" + search + "%";
                pstmt.setString(idx++, like);
                pstmt.setString(idx++, like);
            }
            pstmt.setLong(idx++, limit); pstmt.setLong(idx++, offset);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject p = new JSONObject();
                p.put("id",               rs.getString(1));
                p.put("title",            rs.getString(2));
                p.put("type",             rs.getString(3));
                p.put("version",          rs.getString(4));
                p.put("status",           rs.getString(5));
                p.put("framework_tags",   rs.getString(6));
                p.put("owner_name",       rs.getString(7));
                p.put("owner_id",         rs.getString(8));
                p.put("review_date",      rs.getString(9));
                p.put("created_at",       rs.getString(10));
                p.put("document_name",    rs.getString(11));
                p.put("approved_by_name", rs.getString(12));
                policies.add(p);
            }
        } finally {
            if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("policies", policies);
        result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void createPolicy(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String title         = (String) input.get("title");
        String type          = (String) input.get("type");
        String version       = (String) input.get("version");
        String frameworkTags = (String) input.get("framework_tags");
        String authorId      = (String) input.get("author_id");
        String reviewDate    = (String) input.get("review_date");
        String description   = (String) input.get("description");

        if (isBlank(title) || isBlank(type)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "title and type are required", req.getRequestURI());
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
                "INSERT INTO policies (title, type, version, framework_tags, author_id, review_date, content, status) " +
                "VALUES (?, ?, ?, ?, ?, ?::date, ?, 'DRAFT') RETURNING id::text"
            );
            pstmt.setString(1, title.trim());
            pstmt.setString(2, type.toUpperCase());
            pstmt.setString(3, isBlank(version) ? "1.0" : version.trim());
            pstmt.setString(4, frameworkTags);
            if (isBlank(authorId)) pstmt.setNull(5, java.sql.Types.OTHER);
            else pstmt.setObject(5, UUID.fromString(authorId));
            if (isBlank(reviewDate)) pstmt.setNull(6, java.sql.Types.DATE);
            else pstmt.setString(6, reviewDate);
            pstmt.setString(7, isBlank(description) ? "" : description);
            rs = pstmt.executeQuery();
            String newId = rs.next() ? rs.getString(1) : null;
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("id", newId);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private void updatePolicy(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id            = (String) input.get("id");
        String title         = (String) input.get("title");
        String type          = (String) input.get("type");
        String version       = (String) input.get("version");
        String frameworkTags = (String) input.get("framework_tags");
        String authorId      = (String) input.get("author_id");
        String reviewDate    = (String) input.get("review_date");

        if (isBlank(id)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI());
            return;
        }

        StringBuilder sql = new StringBuilder("UPDATE policies SET");
        java.util.List<String[]> cols = new java.util.ArrayList<>();

        if (!isBlank(title))   { sql.append(cols.isEmpty() ? " " : ", ").append("title = ?");   cols.add(new String[]{"str", title.trim()}); }
        if (!isBlank(type))    { sql.append(cols.isEmpty() ? " " : ", ").append("type = ?");    cols.add(new String[]{"str", type.toUpperCase()}); }
        if (!isBlank(version)) { sql.append(cols.isEmpty() ? " " : ", ").append("version = ?"); cols.add(new String[]{"str", version.trim()}); }
        if (input.containsKey("framework_tags")) {
            sql.append(cols.isEmpty() ? " " : ", ").append("framework_tags = ?");
            cols.add(new String[]{"str", frameworkTags});
        }
        if (input.containsKey("author_id")) {
            sql.append(cols.isEmpty() ? " " : ", ").append("author_id = ?");
            cols.add(new String[]{"uuid", authorId});
        }
        if (input.containsKey("review_date")) {
            sql.append(cols.isEmpty() ? " " : ", ").append("review_date = ?::date");
            cols.add(new String[]{"str", reviewDate});
        }

        if (cols.isEmpty()) {
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
            for (String[] col : cols) {
                if ("uuid".equals(col[0])) {
                    if (isBlank(col[1])) pstmt.setNull(idx++, java.sql.Types.OTHER);
                    else pstmt.setObject(idx++, UUID.fromString(col[1]));
                } else {
                    pstmt.setString(idx++, col[1]);
                }
            }
            pstmt.setObject(idx, UUID.fromString(id));
            pstmt.executeUpdate();
            JSONObject result = new JSONObject();
            result.put("success", true);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private void setPolicyStatus(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id     = (String) input.get("id");
        String status = (String) input.get("status");

        if (isBlank(id) || isBlank(status)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id and status are required", req.getRequestURI());
            return;
        }

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        String policyTitle = null;
        String previousStatus = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            String[] before = getTitleAndStatus(conn, id);
            if (before != null) { policyTitle = before[0]; previousStatus = before[1]; }

            String approverId = null;
            if ("PUBLISHED".equals(status)) {
                String authHeader = req.getHeader("Authorization");
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    String email = JWTUtil.getEmailFromToken(authHeader.substring(7));
                    approverId = lookupUserIdByEmail(conn, email);
                }
            }

            if (approverId != null) {
                pstmt = conn.prepareStatement(
                    "UPDATE policies SET status = ?, approved_by = ?::uuid WHERE id = ?::uuid");
                pstmt.setString(1, status);
                pstmt.setString(2, approverId);
                pstmt.setObject(3, UUID.fromString(id));
            } else {
                pstmt = conn.prepareStatement(
                    "UPDATE policies SET status = ? WHERE id = ?::uuid");
                pstmt.setString(1, status);
                pstmt.setObject(2, UUID.fromString(id));
            }
            pstmt.executeUpdate();

            JSONObject result = new JSONObject();
            result.put("success", true);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
        }

        if ("PUBLISHED".equals(status) && !"PUBLISHED".equals(previousStatus) && policyTitle != null) {
            Notification.emitToRole("POLICY_PUBLISHED", "USER", "New policy published",
                "\"" + policyTitle + "\" was published and needs your acknowledgement",
                "policies.html", UUID.fromString(id));
        }
    }

    private String[] getTitleAndStatus(Connection conn, String id) {
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = conn.prepareStatement("SELECT title, status FROM policies WHERE id = ?::uuid");
            pstmt.setObject(1, UUID.fromString(id));
            rs = pstmt.executeQuery();
            return rs.next() ? new String[]{ rs.getString(1), rs.getString(2) } : null;
        } catch (Exception e) {
            return null;
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignored) {}
            try { if (pstmt != null) pstmt.close(); } catch (Exception ignored) {}
        }
    }

    private String lookupUserIdByEmail(Connection conn, String email) {
        if (isBlank(email)) return null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = conn.prepareStatement("SELECT id::text FROM users WHERE email = ?");
            pstmt.setString(1, email);
            rs = pstmt.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            return null;
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignored) {}
            try { if (pstmt != null) pstmt.close(); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Staff lookup (for dropdowns)
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject listStaff() throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONArray staff = new JSONArray();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "SELECT id::text, username, email FROM users WHERE status = 'ACTIVE' AND role != 'USER' ORDER BY username"
            );
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject u = new JSONObject();
                u.put("id",       rs.getString(1));
                u.put("username", rs.getString(2));
                u.put("email",    rs.getString(3));
                staff.add(u);
            }
        } finally {
            if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("staff", staff);
        return result;
    }

    // -------------------------------------------------------------------------
    // Policy document upload / download
    // -------------------------------------------------------------------------

    private static final java.util.Set<String> ALLOWED_EXTENSIONS = new java.util.HashSet<>(
        java.util.Arrays.asList(".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx")
    );

    @SuppressWarnings("unchecked")
    private void uploadPolicyDocument(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String policyId  = (String) input.get("policy_id");
        String fileName  = (String) input.get("file_name");
        String fileData  = (String) input.get("file_data"); // base64, may include data-URI prefix

        if (isBlank(policyId) || isBlank(fileName) || isBlank(fileData)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "policy_id, file_name and file_data are required", req.getRequestURI());
            return;
        }

        String safeName = new File(fileName).getName();
        int dotIdx = safeName.lastIndexOf('.');
        String ext = dotIdx >= 0 ? safeName.substring(dotIdx).toLowerCase() : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "Allowed types: PDF, DOC, DOCX, XLS, XLSX, PPT, PPTX", req.getRequestURI());
            return;
        }

        String raw = fileData.contains(",") ? fileData.substring(fileData.indexOf(',') + 1) : fileData;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(raw.trim());
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "Invalid base64 content", req.getRequestURI());
            return;
        }

        String uploadDir = System.getenv("TSI_EXPORT_PATH");
        if (isBlank(uploadDir)) uploadDir = System.getProperty("user.home") + "/.tsi-compass/exports";
        File dir = new File(uploadDir + "/policies");
        if (!dir.exists() && !dir.mkdirs()) {
            OutputProcessor.errorResponse(res, 500, "Internal Error", "Cannot create upload directory", req.getRequestURI());
            return;
        }

        String storedFile = UUID.randomUUID().toString() + ext;
        File dest = new File(dir, storedFile);
        try (FileOutputStream fos = new FileOutputStream(dest)) {
            fos.write(bytes);
        }

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "UPDATE policies SET document_name = ?, document_path = ? WHERE id = ?::uuid"
            );
            pstmt.setString(1, safeName);
            pstmt.setString(2, dest.getAbsolutePath());
            pstmt.setString(3, policyId);
            pstmt.executeUpdate();
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("document_name", safeName);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) pool.cleanup(null, pstmt, conn);
        }
    }

    @SuppressWarnings("unchecked")
    private void getPolicyDocument(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String policyId = (String) input.get("policy_id");
        if (isBlank(policyId)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "policy_id is required", req.getRequestURI());
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
                "SELECT document_name, document_path FROM policies WHERE id = ?::uuid"
            );
            pstmt.setString(1, policyId);
            rs = pstmt.executeQuery();
            if (!rs.next() || isBlank(rs.getString("document_path"))) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "No document attached to this policy", req.getRequestURI());
                return;
            }
            String docName = rs.getString("document_name");
            String docPath = rs.getString("document_path");

            File file = new File(docPath);
            if (!file.exists() || !file.isFile()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Document file not found on server", req.getRequestURI());
                return;
            }

            byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                int read = 0;
                while (read < bytes.length) {
                    int n = fis.read(bytes, read, bytes.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("document_name", docName);
            result.put("file_data", Base64.getEncoder().encodeToString(bytes));
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
    }

    // -------------------------------------------------------------------------
    // Delete — Meeting
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void deleteMeeting(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("DELETE FROM committee_meetings WHERE id = ?::uuid");
            p.setString(1, id); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(null, p, conn); } catch (Exception i) {}
        }
    }

    // -------------------------------------------------------------------------
    // Delete — Action Item
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void deleteActionItem(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("DELETE FROM committee_action_items WHERE id = ?::uuid");
            p.setString(1, id); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(null, p, conn); } catch (Exception i) {}
        }
    }

    // -------------------------------------------------------------------------
    // Delete — Policy
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void deletePolicy(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("DELETE FROM policies WHERE id = ?::uuid");
            p.setString(1, id); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true);
            OutputProcessor.send(res, 200, result);
        } finally {
            if (pool != null) try { pool.cleanup(null, p, conn); } catch (Exception i) {}
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
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
}
