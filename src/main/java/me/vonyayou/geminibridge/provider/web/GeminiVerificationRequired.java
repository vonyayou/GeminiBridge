package me.vonyayou.geminibridge.provider.web;

import me.vonyayou.geminibridge.gateway.ApiException;
import java.net.URI;
import java.time.Instant;
import java.util.Set;

public final class GeminiVerificationRequired extends ApiException {
    private final URI url;
    private final Instant expiresAt = Instant.now().plusSeconds(600);

    public GeminiVerificationRequired(URI url, String stage) {
        super(403, "gemini_security_check_required", "Google requires a security check for this request. In Settings, click Open Google verification, complete the check in Firefox on the same network, then check the session. For manual cookies, include GOOGLE_ABUSE_EXEMPTION if Google issued it.", stage);
        this.url = isAllowed(url) ? url : null;
    }
    public URI verificationUrl() { return expiresAt.isAfter(Instant.now()) ? url : null; }
    public static boolean isAllowed(URI url) {
        if (url == null || !"https".equals(url.getScheme()) || url.getHost() == null
                || !Set.of("google.com", "www.google.com", "gemini.google.com", "accounts.google.com").contains(url.getHost())
                || url.getRawUserInfo() != null || url.getFragment() != null || url.getPort() != -1 && url.getPort() != 443
                || url.toASCIIString().length() > 16_384) return false;
        String path = url.getRawPath();
        return path != null && path.matches("/(sorry|challenge)(/[a-zA-Z0-9_./-]*)?") && !path.contains("..");
    }
}
