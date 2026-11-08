package me.vonyayou.geminibridge.provider.web;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import java.util.*;

public final class SessionCookieJar implements CookieJar {
    private final HttpUrl origin;
    private final Map<String, Cookie> cookies = new LinkedHashMap<>();
    private final Set<String> secrets = new LinkedHashSet<>();
    public SessionCookieJar(HttpUrl origin, Map<String, String> initial) {
        this.origin = origin;
        String domain = origin.host().equals("gemini.google.com") ? "google.com" : origin.host();
        initial.forEach((name, value) -> {
            var builder = new Cookie.Builder().name(name).value(value).domain(domain).path("/").httpOnly();
            if (origin.isHttps()) builder.secure();
            Cookie cookie = builder.build(); cookies.put(key(cookie), cookie); remember(value);
        });
    }
    @Override public synchronized List<Cookie> loadForRequest(HttpUrl url) {
        if (!sameOrigin(url)) return List.of();
        cookies.values().removeIf(cookie -> cookie.expiresAt() <= System.currentTimeMillis());
        return cookies.values().stream().filter(cookie -> cookie.matches(url)).sorted(Comparator.comparingInt((Cookie cookie) -> cookie.path().length()).reversed()).toList();
    }
    @Override public synchronized void saveFromResponse(HttpUrl url, List<Cookie> incoming) {
        if (!sameOrigin(url)) return;
        for (Cookie cookie : incoming) {
            boolean domainMatches = cookie.hostOnly() ? cookie.domain().equals(url.host()) : cookie.domain().equals(url.host()) || url.host().endsWith("." + cookie.domain());
            if (!domainMatches) continue;
            if (cookie.expiresAt() <= System.currentTimeMillis()) cookies.remove(key(cookie));
            else { cookies.put(key(cookie), cookie); remember(cookie.value()); }
        }
    }
    public synchronized Set<String> secrets() { return Set.copyOf(secrets); }
    public synchronized Map<String, String> sessionValues() {
        var values = new LinkedHashMap<String, String>();
        for (Cookie cookie : cookies.values()) if (cookie.expiresAt() > System.currentTimeMillis() && me.vonyayou.geminibridge.auth.browser.ManualCookieInput.NAMES.contains(cookie.name())) values.put(cookie.name(), cookie.value());
        return Map.copyOf(values);
    }
    private void remember(String value) {
        if (value.length() < 8) return;
        while (secrets.size() >= 128) secrets.remove(secrets.iterator().next());
        secrets.add(value);
    }
    private static String key(Cookie cookie) { return cookie.name() + "\n" + cookie.domain() + "\n" + cookie.path(); }
    private boolean sameOrigin(HttpUrl url) { return url.scheme().equals(origin.scheme()) && url.host().equals(origin.host()) && url.port() == origin.port(); }
    @Override public String toString() { return "SessionCookieJar[credentials=REDACTED]"; }
}
