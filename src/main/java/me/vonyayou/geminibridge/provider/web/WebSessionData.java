package me.vonyayou.geminibridge.provider.web;

import java.time.Instant;
import java.util.Map;

public record WebSessionData(Map<String, String> cookies, String accessToken, String buildLabel, String sessionId,
                             String language, String requestSessionId, Instant expiresAt, SessionCookieJar cookieJar, String appPath, String accountIndex, String accountEmail) {
    public WebSessionData(Map<String, String> cookies, String accessToken, String buildLabel, String sessionId,
            String language, String requestSessionId, Instant expiresAt) {
        this(cookies, accessToken, buildLabel, sessionId, language, requestSessionId, expiresAt, null, "/app", null, null);
    }
    public WebSessionData { cookies = Map.copyOf(cookies); }
    @Override public String toString() { return "WebSessionData[credentials=REDACTED]"; }
}
