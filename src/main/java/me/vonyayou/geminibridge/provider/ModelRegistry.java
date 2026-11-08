package me.vonyayou.geminibridge.provider;

import me.vonyayou.geminibridge.gateway.ApiException;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ModelRegistry {
    private final List<Provider> providers;
    public ModelRegistry(List<Provider> providers) { this.providers = List.copyOf(providers); }
    public List<Model> models() { return providers.stream().flatMap(p -> p.discover().stream()).toList(); }
    public List<ProviderStatus> statuses() { return providers.stream().map(Provider::status).toList(); }
    public Model model(String id) {
        var available = models().stream().filter(m -> m.id().equals(id) || m.aliases().contains(id)).findFirst();
        if (available.isPresent()) return available.get();
        for (Provider provider : providers) { var error = provider.unavailableModel(id); if (error != null) throw error; }
        throw new ApiException(404, "model_not_found", "The requested model is not available. No fallback model was selected.", "model");
    }
    public Provider provider(String id) {
        return providers.stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(404, "provider_not_found", "The provider does not exist."));
    }
    public List<Model> refresh() { providers.forEach(Provider::check); return models(); }
}
