package me.vonyayou.geminibridge.auth.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.vonyayou.geminibridge.auth.SessionSecrets;
import me.vonyayou.geminibridge.gateway.ApiException;
import me.vonyayou.geminibridge.provider.web.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeminiWebSessionTest {
    @TempDir Path directory;
    BrowserCookieReader reader;
    GeminiWebClient client;
    SessionSecrets credentials;
    GeminiWebSession session;
    String profile;
    @BeforeEach void setup() throws Exception {
        Path root = Files.createDirectories(directory.resolve("profiles/test.default")); Files.createFile(root.resolve("cookies.sqlite"));
        var environment = new MockEnvironment().withProperty("bridge.firefox-root", directory.resolve("profiles").toString()).withProperty("bridge.data-dir", directory.resolve("data").toString());
        var profiles = new BrowserProfiles(environment); profile = profiles.profiles().getFirst().id();
        reader = mock(BrowserCookieReader.class); client = mock(GeminiWebClient.class); credentials = new SessionSecrets();
        var cookies = new BrowserCookieReader.Cookies(Map.of("__Secure-1PSID", "session-secret"), Instant.now().plusSeconds(600));
        var data = new WebSessionData(cookies.values(), "csrf-secret", "build", "sid", "en", "request-session", cookies.expiresAt());
        when(reader.read(any())).thenReturn(cookies); when(client.initialize(any())).thenReturn(data);
        when(client.discover(any())).thenReturn(List.of(new WebModel("gemini-web-abc", "Web model", "abc", Arrays.asList(1, null, null, null, "abc", 4))));
        session = new GeminiWebSession(profiles, reader, client, credentials, new ObjectMapper(), environment);
    }
    @Test void statusContainsNoCredentialsAndDiskStoresOnlyProfileSelection() throws Exception {
        assertThat(session.current()).isNull(); verifyNoInteractions(reader, client);
        var status = session.connect(profile);
        assertThat(status).containsEntry("state", "authenticated").containsEntry("credentialStored", false).containsEntry("availableModels", 1);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(status); assertThat(json).doesNotContain("session-secret", "csrf-secret");
        String stored = Files.readString(directory.resolve("data/browser-session.json")); assertThat(stored).contains(profile).doesNotContain("session-secret", "csrf-secret");
        assertThat(credentials.redact("session-secret csrf-secret")).isEqualTo("[REDACTED] [REDACTED]");
    }
    @Test void logoutClearsSessionAndDisablesAutomaticReconnection() throws Exception {
        session.connect(profile); var previous = session.current();
        session.logout(); assertThat(session.current()).isNull(); assertThat(session.isCurrent(previous)).isFalse();
        assertThat(session.status()).containsEntry("state", "disconnected");
        assertThat(new ObjectMapper().readTree(Files.readString(directory.resolve("data/browser-session.json"))).path("enabled").asBoolean()).isFalse();
        assertThatThrownBy(session::requireAuthenticated).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(401));
        clearInvocations(reader, client); session.check(); verifyNoInteractions(reader, client);
    }
    @Test void refusedAuthenticationNeverPublishesModelAvailability() {
        when(client.discover(any())).thenThrow(new ApiException(403, "gemini_access_restricted", "Google denied access."));
        assertThatThrownBy(() -> session.connect(profile)).isInstanceOf(ApiException.class);
        assertThat(session.current()).isNull(); assertThat(session.connection()).isEqualTo("unavailable");
        assertThat(session.status()).containsEntry("availableModels", 0).containsEntry("sessionLoaded", false);
    }
    @Test void recheckingSameProfileKeepsInFlightRequestsValidUntilLogout() {
        session.connect(profile); var previous = session.current();
        session.connect(profile); assertThat(session.isCurrent(previous)).isTrue();
        session.logout(); assertThat(session.isCurrent(previous)).isFalse();
    }
    @Test void authenticationErrorsAfterGenerationInvalidateTheSession() {
        session.connect(profile); var snapshot = session.current();
        session.generationFailed(snapshot, new ApiException(401, "gemini_reauthentication_required", "Reconnect."));
        assertThat(session.current()).isNull(); assertThat(session.status()).containsEntry("state", "reauthentication_required");
    }
    @Test void aKnownSecurityBlockCannotBeClearedByOnlyRefreshingTheModelList() {
        session.connect(profile); var snapshot = session.current();
        var block = new ApiException(403, "gemini_security_check_required", "Google requires a security check.", "generation");
        session.generationFailed(snapshot, block);
        assertThat(session.status()).containsEntry("state", "verification_required").containsEntry("sessionLoaded", false).containsEntry("availableModels", 0);
        session.check();
        assertThat(session.current()).isNull();
        assertThatThrownBy(session::requireAuthenticated).isSameAs(block);
        assertThat(session.unavailableModel("gemini-web-abc")).isSameAs(block);
        assertThat(session.unavailableModel("unknown-model")).isNull();
    }
    @Test void explicitReconnectAllowsRetryWithTheSameLoginAfterVerification() {
        session.connect(profile); var previous = session.current();
        var block = new ApiException(403, "gemini_security_check_required", "Google requires a security check.", "generation");
        session.generationFailed(previous, block);
        clearInvocations(client);
        assertThat(session.connect(profile)).containsEntry("state", "authenticated").containsEntry("sessionLoaded", true);
        verify(client).initialize(any()); verify(client).discover(any());
        assertThat(session.unavailableModel("gemini-web-abc")).isNull();
        assertThat(session.isCurrent(previous)).isFalse();
        session.generationFailed(session.current(), block);
        assertThat(session.status()).containsEntry("state", "verification_required");
    }
    @Test void reconnectDoesNotClearAnOngoingUpstreamChallenge() {
        session.connect(profile);
        var block = new ApiException(403, "gemini_security_check_required", "Google requires a security check.", "generation");
        session.generationFailed(session.current(), block);
        when(client.initialize(any())).thenThrow(block);
        assertThatThrownBy(() -> session.connect(profile)).isSameAs(block);
        assertThat(session.status()).containsEntry("state", "verification_required").containsEntry("sessionLoaded", false);
        assertThat(session.unavailableModel("gemini-web-abc")).isSameAs(block);
    }
    @Test void manuallyReenteringTheSameCookiesPermitsRetryButBackgroundChecksDoNot() {
        var cookies = Map.of("__Secure-1PSID", "manual-session-secret", "GOOGLE_ABUSE_EXEMPTION", "verification-secret");
        when(client.initialize(any())).thenAnswer(invocation -> {
            BrowserCookieReader.Cookies supplied = invocation.getArgument(0);
            return new WebSessionData(supplied.values(), "csrf-secret", "build", "sid", "en", "request-session", supplied.expiresAt());
        });
        session.connectManual(cookies);
        var block = new ApiException(403, "gemini_security_check_required", "Google requires a security check.", "generation");
        session.generationFailed(session.current(), block);
        session.check();
        assertThat(session.current()).isNull();
        assertThat(session.connectManual(cookies)).containsEntry("sessionLoaded", true);
        assertThat(session.unavailableModel("gemini-web-abc")).isNull();
        verifyNoInteractions(reader);
        assertThat(credentials.redact("verification-secret")).isEqualTo("[REDACTED]");
    }
    @Test void aLateFailureCannotBlockAReconnectedSession() {
        session.connect(profile); var previous = session.current();
        var block = new ApiException(403, "gemini_security_check_required", "Google requires a security check.", "generation");
        session.generationFailed(previous, block);
        session.connect(profile);
        session.generationFailed(previous, block);
        assertThat(session.status()).containsEntry("state", "authenticated");
        assertThat(session.lastError()).isNull();
        assertThat(session.unavailableModel("gemini-web-abc")).isNull();
    }
    @Test void aFailureFromTheSameSessionStillInvalidatesItAfterBackgroundRefresh() {
        session.connect(profile); var previous = session.current();
        session.check();
        session.generationFailed(previous, new ApiException(403, "gemini_security_check_required", "Verification required."));
        assertThat(session.status()).containsEntry("state", "verification_required").containsEntry("sessionLoaded", false);
    }
    @Test void anInitializationChallengeCanBeOpenedAndIsClearedOnLogout() {
        var url = java.net.URI.create("https://www.google.com/sorry/index?token=private-token");
        when(client.initialize(any())).thenThrow(new GeminiVerificationRequired(url, "session_page"));
        assertThatThrownBy(() -> session.connect(profile)).isInstanceOf(GeminiVerificationRequired.class);
        assertThat(session.status()).containsEntry("verificationAvailable", true).containsEntry("state", "verification_required");
        assertThat(session.requireVerificationUrl(profile)).isEqualTo(url);
        session.logout();
        assertThat(session.status()).containsEntry("verificationAvailable", false);
        assertThatThrownBy(() -> session.requireVerificationUrl(profile)).isInstanceOf(ApiException.class);
    }
    @Test void manuallyEnteredCookiesConnectWithoutReadingAnyBrowserProfile() throws Exception {
        when(client.initialize(any())).thenAnswer(invocation -> {
            BrowserCookieReader.Cookies cookies = invocation.getArgument(0);
            return new WebSessionData(cookies.values(), "csrf-secret", "build", "sid", "en", "request-session", cookies.expiresAt(), null, "/app", null, "person@example.com");
        });
        var result = session.connectManual(Map.of("__Secure-1PSID", "manual-session-secret", "__Secure-1PSIDTS", "manual-timestamp-secret"));
        assertThat(result).containsEntry("source", "manual").containsEntry("state", "authenticated").containsEntry("manualCookiesLoaded", true).containsEntry("accountEmail", "person@example.com");
        verifyNoInteractions(reader);
        String response = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertThat(response).doesNotContain("manual-session-secret", "manual-timestamp-secret", "csrf-secret");
        String selection = Files.readString(directory.resolve("data/browser-session.json"));
        assertThat(selection).contains("manual").doesNotContain("manual-session-secret", "manual-timestamp-secret", "csrf-secret");
        assertThat(credentials.redact("manual-session-secret")).isEqualTo("[REDACTED]");
    }
    @Test void manualSourceAfterRestartRequiresNewInputInsteadOfImportingTheBrowser() {
        session.connectManual(Map.of("__Secure-1PSID", "manual-session-secret"));
        clearInvocations(reader, client);
        var environment = new MockEnvironment().withProperty("bridge.data-dir", directory.resolve("data").toString());
        var restored = new GeminiWebSession(new BrowserProfiles(environment), reader, client, credentials, new ObjectMapper(), environment);
        restored.restore();
        assertThat(restored.status()).containsEntry("source", "manual").containsEntry("state", "reauthentication_required").containsEntry("manualCookiesLoaded", false);
        verifyNoInteractions(reader, client);
    }
    @Test void manualConnectionChecksReuseMemoryAndLogoutClearsTheEnteredCookies() {
        session.connectManual(Map.of("__Secure-1PSID", "manual-session-secret"));
        clearInvocations(reader, client); session.check();
        verify(client).initialize(any()); verifyNoInteractions(reader);
        session.logout();
        assertThat(session.status()).containsEntry("manualCookiesLoaded", false).containsEntry("state", "disconnected");
    }
}
