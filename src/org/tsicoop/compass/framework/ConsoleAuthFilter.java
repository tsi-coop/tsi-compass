package org.tsicoop.compass.framework;

import jakarta.servlet.*;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Server-side gate for the static console and self-service pages.
 *
 * These pages used to be served unconditionally to any HTTP client; the only login
 * check was client-side JS reading localStorage after the page had already been
 * delivered. This filter requires a valid, non-revoked session JWT, issued at login
 * in an HttpOnly cookie so plain browser navigation (not just fetch/XHR) can present
 * it, or redirects to the login page without serving the file.
 *
 * API authentication is unchanged: /api/* calls are still validated independently
 * by InterceptingFilter on every request.
 */
public class ConsoleAuthFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void destroy() {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        // The portal does not exist unless it is switched on.
        String area = areaOf(req.getRequestURI());
        if ("vendorportal".equals(area) && !PortalConfig.vendorPortalEnabled()) {
            res.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String token = readSessionCookie(req);
        if (token == null || !JWTUtil.isTokenValid(token)) {
            res.sendRedirect("/index.html");
            return;
        }

        // Role-aware area gating: the vendor portal is for VENDOR sessions only, and VENDOR sessions
        // may not load console or employee self-service pages.
        boolean vendor = "VENDOR".equals(JWTUtil.getRoleFromToken(token));
        if ("vendorportal".equals(area) && !vendor) {
            res.sendRedirect("/index.html");
            return;
        }
        if (vendor && !"vendorportal".equals(area)) {
            res.sendRedirect("/vendorportal/index.html");
            return;
        }

        // Module gate: a role with NONE on a module must not be served that module's pages.
        // (rbac.js repeats this client-side for UX; this is the enforced check.)
        String module = moduleForPage(req.getRequestURI());
        if (module != null) {
            String role = JWTUtil.getRoleFromToken(token);
            if (role == null || "NONE".equals(InterceptingFilter.getPermissionLevel(role, module))) {
                res.sendRedirect("/console/dashboard.html");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private static String areaOf(String uri) {
        if (uri.startsWith("/vendorportal/")) return "vendorportal";
        if (uri.startsWith("/selfservice/")) return "selfservice";
        return "console";
    }

    /** Console page name prefix -> role_permissions module. Pages not listed are gated by login only. */
    private static String moduleForPage(String uri) {
        String page = uri.substring(uri.lastIndexOf('/') + 1);
        if (page.startsWith("data-governance")) return "data";
        if (page.equals("risks.html") || page.startsWith("risks-")) return "risks";
        return null;
    }

    private String readSessionCookie(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (InputProcessor.CONSOLE_SESSION_COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
