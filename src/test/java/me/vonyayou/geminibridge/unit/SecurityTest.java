package me.vonyayou.geminibridge.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.vonyayou.geminibridge.auth.*;
import me.vonyayou.geminibridge.history.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecurityTest {
    SecurityFilter filter;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        var history = mock(HistoryService.class);
        ObjectProvider<HistoryService> supplier = mock(ObjectProvider.class); when(supplier.getObject()).thenReturn(history);
        filter = new SecurityFilter(new ObjectMapper(), supplier);
    }
    @Test void localManagementAndApiRequestsWorkWithoutAuthentication() throws Exception {
        for (String path : java.util.List.of("/api/dashboard", "/v1/models")) {
            var request = new MockHttpServletRequest("GET", path); var response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(request.getSession(false)).isNull();
        }
    }
    @Test void arbitraryClientKeysAreAcceptedAndUsedOnlyAsHashedIdentifiers() throws Exception {
        for (String key : java.util.List.of("x", "local", "arbitrary-client-key")) {
            var request = new MockHttpServletRequest("GET", "/v1/models"); var response = new MockHttpServletResponse();
            request.addHeader("Authorization", "Bearer " + key);
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(request.getAttribute("bridge.client").toString()).startsWith("client-").doesNotContain(key);
        }
    }
    @Test void missingApiKeyUsesTheLocalClientIdentifier() throws Exception {
        var request = new MockHttpServletRequest("GET", "/v1/models");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(request.getAttribute("bridge.client")).isEqualTo("local");
    }
    @Test void rejectsRemotePeersEvenWithSpoofedForwardingHeaders() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/settings"); var response = new MockHttpServletResponse();
        request.setRemoteAddr("203.0.113.1"); request.addHeader("X-Forwarded-For", "127.0.0.1");
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("local_access_required");
    }
    @Test void rejectsCrossOriginRequests() throws Exception {
        var request = new MockHttpServletRequest("PUT", "/api/settings"); var response = new MockHttpServletResponse();
        request.addHeader("Origin", "https://attacker.example");
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
    }
    @Test void rejectsDnsRebindingHostnames() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/dashboard"); var response = new MockHttpServletResponse();
        request.setServerName("attacker.example");
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("host_denied");
    }
    @Test void enforcesSizeWithoutContentLength() throws Exception {
        var request = new MockHttpServletRequest("PUT", "/api/settings") { @Override public long getContentLengthLong() { return -1; } };
        request.setContent(new byte[SecurityFilter.MAX_BODY + 1]); var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(413);
    }
    @Test void googleSessionCredentialsAreStillRedacted() {
        var secrets = new SessionSecrets(); secrets.setProviderSecrets(java.util.Set.of("google-session-secret"));
        var redactor = new Redactor(new ObjectMapper(), secrets);
        String result = redactor.json(java.util.Map.of("api_key", "private", "input", "Bearer abc123 google-session-secret", "nested", java.util.Map.of("cookies", "secret")));
        assertThat(result).doesNotContain("google-session-secret", "abc123", "private", "secret").contains("[REDACTED]");
    }
}
