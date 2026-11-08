package me.vonyayou.geminibridge.config;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final SettingsService settings;
    public SettingsController(SettingsService settings) { this.settings = settings; }
    @GetMapping Map<String, Object> get() { return settings.view(); }
    @PutMapping Map<String, Object> update(@Valid @RequestBody Settings value) { return settings.update(value); }
}
