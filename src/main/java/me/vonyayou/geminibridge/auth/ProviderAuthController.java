package me.vonyayou.geminibridge.auth;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class ProviderAuthController {
    private final ProviderAuthService auth;
    public ProviderAuthController(ProviderAuthService auth) { this.auth = auth; }
    @GetMapping("/status") Map<String, Object> status() { return auth.status(); }
    public record Start(@jakarta.validation.constraints.Size(max = 40) String profileId) {}
    @PostMapping("/start") Map<String, Object> start(@jakarta.validation.Valid @RequestBody(required = false) Start request) { return auth.start(request == null ? null : request.profileId()); }
    public record Manual(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(max = 7) Map<String, String> cookies) {
        @Override public String toString() { return "Manual[cookies=REDACTED]"; }
    }
    @PostMapping("/manual") Map<String, Object> manual(@jakarta.validation.Valid @RequestBody Manual request) { return auth.manual(request.cookies()); }
    @GetMapping("/profiles") java.util.List<me.vonyayou.geminibridge.auth.browser.BrowserProfiles.Profile> profiles() { return auth.profiles(); }
    @PostMapping("/logout") Map<String, Object> logout() { return auth.logout(); }
}
