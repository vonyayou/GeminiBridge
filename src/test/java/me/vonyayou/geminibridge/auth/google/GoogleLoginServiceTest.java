package me.vonyayou.geminibridge.auth.google;

import me.vonyayou.geminibridge.auth.browser.BrowserProfiles;
import me.vonyayou.geminibridge.auth.browser.GeminiWebSession;
import me.vonyayou.geminibridge.gateway.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleLoginServiceTest {
    @TempDir Path directory;
    BrowserProfiles profiles;
    BrowserLauncher launcher;
    GeminiWebSession session;
    GoogleLoginService login;
    @BeforeEach void setup() throws Exception {
        Path profile = Files.createDirectories(directory.resolve("profiles/test.default")); Files.createFile(profile.resolve("cookies.sqlite"));
        profiles = new BrowserProfiles(new MockEnvironment().withProperty("bridge.firefox-root", directory.resolve("profiles").toString()));
        launcher = mock(BrowserLauncher.class); session = mock(GeminiWebSession.class); login = new GoogleLoginService(profiles, launcher, session);
    }
    @Test void startsBrowserSignInWithoutClaimingTheUserIsAuthenticated() {
        var flow = login.start(null);
        assertThat(flow).containsEntry("state", "waiting_for_sign_in"); assertThat(flow).doesNotContainKey("sessionLoaded");
        verify(launcher).open(eq(profiles.sources().getFirst()), argThat(url -> url.getHost().equals("accounts.google.com") && url.toString().contains("gemini.google.com")));
        verifyNoInteractions(session);
    }
    @Test void completionChecksTheSameProfileAndReturnsVerifiedAccountStatus() {
        String profile = profiles.profiles().getFirst().id();
        when(session.connect(profile)).thenReturn(Map.of("source", "browser", "sessionLoaded", true, "accountEmail", "person@example.com"));
        var flow = login.start(profile);
        var result = login.complete((String) flow.get("id"));
        assertThat(result).containsEntry("source", "browser").containsEntry("accountEmail", "person@example.com");
        verify(session).connect(profile);
        assertThatThrownBy(() -> login.complete((String) flow.get("id"))).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("google_login_not_found"));
    }
    @Test void unfinishedGoogleSignInCanBeCheckedAgain() {
        String profile = profiles.profiles().getFirst().id();
        when(session.connect(profile)).thenThrow(new ApiException(401, "gemini_reauthentication_required", "Sign in first.")).thenReturn(Map.of("sessionLoaded", true));
        String flow = (String) login.start(profile).get("id");
        assertThatThrownBy(() -> login.complete(flow)).isInstanceOf(ApiException.class);
        assertThat(login.complete(flow)).containsEntry("sessionLoaded", true);
    }
    @Test void profilePathsAndArbitraryLoginUrlsCannotBeInjected() {
        assertThatThrownBy(() -> login.start("../../other-profile")).isInstanceOf(ApiException.class); verifyNoInteractions(launcher);
        var browser = new FirefoxBrowserLauncher(new MockEnvironment().withProperty("bridge.firefox-executable", "/test/firefox"));
        var command = browser.command(profiles.sources().getFirst(), URI.create("https://accounts.google.com/AccountChooser"));
        assertThat(command).containsExactly("/test/firefox", "--profile", profiles.sources().getFirst().database().getParent().toAbsolutePath().toString(), "--new-tab", "https://accounts.google.com/AccountChooser");
        assertThatThrownBy(() -> browser.command(profiles.sources().getFirst(), URI.create("https://attacker.example/login"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cancellationClosesOnlyThePendingLoginFlow() {
        String flow = (String) login.start(null).get("id"); login.cancel(flow);
        assertThatThrownBy(() -> login.complete(flow)).isInstanceOf(ApiException.class); verifyNoInteractions(session);
    }
}
