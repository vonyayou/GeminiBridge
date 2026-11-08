package me.vonyayou.geminibridge.provider;

import me.vonyayou.geminibridge.gateway.ApiException;
import me.vonyayou.geminibridge.auth.browser.GeminiWebSession;
import me.vonyayou.geminibridge.provider.web.GeminiWebClient;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

@Component
public class GeminiWebProvider implements Provider {
    private final GeminiWebSession session;
    private final GeminiWebClient client;
    public GeminiWebProvider(GeminiWebSession session, GeminiWebClient client) { this.session = session; this.client = client; }
    public String id() { return "gemini-web"; }
    public List<Model> discover() {
        var snapshot = session.current();
        return snapshot == null ? List.of() : snapshot.models().stream().map(model -> new Model(model.id(), model.displayName(), id(), true, false,
                Set.of("text", "streaming", "conversation-as-text", "unofficial-web"), true, session.checkedAt(), List.of("gemini-web-" + model.upstreamId()))).toList();
    }
    public ProviderStatus status() {
        return new ProviderStatus(id(), "Gemini Web", session.connection(), session.status().get("state").toString(), discover().size(), false,
                "Gemini Web session supplied by browser import or manual cookie entry.", session.checkedAt(), session.lastSuccessfulRequest(), session.lastError());
    }
    public ProviderStatus check() { session.check(); return status(); }
    public ApiException unavailableModel(String id) { return session.unavailableModel(id); }
    public ProviderResult generate(ProviderRequest request, Consumer<String> delta, BooleanSupplier cancelled) {
        var snapshot = session.requireAuthenticated();
        var model = snapshot.models().stream().filter(item -> item.id().equals(request.model())).findFirst()
                .orElseThrow(() -> new ApiException(404, "model_not_found", "The selected model is not available in this Gemini Web session."));
        try {
            var result = client.generate(snapshot.data(), model, request, delta, () -> cancelled.getAsBoolean() || !session.isCurrent(snapshot));
            session.succeeded(); return result;
        } catch (ApiException error) { session.generationFailed(snapshot, error); throw error; }
    }
}
