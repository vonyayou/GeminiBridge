package me.vonyayou.geminibridge.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import me.vonyayou.geminibridge.gateway.ApiException;
import me.vonyayou.geminibridge.gateway.Errors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityFilter extends OncePerRequestFilter {
    public static final int MAX_BODY = 1_048_576;
    private final ObjectMapper mapper;
    private final org.springframework.beans.factory.ObjectProvider<me.vonyayou.geminibridge.history.HistoryService> history;

    public SecurityFilter(ObjectMapper mapper, org.springframework.beans.factory.ObjectProvider<me.vonyayou.geminibridge.history.HistoryService> history) { this.mapper = mapper; this.history = history; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        String path = request.getRequestURI();
        boolean api = path.startsWith("/api/") || path.startsWith("/v1/");
        if (api) response.setHeader("Cache-Control", "no-store");
        if (!isLoopback(request.getRemoteAddr())) {
            reject(request, response, 403, "local_access_required", "GeminiBridge accepts local connections only."); return;
        }
        String hostname = request.getServerName().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(hostname)) {
            reject(request, response, 403, "host_denied", "This hostname is not allowed."); return;
        }
        if (api && !validOrigin(request)) { reject(request, response, 403, "origin_denied", "Cross-origin requests are not allowed."); return; }
        if (request.getContentLengthLong() > MAX_BODY) { reject(request, response, 413, "request_too_large", "Request body exceeds 1 MiB."); return; }

        if (path.startsWith("/v1/")) {
            String auth = request.getHeader("Authorization");
            String key = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : null;
            request.setAttribute("bridge.client", clientId(key));
        } else if (path.startsWith("/api/")) request.setAttribute("bridge.client", "playground");
        HttpServletRequest bounded = request;
        if (api && !Set.of("GET", "HEAD", "OPTIONS", "DELETE").contains(request.getMethod())) {
            byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) { reject(request, response, 413, "request_too_large", "Request body exceeds 1 MiB."); return; }
            bounded = new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    var stream = new java.io.ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        public int read() { return stream.read(); }
                        public boolean isFinished() { return stream.available() == 0; }
                        public boolean isReady() { return true; }
                        public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                    };
                }
            };
        }
        chain.doFilter(bounded, response);
    }

    private boolean validOrigin(HttpServletRequest request) {
        if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))) return false;
        String origin = request.getHeader("Origin");
        if (origin == null) return true;
        try {
            URI uri = URI.create(origin);
            int port = uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
            return uri.getScheme().equals(request.getScheme()) && uri.getHost().equalsIgnoreCase(request.getServerName()) && port == request.getServerPort();
        } catch (Exception e) { return false; }
    }
    private static String clientId(String key) {
        if (key == null || key.isBlank()) return "local";
        try { return "client-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 12); }
        catch (Exception e) { throw new IllegalStateException(); }
    }
    private static boolean isLoopback(String address) {
        try { return java.net.InetAddress.getByName(address).isLoopbackAddress(); }
        catch (Exception e) { return false; }
    }
    private void reject(HttpServletRequest request, HttpServletResponse response, int status, String code, String message) throws IOException {
        history.getObject().rejected(request, status, message);
        response.setStatus(status);
        response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), Errors.body(new ApiException(status, code, message)));
    }
}
