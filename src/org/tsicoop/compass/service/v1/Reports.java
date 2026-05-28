package org.tsicoop.compass.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.OutputProcessor;
import org.tsicoop.compass.framework.PoolDB;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

public class Reports implements Action {

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
                case "get_executive_summary":
                    OutputProcessor.send(res, 200, getExecutiveSummary());
                    break;
                case "list_schedules":
                    OutputProcessor.send(res, 200, listSchedules());
                    break;
                case "add_schedule":
                    OutputProcessor.send(res, 200, addSchedule(input));
                    break;
                case "update_schedule":
                    OutputProcessor.send(res, 200, updateSchedule(input));
                    break;
                case "generate_monthly_report":
                    generateMonthlyReport(req, res, input);
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

    // ── Executive Summary ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private JSONObject getExecutiveSummary() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            pstmt = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status != 'RETIRED') AS open_risks, " +
                "COUNT(*) FILTER (WHERE inherent_risk_score >= 15 AND status != 'RETIRED') AS high_risks, " +
                "COUNT(*) FILTER (WHERE status IN ('TREATED','MONITORED')) AS treated_risks, " +
                "COUNT(*) AS total_risks FROM risks");
            rs = pstmt.executeQuery();
            if (rs.next()) { result.put("open_risks", rs.getLong("open_risks")); result.put("high_risks", rs.getLong("high_risks")); result.put("treated_risks", rs.getLong("treated_risks")); result.put("total_risks", rs.getLong("total_risks")); }
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {} rs = null; pstmt = null;

            pstmt = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status NOT IN ('RESOLVED','FALSE_POSITIVE')) AS open_vulns, " +
                "COUNT(*) FILTER (WHERE severity = 'CRITICAL' AND status NOT IN ('RESOLVED','FALSE_POSITIVE')) AS critical_vulns, " +
                "COUNT(*) FILTER (WHERE sla_deadline < NOW() AND status NOT IN ('RESOLVED','FALSE_POSITIVE')) AS overdue_vulns FROM vulnerabilities");
            rs = pstmt.executeQuery();
            if (rs.next()) { result.put("open_vulns", rs.getLong("open_vulns")); result.put("critical_vulns", rs.getLong("critical_vulns")); result.put("overdue_vulns", rs.getLong("overdue_vulns")); }
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {} rs = null; pstmt = null;

            pstmt = conn.prepareStatement(
                "SELECT COUNT(DISTINCT c.id) AS total_controls, COUNT(DISTINCT CASE WHEN ca.status IN ('COMPLIANT','APPROVED') THEN c.id END) AS compliant_controls " +
                "FROM controls c LEFT JOIN LATERAL (SELECT status FROM control_attestations WHERE control_id = c.id ORDER BY attested_at DESC NULLS LAST LIMIT 1) ca ON true");
            rs = pstmt.executeQuery();
            if (rs.next()) { long t = rs.getLong("total_controls"); long c = rs.getLong("compliant_controls"); result.put("total_controls", t); result.put("compliant_controls", c); result.put("compliance_pct", t > 0 ? (c * 100L / t) : 0L); }
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {} rs = null; pstmt = null;

            pstmt = conn.prepareStatement("SELECT COUNT(*) FILTER (WHERE status NOT IN ('CLOSED')) AS active_audits, COUNT(*) FILTER (WHERE status = 'CLOSED') AS closed_audits, COUNT(*) AS total_audits FROM audits");
            rs = pstmt.executeQuery();
            if (rs.next()) { result.put("active_audits", rs.getLong("active_audits")); result.put("closed_audits", rs.getLong("closed_audits")); result.put("total_audits", rs.getLong("total_audits")); }
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {} rs = null; pstmt = null;

            pstmt = conn.prepareStatement("SELECT COUNT(*) FILTER (WHERE status = 'OPEN') AS open_observations, COUNT(*) FILTER (WHERE priority = 'HIGH' AND status = 'OPEN') AS high_observations FROM audit_observations");
            rs = pstmt.executeQuery();
            if (rs.next()) { result.put("open_observations", rs.getLong("open_observations")); result.put("high_observations", rs.getLong("high_observations")); }
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {} rs = null; pstmt = null;

            pstmt = conn.prepareStatement("SELECT COUNT(*) FILTER (WHERE status NOT IN ('RESOLVED','CLOSED')) AS open_incidents, COUNT(*) FILTER (WHERE severity IN ('CRITICAL','HIGH') AND status NOT IN ('RESOLVED','CLOSED')) AS high_incidents FROM incidents");
            rs = pstmt.executeQuery();
            if (rs.next()) { result.put("open_incidents", rs.getLong("open_incidents")); result.put("high_incidents", rs.getLong("high_incidents")); }
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {} rs = null; pstmt = null;

            pstmt = conn.prepareStatement("SELECT COUNT(*) FILTER (WHERE status = 'SUBMITTED') AS pending_changes, COUNT(*) FILTER (WHERE status NOT IN ('COMPLETED','REJECTED')) AS active_changes FROM change_requests");
            rs = pstmt.executeQuery();
            if (rs.next()) { result.put("pending_changes", rs.getLong("pending_changes")); result.put("active_changes", rs.getLong("active_changes")); }
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {} rs = null; pstmt = null;

            pstmt = conn.prepareStatement("SELECT COUNT(*) FILTER (WHERE status = 'OPEN') AS open_tickets, COUNT(*) FILTER (WHERE priority IN ('CRITICAL','HIGH') AND status = 'OPEN') AS high_tickets FROM helpdesk_tickets");
            rs = pstmt.executeQuery();
            if (rs.next()) { result.put("open_tickets", rs.getLong("open_tickets")); result.put("high_tickets", rs.getLong("high_tickets")); }
        } finally {
            if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
        }
        result.put("success", true);
        return result;
    }

    // ── Scheduled reports CRUD ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private JSONObject listSchedules() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        JSONObject result = new JSONObject(); JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "SELECT sr.id, sr.name, sr.report_type, sr.cadence, sr.recipients, sr.is_active, " +
                "TO_CHAR(sr.last_run_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') AS last_run_at, " +
                "TO_CHAR(sr.next_run_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') AS next_run_at, " +
                "TO_CHAR(sr.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS created_at, " +
                "u.username AS created_by_name FROM scheduled_reports sr LEFT JOIN users u ON u.id = sr.created_by " +
                "ORDER BY sr.is_active DESC, sr.next_run_at ASC NULLS LAST");
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject row = new JSONObject();
                row.put("id", rs.getString("id")); row.put("name", rs.getString("name"));
                row.put("report_type", rs.getString("report_type")); row.put("cadence", rs.getString("cadence"));
                row.put("recipients", rs.getString("recipients")); row.put("is_active", rs.getBoolean("is_active"));
                row.put("last_run_at", rs.getString("last_run_at")); row.put("next_run_at", rs.getString("next_run_at"));
                row.put("created_at", rs.getString("created_at")); row.put("created_by_name", rs.getString("created_by_name"));
                list.add(row);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {} }
        result.put("success", true); result.put("schedules", list); return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject addSchedule(JSONObject input) throws Exception {
        String name = (String) input.get("name"), reportType = (String) input.get("report_type"),
               cadence = (String) input.get("cadence"), recipients = (String) input.get("recipients"),
               createdBy = (String) input.get("created_by");
        if (isBlank(name) || isBlank(reportType) || isBlank(cadence))
            throw new IllegalArgumentException("name, report_type, and cadence are required");
        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            pstmt = conn.prepareStatement("INSERT INTO scheduled_reports (name, report_type, cadence, recipients, created_by) VALUES (?, ?, ?, ?, ?::uuid) RETURNING id");
            pstmt.setString(1, name); pstmt.setString(2, reportType); pstmt.setString(3, cadence);
            pstmt.setString(4, recipients); pstmt.setString(5, isBlank(createdBy) ? null : createdBy);
            rs = pstmt.executeQuery(); rs.next(); result.put("id", rs.getString("id"));
        } finally { if (pool != null) try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {} }
        result.put("success", true); return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject updateSchedule(JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) throw new IllegalArgumentException("id is required");
        Object isActiveObj = input.get("is_active");
        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "UPDATE scheduled_reports SET name=COALESCE(?,name), report_type=COALESCE(?,report_type), " +
                "cadence=COALESCE(?,cadence), recipients=COALESCE(?,recipients), " +
                "is_active=COALESCE(?::boolean,is_active), next_run_at=COALESCE(?::timestamptz,next_run_at) WHERE id=?::uuid");
            pstmt.setString(1, (String) input.get("name")); pstmt.setString(2, (String) input.get("report_type"));
            pstmt.setString(3, (String) input.get("cadence")); pstmt.setString(4, (String) input.get("recipients"));
            pstmt.setString(5, isActiveObj != null ? isActiveObj.toString() : null);
            pstmt.setString(6, (String) input.get("next_run_at")); pstmt.setString(7, id);
            pstmt.executeUpdate();
        } finally { if (pool != null) try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {} }
        result.put("success", true); return result;
    }

    // ── Monthly Activity Report PDF ──────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void generateMonthlyReport(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        int month, year;
        try {
            month = ((Number) input.get("month")).intValue();
            year  = ((Number) input.get("year")).intValue();
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "month and year are required integers", req.getRequestURI()); return;
        }
        if (month < 1 || month > 12) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "month must be 1-12", req.getRequestURI()); return;
        }

        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end   = start.plusMonths(1);
        String ps    = start.toString();
        String pe    = end.toString();
        String label = start.format(DateTimeFormatter.ofPattern("MMMM yyyy"));

        Map<String, Long>         m       = new HashMap<>();
        Map<String, List<String[]>> detail = new HashMap<>();

        PoolDB pool = new PoolDB();
        Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            conn = pool.getConnection();

            // ── Risks metrics ──
            p = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status != 'RETIRED') AS open_risks, " +
                "COUNT(*) FILTER (WHERE inherent_risk_score >= 15 AND status != 'RETIRED') AS high_risks, " +
                "COUNT(*) FILTER (WHERE created_at >= ?::date AND created_at < ?::date) AS new_risks FROM risks");
            p.setString(1, ps); p.setString(2, pe); rs = p.executeQuery();
            if (rs.next()) { m.put("open_risks", rs.getLong(1)); m.put("high_risks", rs.getLong(2)); m.put("new_risks", rs.getLong(3)); }
            pool.cleanup(rs, p, null); rs = null; p = null;

            // ── Risks detail ──
            p = conn.prepareStatement(
                "SELECT r.risk_code, r.title, r.category, r.inherent_risk_score::text, r.status, " +
                "COALESCE(u.username,'—') AS owner FROM risks r LEFT JOIN users u ON u.id=r.owner_id " +
                "WHERE r.status != 'RETIRED' ORDER BY r.inherent_risk_score DESC LIMIT 30");
            rs = p.executeQuery();
            List<String[]> risks = new ArrayList<>();
            while (rs.next()) risks.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)});
            detail.put("risks", risks);
            pool.cleanup(rs, p, null); rs = null; p = null;

            // ── Vulnerabilities metrics ──
            p = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status NOT IN ('RESOLVED','FALSE_POSITIVE')) AS open_vulns, " +
                "COUNT(*) FILTER (WHERE severity='CRITICAL' AND status NOT IN ('RESOLVED','FALSE_POSITIVE')) AS crit_vulns, " +
                "COUNT(*) FILTER (WHERE created_at >= ?::date AND created_at < ?::date) AS new_vulns FROM vulnerabilities");
            p.setString(1, ps); p.setString(2, pe); rs = p.executeQuery();
            if (rs.next()) { m.put("open_vulns", rs.getLong(1)); m.put("crit_vulns", rs.getLong(2)); m.put("new_vulns", rs.getLong(3)); }
            pool.cleanup(rs, p, null); rs = null; p = null;

            // ── Vulnerabilities detail ──
            p = conn.prepareStatement(
                "SELECT vuln_code, title, severity, status, COALESCE(affected_asset,'—') FROM vulnerabilities " +
                "WHERE status NOT IN ('RESOLVED','FALSE_POSITIVE') " +
                "ORDER BY CASE severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END LIMIT 30");
            rs = p.executeQuery();
            List<String[]> vulns = new ArrayList<>();
            while (rs.next()) vulns.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)});
            detail.put("vulns", vulns);
            pool.cleanup(rs, p, null); rs = null; p = null;

            // ── Controls metrics ──
            p = conn.prepareStatement(
                "SELECT COUNT(DISTINCT c.id) AS total, COUNT(DISTINCT CASE WHEN ca.status IN ('COMPLIANT','APPROVED') THEN c.id END) AS compliant " +
                "FROM controls c LEFT JOIN LATERAL (SELECT status FROM control_attestations WHERE control_id=c.id ORDER BY attested_at DESC NULLS LAST LIMIT 1) ca ON true");
            rs = p.executeQuery();
            if (rs.next()) { long t=rs.getLong(1),c2=rs.getLong(2); m.put("total_controls",t); m.put("compliant_controls",c2); m.put("compliance_pct",t>0?c2*100L/t:0L); }
            pool.cleanup(rs, p, null); rs = null; p = null;

            p = conn.prepareStatement("SELECT COUNT(*) FROM control_attestations WHERE attested_at >= ?::date AND attested_at < ?::date");
            p.setString(1, ps); p.setString(2, pe); rs = p.executeQuery();
            if (rs.next()) m.put("new_attestations", rs.getLong(1));
            pool.cleanup(rs, p, null); rs = null; p = null;

            // ── Controls detail ──
            p = conn.prepareStatement(
                "SELECT c.code, c.title, c.type, COALESCE(ca.status,'Not attested'), " +
                "COALESCE(ca.next_due_date::text,'—'), COALESCE(u.username,'—') AS owner " +
                "FROM controls c LEFT JOIN users u ON u.id=c.owner_id " +
                "LEFT JOIN LATERAL (SELECT status, next_due_date FROM control_attestations WHERE control_id=c.id ORDER BY attested_at DESC NULLS LAST LIMIT 1) ca ON true " +
                "ORDER BY c.code LIMIT 40");
            rs = p.executeQuery();
            List<String[]> controls = new ArrayList<>();
            while (rs.next()) controls.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)});
            detail.put("controls", controls);
            pool.cleanup(rs, p, null); rs = null; p = null;

            // ── Incidents metrics ──
            p = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status NOT IN ('RESOLVED','CLOSED')) AS open_inc, " +
                "COUNT(*) FILTER (WHERE severity IN ('CRITICAL','HIGH') AND status NOT IN ('RESOLVED','CLOSED')) AS high_inc, " +
                "COUNT(*) FILTER (WHERE created_at >= ?::date AND created_at < ?::date) AS new_inc, " +
                "COUNT(*) FILTER (WHERE rca_root_cause IS NULL AND status NOT IN ('RESOLVED','CLOSED')) AS pend_rca FROM incidents");
            p.setString(1, ps); p.setString(2, pe); rs = p.executeQuery();
            if (rs.next()) { m.put("open_inc",rs.getLong(1)); m.put("high_inc",rs.getLong(2)); m.put("new_inc",rs.getLong(3)); m.put("pend_rca",rs.getLong(4)); }
            pool.cleanup(rs, p, null); rs = null; p = null;

            // ── Incidents detail: new this period ──
            p = conn.prepareStatement(
                "SELECT title, severity, status, TO_CHAR(created_at,'DD Mon YYYY') FROM incidents " +
                "WHERE created_at >= ?::date AND created_at < ?::date " +
                "ORDER BY CASE severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END LIMIT 25");
            p.setString(1, ps); p.setString(2, pe); rs = p.executeQuery();
            List<String[]> incNew = new ArrayList<>();
            while (rs.next()) incNew.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
            detail.put("incidents_new", incNew);
            pool.cleanup(rs, p, null); rs = null; p = null;

            // ── Incidents detail: all open ──
            p = conn.prepareStatement(
                "SELECT title, severity, status, TO_CHAR(created_at,'DD Mon YYYY') FROM incidents " +
                "WHERE status NOT IN ('RESOLVED','CLOSED') " +
                "ORDER BY CASE severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END LIMIT 25");
            rs = p.executeQuery();
            List<String[]> incOpen = new ArrayList<>();
            while (rs.next()) incOpen.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
            detail.put("incidents_open", incOpen);
            pool.cleanup(rs, p, null); rs = null; p = null;

            // ── Helpdesk metrics ──
            p = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status='OPEN') AS open_tix, " +
                "COUNT(*) FILTER (WHERE created_at >= ?::date AND created_at < ?::date) AS new_tix FROM helpdesk_tickets");
            p.setString(1, ps); p.setString(2, pe); rs = p.executeQuery();
            if (rs.next()) { m.put("open_tix",rs.getLong(1)); m.put("new_tix",rs.getLong(2)); }
            pool.cleanup(rs, p, null); rs = null; p = null;

            // ── Helpdesk detail ──
            p = conn.prepareStatement(
                "SELECT ht.title, ht.priority, ht.status, TO_CHAR(ht.created_at,'DD Mon YYYY'), COALESCE(u.username,'—') AS assigned " +
                "FROM helpdesk_tickets ht LEFT JOIN users u ON u.id=ht.assigned_to " +
                "WHERE ht.status IN ('OPEN','IN_PROGRESS') " +
                "ORDER BY CASE ht.priority WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END LIMIT 25");
            rs = p.executeQuery();
            List<String[]> tickets = new ArrayList<>();
            while (rs.next()) tickets.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)});
            detail.put("tickets", tickets);
            pool.cleanup(rs, p, null); rs = null; p = null;

            // ── Change requests metrics ──
            p = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status NOT IN ('COMPLETED','REJECTED')) AS active_chg, " +
                "COUNT(*) FILTER (WHERE created_at >= ?::date AND created_at < ?::date) AS new_chg FROM change_requests");
            p.setString(1, ps); p.setString(2, pe); rs = p.executeQuery();
            if (rs.next()) { m.put("active_chg",rs.getLong(1)); m.put("new_chg",rs.getLong(2)); }
            pool.cleanup(rs, p, null); rs = null; p = null;

            // ── Change requests detail ──
            p = conn.prepareStatement(
                "SELECT cr.title, cr.stage, cr.status, TO_CHAR(cr.created_at,'DD Mon YYYY'), COALESCE(u.username,'—') AS requester " +
                "FROM change_requests cr LEFT JOIN users u ON u.id=cr.requester_id " +
                "WHERE cr.status NOT IN ('COMPLETED','REJECTED') ORDER BY cr.created_at DESC LIMIT 25");
            rs = p.executeQuery();
            List<String[]> changes = new ArrayList<>();
            while (rs.next()) changes.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)});
            detail.put("changes", changes);
            pool.cleanup(rs, p, null); rs = null; p = null;

            // ── Audits metrics (scheduled_start as proxy for date) ──
            p = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status!='CLOSED') AS active_audits, " +
                "COUNT(*) FILTER (WHERE scheduled_start >= ?::date AND scheduled_start < ?::date) AS new_audits FROM audits");
            p.setString(1, ps); p.setString(2, pe); rs = p.executeQuery();
            if (rs.next()) { m.put("active_audits",rs.getLong(1)); m.put("new_audits",rs.getLong(2)); }
            pool.cleanup(rs, p, null); rs = null; p = null;

            // ── Audits detail ──
            p = conn.prepareStatement(
                "SELECT a.title, COALESCE(f.name,'—'), COALESCE(u.username,'—'), " +
                "TO_CHAR(a.scheduled_start,'DD Mon YYYY'), TO_CHAR(a.scheduled_end,'DD Mon YYYY'), a.status " +
                "FROM audits a LEFT JOIN frameworks f ON f.id=a.framework_id LEFT JOIN users u ON u.id=a.lead_auditor_id " +
                "WHERE a.status != 'CLOSED' ORDER BY a.scheduled_start DESC LIMIT 25");
            rs = p.executeQuery();
            List<String[]> audits = new ArrayList<>();
            while (rs.next()) audits.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)});
            detail.put("audits", audits);
            pool.cleanup(rs, p, null); rs = null; p = null;

            // ── Observations metrics + detail ──
            p = conn.prepareStatement("SELECT COUNT(*) FILTER (WHERE status='OPEN') AS open_obs, COUNT(*) AS total_obs FROM audit_observations");
            rs = p.executeQuery();
            if (rs.next()) { m.put("open_obs",rs.getLong(1)); m.put("total_obs",rs.getLong(2)); }
            pool.cleanup(rs, p, null); rs = null; p = null;

            p = conn.prepareStatement(
                "SELECT ao.title, ao.priority, ao.status, COALESCE(a.title,'—') AS audit " +
                "FROM audit_observations ao LEFT JOIN audits a ON a.id=ao.audit_id " +
                "WHERE ao.status='OPEN' ORDER BY CASE ao.priority WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END LIMIT 25");
            rs = p.executeQuery();
            List<String[]> obs = new ArrayList<>();
            while (rs.next()) obs.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
            detail.put("observations", obs);
            pool.cleanup(rs, p, null); rs = null; p = null;

        } finally {
            if (pool != null) try { pool.cleanup(rs, p, conn); } catch (Exception ignored) {}
        }

        byte[] pdfBytes = buildPdf(label, m, detail);
        String fileName = "activity-report-" + year + "-" + String.format("%02d", month) + ".pdf";

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("file_name", fileName);
        result.put("file_data", java.util.Base64.getEncoder().encodeToString(pdfBytes));
        OutputProcessor.send(res, 200, result);
    }

    // ── PDF builder ──────────────────────────────────────────────────────────

    private byte[] buildPdf(String label, Map<String, Long> m, Map<String, List<String[]>> d) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 45, 45, 55, 55);
        PdfWriter.getInstance(doc, baos);
        doc.open();

        Color TEAL  = new Color(0, 106, 103);
        Color INK   = new Color(23, 32, 51);
        Color MUTED = new Color(96, 112, 134);
        Color LINE  = new Color(217, 226, 236);
        Color WASH  = new Color(243, 247, 248);
        Color WHITE = Color.WHITE;

        Font fTitle = new Font(Font.HELVETICA, 20, Font.BOLD, WHITE);
        Font fSub   = new Font(Font.HELVETICA,  9, Font.NORMAL, new Color(180, 220, 218));
        Font fGen   = new Font(Font.HELVETICA,  7, Font.NORMAL, new Color(150, 200, 198));
        Font fSec   = new Font(Font.HELVETICA, 10, Font.BOLD, TEAL);
        Font fLbl   = new Font(Font.HELVETICA,  7, Font.BOLD, MUTED);
        Font fBig   = new Font(Font.HELVETICA, 20, Font.BOLD, INK);
        Font fHdr   = new Font(Font.HELVETICA,  8, Font.BOLD, WHITE);
        Font fCell  = new Font(Font.HELVETICA,  8, Font.NORMAL, INK);
        Font fNote  = new Font(Font.HELVETICA,  8, Font.ITALIC, MUTED);
        Font fFoot  = new Font(Font.HELVETICA,  7, Font.ITALIC, MUTED);

        // ── Header banner ──
        PdfPTable banner = new PdfPTable(2);
        banner.setWidthPercentage(100); banner.setWidths(new float[]{1.6f, 1f}); banner.setSpacingAfter(20);
        PdfPCell bLeft = new PdfPCell(); bLeft.setBackgroundColor(TEAL); bLeft.setBorder(Rectangle.NO_BORDER); bLeft.setPadding(16);
        bLeft.addElement(new Paragraph("TSI Compass", fTitle));
        bLeft.addElement(new Paragraph("Monthly Activity Report", fSub));
        banner.addCell(bLeft);
        PdfPCell bRight = new PdfPCell(); bRight.setBackgroundColor(TEAL); bRight.setBorder(Rectangle.NO_BORDER); bRight.setPadding(16);
        bRight.setHorizontalAlignment(Element.ALIGN_RIGHT); bRight.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Paragraph pLbl = new Paragraph(label, new Font(Font.HELVETICA, 13, Font.BOLD, WHITE)); pLbl.setAlignment(Element.ALIGN_RIGHT); bRight.addElement(pLbl);
        Paragraph pGen = new Paragraph("Generated: " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy")), fGen); pGen.setAlignment(Element.ALIGN_RIGHT); bRight.addElement(pGen);
        banner.addCell(bRight);
        doc.add(banner);

        // ── Executive Summary ──
        addSecTitle(doc, "EXECUTIVE SUMMARY", fSec);
        PdfPTable sumTbl = new PdfPTable(5); sumTbl.setWidthPercentage(100); sumTbl.setSpacingBefore(6); sumTbl.setSpacingAfter(18);
        addMetric(sumTbl, "OPEN RISKS",    m.getOrDefault("open_risks",     0L), WASH, fLbl, fBig, LINE);
        addMetric(sumTbl, "COMPLIANCE",    m.getOrDefault("compliance_pct", 0L) + "%", WASH, fLbl, fBig, LINE);
        addMetric(sumTbl, "OPEN INCIDENTS",m.getOrDefault("open_inc",       0L), WASH, fLbl, fBig, LINE);
        addMetric(sumTbl, "OPEN VULNS",    m.getOrDefault("open_vulns",     0L), WASH, fLbl, fBig, LINE);
        addMetric(sumTbl, "OPEN TICKETS",  m.getOrDefault("open_tix",       0L), WASH, fLbl, fBig, LINE);
        doc.add(sumTbl);

        // ── Risk & Vulnerability ──
        addSecTitle(doc, "RISK & VULNERABILITY", fSec);
        PdfPTable riskMet = new PdfPTable(4); riskMet.setWidthPercentage(100); riskMet.setSpacingBefore(6); riskMet.setSpacingAfter(8);
        addMetric(riskMet, "OPEN RISKS",      m.getOrDefault("open_risks",  0L), WASH, fLbl, fBig, LINE);
        addMetric(riskMet, "HIGH / CRITICAL", m.getOrDefault("high_risks",  0L), WASH, fLbl, fBig, LINE);
        addMetric(riskMet, "NEW THIS MONTH",  m.getOrDefault("new_risks",   0L), WASH, fLbl, fBig, LINE);
        addMetric(riskMet, "OPEN VULNS",      m.getOrDefault("open_vulns",  0L), WASH, fLbl, fBig, LINE);
        doc.add(riskMet);
        addDetailTable(doc, new String[]{"Code", "Title", "Category", "Score", "Status", "Owner"},
            new float[]{1f, 3f, 1.5f, 0.7f, 1.2f, 1.5f}, d.get("risks"), fHdr, fCell, fNote, TEAL, LINE, WASH, WHITE);

        addSecTitle(doc, "OPEN VULNERABILITIES", fSec);
        addDetailTable(doc, new String[]{"Code", "Title", "Severity", "Status", "Affected Asset"},
            new float[]{1f, 3f, 1.2f, 1.2f, 2f}, d.get("vulns"), fHdr, fCell, fNote, TEAL, LINE, WASH, WHITE);

        // ── Compliance & Controls ──
        addSecTitle(doc, "COMPLIANCE & CONTROLS", fSec);
        PdfPTable ctrlMet = new PdfPTable(4); ctrlMet.setWidthPercentage(100); ctrlMet.setSpacingBefore(6); ctrlMet.setSpacingAfter(8);
        addMetric(ctrlMet, "TOTAL CONTROLS",    m.getOrDefault("total_controls",     0L), WASH, fLbl, fBig, LINE);
        addMetric(ctrlMet, "COMPLIANT",         m.getOrDefault("compliant_controls", 0L), WASH, fLbl, fBig, LINE);
        addMetric(ctrlMet, "COMPLIANCE %",      m.getOrDefault("compliance_pct",     0L) + "%", WASH, fLbl, fBig, LINE);
        addMetric(ctrlMet, "ATTESTATIONS THIS MONTH", m.getOrDefault("new_attestations", 0L), WASH, fLbl, fBig, LINE);
        doc.add(ctrlMet);
        addDetailTable(doc, new String[]{"Code", "Title", "Type", "Attestation", "Next Due", "Owner"},
            new float[]{1f, 3f, 1.5f, 1.5f, 1.3f, 1.5f}, d.get("controls"), fHdr, fCell, fNote, TEAL, LINE, WASH, WHITE);

        // ── Incidents ──
        addSecTitle(doc, "INCIDENTS", fSec);
        PdfPTable incMet = new PdfPTable(4); incMet.setWidthPercentage(100); incMet.setSpacingBefore(6); incMet.setSpacingAfter(8);
        addMetric(incMet, "OPEN INCIDENTS",  m.getOrDefault("open_inc",  0L), WASH, fLbl, fBig, LINE);
        addMetric(incMet, "CRITICAL / HIGH", m.getOrDefault("high_inc",  0L), WASH, fLbl, fBig, LINE);
        addMetric(incMet, "NEW THIS MONTH",  m.getOrDefault("new_inc",   0L), WASH, fLbl, fBig, LINE);
        addMetric(incMet, "PENDING RCA",     m.getOrDefault("pend_rca",  0L), WASH, fLbl, fBig, LINE);
        doc.add(incMet);

        Paragraph incNewLbl = new Paragraph("Incidents raised this month", fNote); incNewLbl.setSpacingAfter(2); doc.add(incNewLbl);
        addDetailTable(doc, new String[]{"Title", "Severity", "Status", "Reported"},
            new float[]{3f, 1.1f, 1.2f, 1.3f}, d.get("incidents_new"), fHdr, fCell, fNote, TEAL, LINE, WASH, WHITE);

        Paragraph incOpenLbl = new Paragraph("All open incidents", fNote); incOpenLbl.setSpacingBefore(6); incOpenLbl.setSpacingAfter(2); doc.add(incOpenLbl);
        addDetailTable(doc, new String[]{"Title", "Severity", "Status", "Reported"},
            new float[]{3f, 1.1f, 1.2f, 1.3f}, d.get("incidents_open"), fHdr, fCell, fNote, TEAL, LINE, WASH, WHITE);

        // ── IT Operations ──
        addSecTitle(doc, "IT OPERATIONS", fSec);
        PdfPTable opsMet = new PdfPTable(4); opsMet.setWidthPercentage(100); opsMet.setSpacingBefore(6); opsMet.setSpacingAfter(8);
        addMetric(opsMet, "OPEN TICKETS",       m.getOrDefault("open_tix",   0L), WASH, fLbl, fBig, LINE);
        addMetric(opsMet, "NEW TICKETS",        m.getOrDefault("new_tix",    0L), WASH, fLbl, fBig, LINE);
        addMetric(opsMet, "ACTIVE CHANGE REQ.", m.getOrDefault("active_chg", 0L), WASH, fLbl, fBig, LINE);
        addMetric(opsMet, "NEW CHANGES",        m.getOrDefault("new_chg",    0L), WASH, fLbl, fBig, LINE);
        doc.add(opsMet);

        Paragraph ticketLbl = new Paragraph("Open & in-progress helpdesk tickets", fNote); ticketLbl.setSpacingAfter(2); doc.add(ticketLbl);
        addDetailTable(doc, new String[]{"Title", "Priority", "Status", "Created", "Assigned To"},
            new float[]{3f, 1.1f, 1.2f, 1.3f, 1.5f}, d.get("tickets"), fHdr, fCell, fNote, TEAL, LINE, WASH, WHITE);

        Paragraph chgLbl = new Paragraph("Active change requests", fNote); chgLbl.setSpacingBefore(6); chgLbl.setSpacingAfter(2); doc.add(chgLbl);
        addDetailTable(doc, new String[]{"Title", "Stage", "Status", "Created", "Requester"},
            new float[]{3f, 1.2f, 1.2f, 1.3f, 1.5f}, d.get("changes"), fHdr, fCell, fNote, TEAL, LINE, WASH, WHITE);

        // ── Audit & Evidence ──
        addSecTitle(doc, "AUDIT & EVIDENCE", fSec);
        PdfPTable audMet = new PdfPTable(3); audMet.setWidthPercentage(100); audMet.setSpacingBefore(6); audMet.setSpacingAfter(8);
        addMetric(audMet, "ACTIVE AUDITS",      m.getOrDefault("active_audits", 0L), WASH, fLbl, fBig, LINE);
        addMetric(audMet, "OPEN OBSERVATIONS",  m.getOrDefault("open_obs",      0L), WASH, fLbl, fBig, LINE);
        addMetric(audMet, "TOTAL OBSERVATIONS", m.getOrDefault("total_obs",     0L), WASH, fLbl, fBig, LINE);
        doc.add(audMet);

        Paragraph audLbl = new Paragraph("Active audits", fNote); audLbl.setSpacingAfter(2); doc.add(audLbl);
        addDetailTable(doc, new String[]{"Title", "Framework", "Lead Auditor", "Start", "End", "Status"},
            new float[]{2.5f, 1.5f, 1.5f, 1.2f, 1.2f, 1.2f}, d.get("audits"), fHdr, fCell, fNote, TEAL, LINE, WASH, WHITE);

        Paragraph obsLbl = new Paragraph("Open audit observations", fNote); obsLbl.setSpacingBefore(6); obsLbl.setSpacingAfter(2); doc.add(obsLbl);
        addDetailTable(doc, new String[]{"Title", "Priority", "Status", "Audit"},
            new float[]{3f, 1.1f, 1.1f, 2.5f}, d.get("observations"), fHdr, fCell, fNote, TEAL, LINE, WASH, WHITE);

        // ── Footer ──
        Paragraph footer = new Paragraph(
            "Generated automatically by TSI Compass GRC Platform. Data reflects system state at time of generation.", fFoot);
        footer.setSpacingBefore(16);
        doc.add(footer);

        doc.close();
        return baos.toByteArray();
    }

    private void addSecTitle(Document doc, String title, Font font) throws Exception {
        Paragraph p = new Paragraph(title, font);
        p.setSpacingBefore(14); p.setSpacingAfter(4);
        doc.add(p);
    }

    private void addMetric(PdfPTable table, String label, Object value, Color bg, Font lf, Font vf, Color border) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(bg); cell.setPadding(10); cell.setBorderColor(border);
        cell.addElement(new Paragraph(label, lf));
        cell.addElement(new Paragraph(String.valueOf(value), vf));
        table.addCell(cell);
    }

    private void addDetailTable(Document doc, String[] headers, float[] widths, List<String[]> rows,
        Font fHdr, Font fCell, Font fNote, Color teal, Color line, Color wash, Color white) throws Exception {
        if (rows == null || rows.isEmpty()) {
            Paragraph none = new Paragraph("No records.", fNote);
            none.setSpacingAfter(10);
            doc.add(none);
            return;
        }
        PdfPTable tbl = new PdfPTable(headers.length);
        tbl.setWidthPercentage(100); tbl.setWidths(widths);
        tbl.setSpacingAfter(12);
        for (String h : headers) {
            PdfPCell hc = new PdfPCell(new Phrase(h, fHdr));
            hc.setBackgroundColor(teal); hc.setPaddingTop(5); hc.setPaddingBottom(5);
            hc.setPaddingLeft(5); hc.setPaddingRight(5); hc.setBorderColor(teal);
            tbl.addCell(hc);
        }
        boolean alt = false;
        for (String[] row : rows) {
            Color bg = alt ? wash : white;
            for (String val : row) {
                PdfPCell dc = new PdfPCell(new Phrase(val != null ? val : "—", fCell));
                dc.setBackgroundColor(bg); dc.setPaddingTop(4); dc.setPaddingBottom(4);
                dc.setPaddingLeft(5); dc.setPaddingRight(5); dc.setBorderColor(line);
                tbl.addCell(dc);
            }
            alt = !alt;
        }
        doc.add(tbl);
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
