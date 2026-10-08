package org.tsicoop.compass.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.EventLog;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.OutputProcessor;
import org.tsicoop.compass.framework.PoolDB;
import org.tsicoop.compass.framework.PortalConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Vendor portal: the Self-Service pattern applied to outside vendors (role VENDOR). A vendor contact
 * answers the security questionnaires GRC has sent to their company, attaches evidence and submits.
 *
 * Like SelfService, identity comes only from the authenticated user: the vendor is looked up from
 * users.vendor_id, and no vendor, user or owner id in the request body is ever trusted. Every function
 * checks the assessment belongs to the caller's vendor and is in a state that allows the action; anything
 * else is a 404 so another vendor's records cannot even be probed.
 *
 * Responses use a dedicated shape: question text, guidance and the vendor's own answers only. Weights,
 * critical flags, references, scores, findings, tiers and assessor notes are never included.
 * See docs/tprm-vendor-portal-spec.md.
 */
public class VendorPortal implements Action {

    private static final int MAX_COMMENT = 2000, MAX_TEXT = 4000, MAX_REF = 255;
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024, MAX_FILES_PER_ASSESSMENT = 25, MAX_BYTES_PER_ASSESSMENT = 50 * 1024 * 1024;
    private static final String[] EVIDENCE_TYPES = {"CERTIFICATE", "SOC_REPORT", "PENTEST_SUMMARY", "POLICY", "OTHER"};

    private static class HttpError extends RuntimeException {
        final int status; final String error;
        HttpError(int status, String error, String message) { super(message); this.status = status; this.error = error; }
    }
    private static HttpError bad(String m) { return new HttpError(400, "Bad Request", m); }
    private static HttpError notFound() { return new HttpError(404, "Not Found", "Not found"); }
    private static HttpError conflict(String m) { return new HttpError(409, "Conflict", m); }

