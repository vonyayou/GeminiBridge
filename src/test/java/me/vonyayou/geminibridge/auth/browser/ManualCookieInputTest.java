package me.vonyayou.geminibridge.auth.browser;

import me.vonyayou.geminibridge.gateway.ApiException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ManualCookieInputTest {
    @Test void acceptsSessionValuesAndAnOptionalCopiedNamePrefix() {
        var cookies = ManualCookieInput.parse(Map.of("__Secure-1PSID", " __Secure-1PSID=my-session ", "__Secure-1PSIDTS", "timestamp", "__Secure-3PSID", "related-session"));
        assertThat(cookies.values()).containsEntry("__Secure-1PSID", "my-session").containsEntry("__Secure-3PSID", "related-session");
        assertThat(cookies.toString()).doesNotContain("my-session", "related-session");
    }
    @Test void rejectsMissingRequiredCookiesAndUnrelatedCookies() {
        assertThatThrownBy(() -> ManualCookieInput.parse(Map.of())).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("manual_cookie_required"));
        assertThatThrownBy(() -> ManualCookieInput.parse(Map.of("password", "secret"))).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("unsupported_cookie"));
    }
    @Test void acceptsTheVerificationCookieAlongsideAllSessionCookies() {
        var input = new java.util.LinkedHashMap<String, String>();
        for (String name : ManualCookieInput.NAMES) input.put(name, "value-for-" + name);
        var cookies = ManualCookieInput.parse(input);
        assertThat(cookies.values()).hasSize(7).containsEntry("GOOGLE_ABUSE_EXEMPTION", "value-for-GOOGLE_ABUSE_EXEMPTION");
        assertThat(cookies.toString()).doesNotContain("value-for-GOOGLE_ABUSE_EXEMPTION");
    }
    @Test void rejectsHeaderInjectionWithoutReturningTheEnteredValue() {
        String value = "session\r\nAuthorization: secret-header";
        assertThatThrownBy(() -> ManualCookieInput.parse(Map.of("__Secure-1PSID", value))).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.code()).isEqualTo("invalid_cookie_value"); assertThat(error.getMessage()).doesNotContain("secret-header", value);
        });
    }
}
