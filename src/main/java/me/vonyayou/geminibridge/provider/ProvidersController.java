package me.vonyayou.geminibridge.provider;

import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
public class ProvidersController {
    private final ModelRegistry registry;
    public ProvidersController(ModelRegistry registry) { this.registry = registry; }
    @GetMapping({"/api/providers", "/api/providers/status"}) List<ProviderStatus> providers() { return registry.statuses(); }
    @PostMapping("/api/providers/{id}/check") ProviderStatus check(@PathVariable String id) { return registry.provider(id).check(); }
    @GetMapping("/api/models") List<Model> models() { return registry.models(); }
    @GetMapping("/api/models/{id}") Model model(@PathVariable String id) { return registry.model(id); }
    @PostMapping("/api/models/refresh") List<Model> refresh() { return registry.refresh(); }
    @GetMapping("/v1/models") Map<String, Object> compatibleModels() {
        return Map.of("object", "list", "data", registry.models().stream().map(this::compatible).toList());
    }
    @GetMapping("/v1/models/{id}") Map<String, Object> compatibleModel(@PathVariable String id) { return compatible(registry.model(id)); }
    private Map<String, Object> compatible(Model model) {
        return Map.of("id", model.id(), "object", "model", "created", model.checkedAt().getEpochSecond(), "owned_by", model.provider());
    }
}
