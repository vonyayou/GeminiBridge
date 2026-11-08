package me.vonyayou.geminibridge.auth.google;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth/google")
public class GoogleLoginController {
    private final GoogleLoginService login;
    public GoogleLoginController(GoogleLoginService login) { this.login = login; }
    public record Start(@Size(max = 40) String profileId) {}
    @PostMapping("/start") Map<String, Object> start(@Valid @RequestBody(required = false) Start request) { return login.start(request == null ? null : request.profileId()); }
    @PostMapping("/verify") Map<String, Object> verify(@Valid @RequestBody(required = false) Start request) { return login.startVerification(request == null ? null : request.profileId()); }
    @PostMapping("/{id}/complete") Map<String, Object> complete(@PathVariable String id) { return login.complete(id); }
    @PostMapping("/{id}/cancel") Map<String, Boolean> cancel(@PathVariable String id) { login.cancel(id); return Map.of("cancelled", true); }
}
