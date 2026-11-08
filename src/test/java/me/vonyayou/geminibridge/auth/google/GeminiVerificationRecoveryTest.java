package me.vonyayou.geminibridge.auth.google;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.vonyayou.geminibridge.auth.SessionSecrets;
import me.vonyayou.geminibridge.auth.browser.*;
import me.vonyayou.geminibridge.gateway.ApiException;
import me.vonyayou.geminibridge.gateway.Errors;
import me.vonyayou.geminibridge.provider.*;
import me.vonyayou.geminibridge.provider.web.*;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.net.URI;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeminiVerificationRecoveryTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String frame(List<Object> data, String method) throws Exception {
        return mapper.writeValueAsString(List.of(Arrays.asList("wrb.fr", method, mapper.writeValueAsString(data)))) + "\n";
    }
    private void authenticate(MockWebServer server) throws Exception {
        server.enqueue(new MockResponse().setBody("{\"SNlM0e\":\"csrf-secret\"}"));
        var model = new ArrayList<Object>(Collections.nCopies(20, null));
        model.set(0, "account-pro"); model.set(11, "3.1 Pro"); model.set(17, 3);
        var data = new ArrayList<Object>(Collections.nCopies(18, null));
        data.set(14, 1000); data.set(15, List.of(model)); data.set(16, List.of()); data.set(17, List.of());
        server.enqueue(new MockResponse().setBody(frame(data, "otAQ7b")));
    }
    private void cookie(Path db, String name, String value) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var query = connection.prepareStatement("INSERT INTO moz_cookies VALUES(?,?,'.google.com',?,'',1)")) {
            query.setString(1, name); query.setString(2, value); query.setLong(3, Instant.now().plusSeconds(3600).getEpochSecond()); query.executeUpdate();
        }
    }
    @Test void aGoogleChallengeCanBeOpenedCompletedAndRetriedWithoutChangingTheLogin() throws Exception {
        Path db = Files.createDirectories(directory.resolve("profiles/test.default")).resolve("cookies.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE moz_cookies(name TEXT,value TEXT,host TEXT,expiry INTEGER,originAttributes TEXT,lastAccessed INTEGER)");
        }
        cookie(db, "__Secure-1PSID", "unchanged-login-secret");
        var environment = new MockEnvironment().withProperty("bridge.firefox-root", directory.resolve("profiles").toString()).withProperty("bridge.data-dir", directory.resolve("data").toString());
        var profiles = new BrowserProfiles(environment); String profile = profiles.profiles().getFirst().id();
        var challenge = URI.create("https://www.google.com/sorry/index?token=private-verification-token");
        try (var server = new MockWebServer()) {
            server.start();
            var client = new GeminiWebClient(mapper, new OkHttpClient(), server.url("/").toString());
            var secrets = new SessionSecrets();
            var session = new GeminiWebSession(profiles, new BrowserCookieReader(directory.resolve("data").toString()), client, secrets, mapper, environment);
            var provider = new GeminiWebProvider(session, client);
            var launcher = mock(BrowserLauncher.class);
            var login = new GoogleLoginService(profiles, launcher, session);
            authenticate(server); session.connect(profile);
            var request = new Provider.ProviderRequest("gemini-3.1-pro", null, List.of(new Provider.Message("user", "Hello")));
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", challenge.toString()));
            assertThatThrownBy(() -> provider.generate(request, ignored -> {}, () -> false)).isInstanceOfSatisfying(GeminiVerificationRequired.class, error -> {
                assertThat(error.verificationUrl()).isEqualTo(challenge);
                assertThat(Errors.body(error).toString()).doesNotContain("private-verification-token");
            });
            assertThat(session.status()).containsEntry("verificationAvailable", true).containsEntry("sessionLoaded", false);
            assertThat(mapper.writeValueAsString(session.status())).doesNotContain("private-verification-token", "unchanged-login-secret");
            assertThatThrownBy(() -> session.requireVerificationUrl("another-profile")).isInstanceOf(ApiException.class);
            authenticate(server); session.check();
            assertThat(session.status()).containsEntry("verificationAvailable", true).containsEntry("sessionLoaded", false);
            var flow = login.startVerification(profile);
            assertThat(flow).containsEntry("state", "waiting_for_verification");
            assertThat(mapper.writeValueAsString(flow)).doesNotContain("private-verification-token");
            verify(launcher).open(profiles.sources().getFirst(), challenge);
            assertThat(server.getRequestCount()).isEqualTo(5);
            cookie(db, "GOOGLE_ABUSE_EXEMPTION", "completed-verification-secret");
            authenticate(server);
            assertThat(login.complete(flow.get("id").toString())).containsEntry("sessionLoaded", true).containsEntry("verificationAvailable", false);
            var candidate = new ArrayList<Object>(Collections.nCopies(9, null));
            candidate.set(1, List.of("Recovered")); candidate.set(8, List.of(2));
            var answer = new ArrayList<Object>(Collections.nCopies(5, null)); answer.set(4, List.of(candidate));
            server.enqueue(new MockResponse().setBody(frame(answer, "generate")));
            assertThat(provider.generate(request, ignored -> {}, () -> false).text()).isEqualTo("Recovered");
            for (int i = 0; i < 7; i++) server.takeRequest();
            var generation = server.takeRequest();
            assertThat(generation.getHeader("Cookie")).contains("__Secure-1PSID=unchanged-login-secret", "GOOGLE_ABUSE_EXEMPTION=completed-verification-secret");
            assertThat(generation.getHeader("x-goog-ext-525001261-jspb")).contains("account-pro");
            assertThat(secrets.redact("completed-verification-secret")).isEqualTo("[REDACTED]");
            assertThat(Files.readString(directory.resolve("data/browser-session.json"))).doesNotContain("private-verification-token", "completed-verification-secret", "unchanged-login-secret");
            assertThatThrownBy(() -> login.startVerification(profile)).isInstanceOf(ApiException.class);
            session.logout();
            assertThat(session.status()).containsEntry("verificationAvailable", false);
        }
    }
}
