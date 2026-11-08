package me.vonyayou.geminibridge.auth.google;

import me.vonyayou.geminibridge.auth.browser.BrowserProfiles;
import me.vonyayou.geminibridge.auth.browser.GeminiWebSession;
import me.vonyayou.geminibridge.gateway.ApiException;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GoogleLoginService {
    private static final URI LOGIN_URL = URI.create("https://accounts.google.com/AccountChooser?continue=https%3A%2F%2Fgemini.google.com%2Fapp");
    private record Flow(String id, String profileId, String profileName, Instant expiresAt) {}
    private final BrowserProfiles profiles;
    private final BrowserLauncher launcher;
    private final GeminiWebSession session;
    private final ConcurrentHashMap<String, Flow> flows = new ConcurrentHashMap<>();
    public GoogleLoginService(BrowserProfiles profiles, BrowserLauncher launcher, GeminiWebSession session) {
        this.profiles = profiles; this.launcher = launcher; this.session = session;
    }
    public synchronized Map<String, Object> start(String profileId) {
        return start(profileId, false);
    }
    public synchronized Map<String, Object> startVerification(String profileId) {
        return start(profileId, true);
    }
    private Map<String, Object> start(String profileId, boolean verification) {
        flows.values().removeIf(flow -> flow.expiresAt().isBefore(Instant.now()));
        if (flows.size() >= 4) throw new ApiException(429, "google_login_busy", "Finish or close an existing Google login before opening another one.");
        if (profileId == null || profileId.isBlank()) {
            var list = profiles.profiles();
            if (list.size() != 1) throw new ApiException(400, "browser_profile_required", "Select the Firefox profile for Google sign-in.");
            profileId = list.getFirst().id();
        }
        var profile = profiles.resolve(profileId);
        launcher.open(profile, verification ? session.requireVerificationUrl(profileId) : LOGIN_URL);
        var flow = new Flow(UUID.randomUUID().toString().replace("-", ""), profileId, profile.profile().name(), Instant.now().plusSeconds(600));
        flows.put(flow.id(), flow);
        return Map.of("id", flow.id(), "state", verification ? "waiting_for_verification" : "waiting_for_sign_in", "profileName", flow.profileName(), "expiresAt", flow.expiresAt(),
                "message", verification ? "Complete the Google security check in Firefox, then check the session here." : "Complete Google sign-in in Firefox, open Gemini, and then check the session here.");
    }
    public Map<String, Object> complete(String id) {
        var flow = flows.get(id);
        if (flow == null) throw new ApiException(404, "google_login_not_found", "This Google login is no longer active.");
        if (flow.expiresAt().isBefore(Instant.now())) { flows.remove(id); throw new ApiException(410, "google_login_expired", "The Google login window expired. Start sign-in again."); }
        var status = session.connect(flow.profileId());
        flows.remove(id, flow);
        return status;
    }
    public void cancel(String id) { flows.remove(id); }
}
