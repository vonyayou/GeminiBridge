package me.vonyayou.geminibridge.auth.browser;

import me.vonyayou.geminibridge.gateway.ApiException;
import java.time.Instant;
import java.util.*;

public final class ManualCookieInput {
    public static final Set<String> NAMES = Set.of("__Secure-1PSID", "__Secure-1PSIDTS", "__Secure-1PSIDCC", "__Secure-3PSID", "__Secure-3PSIDTS", "__Secure-3PSIDCC", "GOOGLE_ABUSE_EXEMPTION");
    private ManualCookieInput() {}
    public static BrowserCookieReader.Cookies parse(Map<String, String> input) {
        if (input == null || input.size() > NAMES.size()) throw new ApiException(400, "invalid_manual_cookies", "Enter the Google session cookie values.");
        var values = new LinkedHashMap<String, String>();
        for (var entry : input.entrySet()) {
            if (!NAMES.contains(entry.getKey())) throw new ApiException(400, "unsupported_cookie", "Only the supported Google session cookie names can be entered.");
            String value = entry.getValue();
            if (value == null || value.isBlank()) continue;
            value = value.trim();
            if (value.startsWith(entry.getKey() + "=")) value = value.substring(entry.getKey().length() + 1);
            if (value.isBlank() || value.length() > 8192 || value.chars().anyMatch(c -> c < 33 || c > 126 || c == ';' || c == '\r' || c == '\n'))
                throw new ApiException(400, "invalid_cookie_value", "Paste a cookie value without headers, separators, or line breaks.", entry.getKey());
            values.put(entry.getKey(), value);
        }
        if (!values.containsKey("__Secure-1PSID")) throw new ApiException(400, "manual_cookie_required", "Enter __Secure-1PSID from your signed-in Google session.", "__Secure-1PSID");
        return new BrowserCookieReader.Cookies(values, Instant.now().plusSeconds(600));
    }
}