    private static class Who { String userId, vendorId, vendorName, username; }

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject in = InputProcessor.getInput(req);
            String func = (String) in.get("_func");
            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing _func", req.getRequestURI()); return;
            }
            JSONObject out = db(false, c -> {
                Who w = identify(c, req);
                switch (func.toLowerCase()) {
                    case "list_my_assessments": return listMine(c, w);
                    case "get_assessment":      return getAssessment(c, w, in);
                    case "save_answer":         return saveAnswer(c, req, w, in);
                    case "upload_evidence":     return uploadEvidence(c, req, w, in);
                    case "list_evidence":       return listEvidence(c, w, in);
                    case "get_evidence":        return getEvidence(c, w, in);
                    case "remove_evidence":     return removeEvidence(c, req, w, in);
                    case "submit_assessment":   return submit(c, req, w, in);
                    default: throw bad("Unknown function: " + func);
                }
            });
            OutputProcessor.send(res, 200, out);
        } catch (HttpError e) {
            OutputProcessor.errorResponse(res, e.status, e.error, e.getMessage(), req.getRequestURI());
        } catch (Exception e) {
            e.printStackTrace(); // detail stays in the server log; the vendor gets a generic message
            OutputProcessor.errorResponse(res, 500, "Internal Error", "Something went wrong. Please try again.", req.getRequestURI());
        }
    }

    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse r) { return "POST".equalsIgnoreCase(m); }

    // ---------- plumbing ----------

    private interface Work<T> { T run(Connection c) throws Exception; }

    private <T> T db(boolean tx, Work<T> w) throws Exception {
        PoolDB pool = new PoolDB(); Connection conn = null;
        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);
            try { T out = w.run(conn); conn.commit(); return out; }
            catch (Exception e) { try { conn.rollback(); } catch (Exception ignored) {} throw e; }
            finally { try { conn.setAutoCommit(true); } catch (Exception ignored) {} }
        } finally { try { pool.cleanup(null, null, conn); } catch (Exception ignored) {} }
    }

    private void bind(PreparedStatement p, Object[] args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) p.setNull(i + 1, Types.VARCHAR);
            else if (a instanceof Boolean) p.setBoolean(i + 1, (Boolean) a);
            else if (a instanceof Integer) p.setInt(i + 1, (Integer) a);
            else if (a instanceof Long) p.setLong(i + 1, (Long) a);
            else p.setString(i + 1, a.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private JSONArray query(Connection c, String sql, Object... args) throws Exception {
        PreparedStatement p = null; ResultSet rs = null; JSONArray out = new JSONArray();
        try {
            p = c.prepareStatement(sql); bind(p, args); rs = p.executeQuery();
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                JSONObject o = new JSONObject();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    int t = md.getColumnType(i); Object v;
                    if (t == Types.BIT || t == Types.BOOLEAN) { boolean b = rs.getBoolean(i); v = rs.wasNull() ? null : (Object) b; }
                    else if (t == Types.INTEGER || t == Types.SMALLINT || t == Types.BIGINT) { long l = rs.getLong(i); v = rs.wasNull() ? null : (Object) l; }
                    else v = rs.getString(i);
                    o.put(md.getColumnLabel(i), v);
                }
                out.add(o);
            }
        } finally { try { if (rs != null) rs.close(); } catch (Exception ignored) {} try { if (p != null) p.close(); } catch (Exception ignored) {} }
        return out;
    }

    private JSONObject one(Connection c, String sql, Object... args) throws Exception {
        JSONArray a = query(c, sql, args);
        return a.isEmpty() ? null : (JSONObject) a.get(0);
    }

    private int exec(Connection c, String sql, Object... args) throws Exception {
        PreparedStatement p = null;
        try { p = c.prepareStatement(sql); bind(p, args); return p.executeUpdate(); }
        finally { try { if (p != null) p.close(); } catch (Exception ignored) {} }
    }

    private static String s(JSONObject o, String k) { Object v = o.get(k); return v == null ? null : v.toString(); }
    private static boolean blank(String x) { return x == null || x.trim().isEmpty(); }
    private JSONObject ok() { JSONObject r = new JSONObject(); r.put("success", true); return r; }

    private void log(HttpServletRequest req, String action, Object... kv) {
        JSONObject ctx = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) ctx.put(kv[i], kv[i + 1]);
        EventLog.log(InputProcessor.getEmail(req), action, ctx.toJSONString());
    }

    /** Who is calling: an active VENDOR user attached to a live vendor. Nothing here comes from the request body. */
    private Who identify(Connection c, HttpServletRequest req) throws Exception {
        if (!PortalConfig.vendorPortalEnabled()) throw new HttpError(403, "Forbidden", "The vendor portal is not available.");
        UUID uid = InputProcessor.getAuthenticatedUserId(req);
        if (uid == null || !"VENDOR".equals(InputProcessor.getRole(req))) throw new HttpError(403, "Forbidden", "The vendor portal is for vendor accounts only.");
        JSONObject u = one(c, "SELECT u.id::text AS id, u.username, u.vendor_id::text AS vendor_id, v.name AS vendor_name FROM users u JOIN vendors v ON v.id = u.vendor_id " +
            "WHERE u.id = ?::uuid AND u.role = 'VENDOR' AND u.status = 'ACTIVE' AND v.archived_at IS NULL", uid.toString());
        // A disabled account stops working immediately, even with a session that has not expired.
        if (u == null) throw new HttpError(403, "Forbidden", "This account is not active.");
        Who w = new Who(); w.userId = s(u, "id"); w.vendorId = s(u, "vendor_id"); w.vendorName = s(u, "vendor_name"); w.username = s(u, "username");
        return w;
    }

    /** The caller's own, sent, not-cancelled assessment; otherwise a 404. */
    private JSONObject mine(Connection c, Who w, String assessmentId, boolean lock) throws Exception {
        if (blank(assessmentId)) throw bad("assessment_id required");
        JSONObject a;
        try {
            a = one(c, "SELECT id::text AS id, questionnaire_id::text AS questionnaire_id, portal_status, portal_due_date::text AS due_date FROM tprm_assessments " +
                "WHERE id = ?::uuid AND vendor_id = ?::uuid AND portal_status <> 'NOT_SENT' AND status <> 'CANCELLED'" + (lock ? " FOR UPDATE" : ""), assessmentId, w.vendorId);
        } catch (java.sql.SQLException e) { throw notFound(); } // malformed id
        if (a == null) throw notFound();
        return a;
    }

    private static boolean open(String status) { return "SENT".equals(status) || "IN_PROGRESS".equals(status) || "RETURNED".equals(status); }

    // ---------- reads ----------

    private JSONObject listMine(Connection c, Who w) throws Exception {
        JSONObject res = ok();
        res.put("vendor_name", w.vendorName); res.put("user_name", w.username);
        res.put("assessments", query(c,
            "SELECT a.id::text AS id, a.assessment_type, a.portal_status, a.portal_due_date::text AS due_date, a.sent_at::text AS sent_at, a.submitted_at::text AS submitted_at, " +
            "qn.name AS questionnaire_name, " +
            "(SELECT COUNT(*) FROM tprm_questions q WHERE q.questionnaire_id = a.questionnaire_id AND q.answer_type <> 'TEXT') AS scored_total, " +
            "(SELECT COUNT(*) FROM tprm_answers x JOIN tprm_questions q ON q.id = x.question_id WHERE x.assessment_id = a.id AND q.answer_type <> 'TEXT' AND x.answer IS NOT NULL AND x.source = 'VENDOR') AS answered, " +
            "(SELECT COUNT(*) FROM tprm_answers x WHERE x.assessment_id = a.id AND x.returned) AS returned_count " +
            "FROM tprm_assessments a JOIN tprm_questionnaires qn ON qn.id = a.questionnaire_id " +
            "WHERE a.vendor_id = ?::uuid AND a.portal_status <> 'NOT_SENT' AND a.status <> 'CANCELLED' ORDER BY a.sent_at DESC NULLS LAST", w.vendorId));
        return res;
    }

    @SuppressWarnings("unchecked")
    private JSONObject getAssessment(Connection c, Who w, JSONObject in) throws Exception {
        JSONObject a = mine(c, w, (String) in.get("assessment_id"), false);
        String ps = s(a, "portal_status");
        JSONArray qs = query(c,
            "SELECT q.id::text AS id, q.section, q.position, q.text, q.guidance, q.answer_type, " +
            // The vendor sees its own words: if the assessor overrode an answer, show what the vendor originally wrote.
            "CASE WHEN x.source = 'VENDOR' THEN x.answer ELSE x.original_answer END AS answer, x.text_answer, " +
            "CASE WHEN x.source = 'VENDOR' THEN x.comment ELSE x.original_comment END AS comment, x.evidence_ref, " +
            "COALESCE(x.returned, FALSE) AS returned, x.return_note " +
            "FROM tprm_questions q LEFT JOIN tprm_answers x ON x.assessment_id = ?::uuid AND x.question_id = q.id " +
            "WHERE q.questionnaire_id = ?::uuid ORDER BY q.position", s(a, "id"), s(a, "questionnaire_id"));
        long unanswered = 0, returned = 0;
        for (Object o : qs) {
            JSONObject q = (JSONObject) o;
            boolean returnedQ = Boolean.TRUE.equals(q.get("returned"));
            boolean editable = "SENT".equals(ps) || "IN_PROGRESS".equals(ps) || ("RETURNED".equals(ps) && returnedQ);
            q.put("editable", editable);
            if (returnedQ) returned++;
            if (!"TEXT".equals(s(q, "answer_type")) && q.get("answer") == null) unanswered++;
        }
        JSONObject out = ok();
        out.put("id", s(a, "id")); out.put("portal_status", ps); out.put("due_date", s(a, "due_date")); out.put("vendor_name", w.vendorName);
        out.put("details", one(c, "SELECT a.assessment_type, qn.name AS questionnaire_name, a.submitted_at::text AS submitted_at FROM tprm_assessments a JOIN tprm_questionnaires qn ON qn.id = a.questionnaire_id WHERE a.id = ?::uuid", s(a, "id")));
        out.put("questions", qs); out.put("unanswered", unanswered); out.put("returned_open", returned);
        out.put("evidence", evidenceRows(c, w, s(a, "id")));
        return out;
    }

    private JSONArray evidenceRows(Connection c, Who w, String assessmentId) throws Exception {
        return query(c, "SELECT id::text AS id, doc_type, file_name, file_size, valid_until::text AS valid_until, question_id::text AS question_id, uploaded_at::text AS uploaded_at " +
            "FROM tprm_documents WHERE vendor_id = ?::uuid AND assessment_id = ?::uuid AND uploaded_by_vendor = TRUE ORDER BY uploaded_at", w.vendorId, assessmentId);
    }

    private JSONObject listEvidence(Connection c, Who w, JSONObject in) throws Exception {
        JSONObject a = mine(c, w, (String) in.get("assessment_id"), false);
        JSONObject out = ok(); out.put("evidence", evidenceRows(c, w, s(a, "id")));
        return out;
    }

    @SuppressWarnings("unchecked")
    private JSONObject getEvidence(Connection c, Who w, JSONObject in) throws Exception {
        String id = (String) in.get("id");
        if (blank(id)) throw bad("id required");
        JSONObject d;
        try {
            d = one(c, "SELECT d.file_name, d.file_path FROM tprm_documents d JOIN tprm_assessments a ON a.id = d.assessment_id " +
                "WHERE d.id = ?::uuid AND d.vendor_id = ?::uuid AND d.uploaded_by_vendor = TRUE AND a.vendor_id = ?::uuid AND a.portal_status <> 'NOT_SENT'", id, w.vendorId, w.vendorId);
        } catch (java.sql.SQLException e) { throw notFound(); }
        if (d == null) throw notFound();
        File f = new File(s(d, "file_path"));
        if (!f.isFile()) throw notFound();
        JSONObject out = ok();
        out.put("file_name", s(d, "file_name")); out.put("file_data", Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(f.toPath())));
        return out;
    }

    // ---------- writes ----------

    private JSONObject saveAnswer(Connection c, HttpServletRequest req, Who w, JSONObject in) throws Exception {
        JSONObject a = mine(c, w, (String) in.get("assessment_id"), true);
        String ps = s(a, "portal_status");
        if (!open(ps)) throw conflict("This questionnaire is no longer open for changes.");
        String qid = (String) in.get("question_id");
        if (blank(qid)) throw bad("question_id required");
        JSONObject q;
        try { q = one(c, "SELECT answer_type FROM tprm_questions WHERE id = ?::uuid AND questionnaire_id = ?::uuid", qid, s(a, "questionnaire_id")); }
        catch (java.sql.SQLException e) { throw notFound(); }
        if (q == null) throw notFound();
        if ("RETURNED".equals(ps)) {
            JSONObject r = one(c, "SELECT returned FROM tprm_answers WHERE assessment_id = ?::uuid AND question_id = ?::uuid", s(a, "id"), qid);
            if (r == null || !Boolean.TRUE.equals(r.get("returned"))) throw conflict("Only the questions returned to you can be changed.");
        }
        String type = s(q, "answer_type");
        String ans = in.get("answer") == null ? "" : in.get("answer").toString().trim().toUpperCase();
        if (!ans.isEmpty() && !Arrays.asList("YES", "NO", "NA").contains(ans)) throw bad("answer must be YES, NO or NA");
        if ("TEXT".equals(type) && !ans.isEmpty()) throw bad("This question takes a written answer");
        if ("YES_NO".equals(type) && "NA".equals(ans)) throw bad("N/A is not available for this question");
        String text = limited((String) in.get("text_answer"), MAX_TEXT, "text_answer");
        String comment = limited((String) in.get("comment"), MAX_COMMENT, "comment");
        String ref = limited((String) in.get("evidence_ref"), MAX_REF, "evidence_ref");
        exec(c, "INSERT INTO tprm_answers (assessment_id, question_id, answer, text_answer, comment, evidence_ref, source, returned) VALUES (?::uuid,?::uuid,?,?,?,?,'VENDOR',FALSE) " +
            "ON CONFLICT (assessment_id, question_id) DO UPDATE SET answer = EXCLUDED.answer, text_answer = EXCLUDED.text_answer, comment = EXCLUDED.comment, " +
            "evidence_ref = EXCLUDED.evidence_ref, source = 'VENDOR', original_answer = NULL, original_comment = NULL, returned = FALSE, answered_at = CURRENT_TIMESTAMP",
            s(a, "id"), qid, ans.isEmpty() ? null : ans, text, comment, ref);
        if ("SENT".equals(ps)) exec(c, "UPDATE tprm_assessments SET portal_status = 'IN_PROGRESS' WHERE id = ?::uuid", s(a, "id"));
        log(req, "VENDOR_PORTAL_ANSWER_SAVED", "assessment_id", s(a, "id"), "question_id", qid, "vendor", w.vendorName);
        JSONObject out = ok(); out.put("portal_status", "SENT".equals(ps) ? "IN_PROGRESS" : ps);
        return out;
    }

    private String limited(String v, int max, String name) {
        if (v == null) return null;
        v = v.trim();
        if (v.length() > max) throw bad(name + " is too long (maximum " + max + " characters)");
        return v.isEmpty() ? null : v;
    }

    private static boolean magicOk(String ext, byte[] b) {
        if (b.length < 8) return false;
        switch (ext) {
            case ".pdf": return b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
            case ".docx": case ".xlsx": return b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
            case ".png": return (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
            case ".jpg": case ".jpeg": return (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF;
            default: return false;
        }
    }

    private static String cleanName(String raw) {
        String n = new File(raw.replace('\\', '/')).getName().replaceAll("[^A-Za-z0-9._ ()-]", "_").trim();
        if (n.length() > 150) { int dot = n.lastIndexOf('.'); String ext = dot > 0 ? n.substring(dot) : ""; n = n.substring(0, 150 - ext.length()) + ext; }
        return n;
    }

    @SuppressWarnings("unchecked")
    private JSONObject uploadEvidence(Connection c, HttpServletRequest req, Who w, JSONObject in) throws Exception {
        JSONObject a = mine(c, w, (String) in.get("assessment_id"), true);
        if (!open(s(a, "portal_status"))) throw conflict("This questionnaire is no longer open for changes.");
        String rawName = (String) in.get("file_name"), data = (String) in.get("file_data");
        if (blank(rawName) || blank(data)) throw bad("file_name and file_data required");
        String name = cleanName(rawName);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot).toLowerCase() : "";
        if (!Arrays.asList(".pdf", ".docx", ".xlsx", ".png", ".jpg", ".jpeg").contains(ext)) throw bad("Only PDF, DOCX, XLSX, PNG and JPG files can be uploaded.");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode((data.contains(",") ? data.substring(data.indexOf(',') + 1) : data).trim()); }
        catch (Exception e) { throw bad("The file could not be read."); }
        if (bytes.length == 0) throw bad("The file is empty.");
        if (bytes.length > MAX_FILE_BYTES) throw bad("Files can be at most " + (MAX_FILE_BYTES / (1024 * 1024)) + " MB.");
        if (!magicOk(ext, bytes)) throw bad("The file content does not match its type. Upload a genuine " + ext.substring(1).toUpperCase() + " file.");
        String qid = (String) in.get("question_id");
        if (!blank(qid)) {
            JSONObject q;
            try { q = one(c, "SELECT 1 AS x FROM tprm_questions WHERE id = ?::uuid AND questionnaire_id = ?::uuid", qid, s(a, "questionnaire_id")); }
            catch (java.sql.SQLException e) { throw notFound(); }
            if (q == null) throw notFound();
        }
        JSONObject tot = one(c, "SELECT COUNT(*) AS n, COALESCE(SUM(file_size), 0)::bigint AS bytes FROM tprm_documents WHERE assessment_id = ?::uuid AND uploaded_by_vendor = TRUE", s(a, "id"));
        if (((Number) tot.get("n")).longValue() >= MAX_FILES_PER_ASSESSMENT) throw conflict("This questionnaire already has the maximum of " + MAX_FILES_PER_ASSESSMENT + " files.");
        if (((Number) tot.get("bytes")).longValue() + bytes.length > MAX_BYTES_PER_ASSESSMENT) throw conflict("The total size of files for this questionnaire would exceed " + (MAX_BYTES_PER_ASSESSMENT / (1024 * 1024)) + " MB.");
        String docType = in.get("doc_type") == null ? "OTHER" : in.get("doc_type").toString().trim().toUpperCase();
        if (!Arrays.asList(EVIDENCE_TYPES).contains(docType)) docType = "OTHER";
        String valid = (String) in.get("valid_until");
        if (!blank(valid) && !valid.matches("\\d{4}-\\d{2}-\\d{2}")) throw bad("valid_until must be a date (YYYY-MM-DD)");

        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(bytes)) sb.append(String.format("%02x", x));
        String base = System.getenv("TSI_EXPORT_PATH");
        if (blank(base)) base = System.getProperty("user.home") + "/.tsi-compass/exports";
        File dir = new File(base + "/tprm_documents");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create upload directory");
        File dest = new File(dir, UUID.randomUUID() + ext); // server-generated name; the vendor's name is only a label
        try (FileOutputStream fos = new FileOutputStream(dest)) { fos.write(bytes); }
        try {
            JSONObject row = one(c, "INSERT INTO tprm_documents (vendor_id, assessment_id, question_id, doc_type, file_name, file_path, sha256_checksum, valid_until, uploaded_by, uploaded_by_vendor, file_size) " +
                "VALUES (?::uuid,?::uuid,?::uuid,?,?,?,?,?::date,?::uuid,TRUE,?) RETURNING id::text AS id", w.vendorId, s(a, "id"), blank(qid) ? null : qid, docType, name,
                dest.getAbsolutePath(), sb.toString(), blank(valid) ? null : valid, w.userId, (long) bytes.length);
            log(req, "VENDOR_PORTAL_EVIDENCE_UPLOADED", "assessment_id", s(a, "id"), "document_id", s(row, "id"), "vendor", w.vendorName, "sha256", sb.toString());
            JSONObject out = ok(); out.put("id", s(row, "id")); out.put("evidence", evidenceRows(c, w, s(a, "id")));
            return out;
        } catch (Exception e) { dest.delete(); throw e; }
    }

    private JSONObject removeEvidence(Connection c, HttpServletRequest req, Who w, JSONObject in) throws Exception {
        String id = (String) in.get("id");
        if (blank(id)) throw bad("id required");
        JSONObject d;
        try {
            d = one(c, "SELECT d.file_path, d.assessment_id::text AS assessment_id, a.portal_status FROM tprm_documents d JOIN tprm_assessments a ON a.id = d.assessment_id " +
                "WHERE d.id = ?::uuid AND d.vendor_id = ?::uuid AND d.uploaded_by_vendor = TRUE AND d.uploaded_by = ?::uuid AND a.vendor_id = ?::uuid", id, w.vendorId, w.userId, w.vendorId);
        } catch (java.sql.SQLException e) { throw notFound(); }
        if (d == null) throw notFound();
        if (!open(s(d, "portal_status"))) throw conflict("Files cannot be removed once the questionnaire has been submitted.");
        exec(c, "DELETE FROM tprm_documents WHERE id = ?::uuid", id);
        try { new File(s(d, "file_path")).delete(); } catch (Exception ignored) {}
        log(req, "VENDOR_PORTAL_EVIDENCE_REMOVED", "assessment_id", s(d, "assessment_id"), "document_id", id, "vendor", w.vendorName);
        JSONObject out = ok(); out.put("evidence", evidenceRows(c, w, s(d, "assessment_id")));
        return out;
    }

    private JSONObject submit(Connection c, HttpServletRequest req, Who w, JSONObject in) throws Exception {
        JSONObject a = mine(c, w, (String) in.get("assessment_id"), true);
        String ps = s(a, "portal_status");
        if (!open(ps)) throw conflict("This questionnaire has already been submitted.");
        if (!Boolean.TRUE.equals(in.get("declaration"))) throw bad("Please confirm the declaration before submitting.");
        JSONObject chk = one(c,
            "SELECT (SELECT COUNT(*) FROM tprm_questions q LEFT JOIN tprm_answers x ON x.assessment_id = ?::uuid AND x.question_id = q.id " +
            "  WHERE q.questionnaire_id = ?::uuid AND q.answer_type <> 'TEXT' AND x.answer IS NULL) AS unanswered, " +
            "(SELECT COUNT(*) FROM tprm_answers WHERE assessment_id = ?::uuid AND returned) AS still_returned", s(a, "id"), s(a, "questionnaire_id"), s(a, "id"));
        long still = ((Number) chk.get("still_returned")).longValue(), un = ((Number) chk.get("unanswered")).longValue();
        if ("RETURNED".equals(ps) && still > 0) throw conflict(still + " returned question(s) still need an updated answer.");
        if (un > 0) throw conflict(un + " question(s) are still unanswered. Please answer every question (use N/A where it does not apply).");
        exec(c, "UPDATE tprm_assessments SET portal_status = 'SUBMITTED', submitted_at = CURRENT_TIMESTAMP, submitted_by = ?::uuid, declaration_accepted = TRUE, status = 'IN_REVIEW' WHERE id = ?::uuid", w.userId, s(a, "id"));
        exec(c, "UPDATE vendors SET security_questionnaire_status = 'RECEIVED' WHERE id = ?::uuid AND COALESCE(security_questionnaire_status,'') <> 'EVALUATED'", w.vendorId);
        log(req, "VENDOR_PORTAL_SUBMITTED", "assessment_id", s(a, "id"), "vendor", w.vendorName, "by", w.username);
        Notification.emit("TPRM_VENDOR_SUBMITTED", "risks", "Vendor questionnaire submitted", w.vendorName + " has submitted their security questionnaire for review.", "risks-tprm.html", UUID.fromString(s(a, "id")));
        JSONObject out = ok(); out.put("portal_status", "SUBMITTED");
        return out;
    }
}
