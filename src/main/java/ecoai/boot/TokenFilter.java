package ecoai.boot;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Optional shared-secret guard for LAN use (enabled only when ecoai.token is set). Accepts ?token=, an X-Token header
 * or the HttpOnly SameSite=Strict cookie set on the first successful visit. Health checks stay open for monitoring.
 */
@Component
@ConditionalOnProperty(prefix = "ecoai", name = "token")
public class TokenFilter extends OncePerRequestFilter {
    private final String token;

    public TokenFilter(EcoAiProperties props) { this.token = props.getToken(); }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return token == null || token.isBlank() || p.startsWith("/actuator/health") || p.equals("/api/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String fromQuery = req.getParameter("token");
        String got = fromQuery != null ? fromQuery : req.getHeader("X-Token");
        if (got == null && req.getCookies() != null) {
            for (Cookie c : req.getCookies()) if ("eco_token".equals(c.getName())) got = c.getValue();
        }
        boolean ok = got != null && MessageDigest.isEqual(got.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("text/plain;charset=UTF-8");
            res.getWriter().write("Access token required. Open the page as /?token=<token> once.");
            return;
        }
        if (fromQuery != null) {
            res.addHeader("Set-Cookie", "eco_token=" + token + "; Path=/; HttpOnly; SameSite=Strict");
        }
        chain.doFilter(req, res);
    }
}
