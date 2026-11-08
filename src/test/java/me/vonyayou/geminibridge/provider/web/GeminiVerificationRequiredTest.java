package me.vonyayou.geminibridge.provider.web;

import me.vonyayou.geminibridge.auth.browser.BrowserProfiles;
import me.vonyayou.geminibridge.auth.google.FirefoxBrowserLauncher;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class GeminiVerificationRequiredTest {
    @Test void onlyExpectedGoogleVerificationPagesCanBeOpened() {
        var browser = new FirefoxBrowserLauncher(new MockEnvironment().withProperty("bridge.firefox-executable", "/test/firefox"));
        var profile = new BrowserProfiles.Source(new BrowserProfiles.Profile("profile", "Firefox", "test"), Path.of("/test/profile/cookies.sqlite"));
        for (String target : List.of("https://www.google.com/sorry/index?token=secret", "https://gemini.google.com/challenge/check", "https://accounts.google.com/challenge/recaptcha")) {
            var url = URI.create(target);
            assertThat(new GeminiVerificationRequired(url, "generation").verificationUrl()).isEqualTo(url);
            assertThat(browser.command(profile, url)).containsExactly("/test/firefox", "--profile", "/test/profile", "--new-tab", target);
        }
        for (String target : List.of("http://www.google.com/sorry/index", "https://www.google.com.attacker.example/sorry/index", "https://attacker.example/sorry/index",
                "https://name@www.google.com/sorry/index", "https://www.google.com:8443/sorry/index", "https://www.google.com/url?q=anything",
                "https://www.google.com/sorry/../url", "https://www.google.com/sorry/%2e%2e/url", "https://www.google.com/sorry/index#fragment", "file:///sorry/index")) {
            var url = URI.create(target);
            assertThat(new GeminiVerificationRequired(url, "generation").verificationUrl()).isNull();
            assertThatThrownBy(() -> browser.command(profile, url)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
