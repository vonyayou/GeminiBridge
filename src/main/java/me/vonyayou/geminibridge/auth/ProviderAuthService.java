package me.vonyayou.geminibridge.auth;

import me.vonyayou.geminibridge.auth.browser.GeminiWebSession;
import me.vonyayou.geminibridge.auth.browser.BrowserProfiles;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class ProviderAuthService {
    private final GeminiWebSession session;
    public ProviderAuthService(GeminiWebSession session) { this.session = session; }
    public Map<String, Object> status() { return session.status(); }
    public Map<String, Object> start(String profileId) { return session.connect(profileId); }
    public Map<String, Object> manual(Map<String, String> cookies) { return session.connectManual(cookies); }
    public Map<String, Object> logout() { return session.logout(); }
    public java.util.List<BrowserProfiles.Profile> profiles() { return session.profiles(); }
}
