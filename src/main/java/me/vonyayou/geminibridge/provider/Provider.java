package me.vonyayou.geminibridge.provider;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

public interface Provider {
    String id();
    List<Model> discover();
    ProviderStatus status();
    ProviderStatus check();
    default long timeoutMillis() { return 120000; }
    default me.vonyayou.geminibridge.gateway.ApiException unavailableModel(String id) { return null; }
    ProviderResult generate(ProviderRequest request, Consumer<String> onDelta, BooleanSupplier cancelled);
    default ProviderResult generate(ProviderRequest request, Consumer<String> onDelta, Consumer<String> onReasoningDelta, BooleanSupplier cancelled) {
        return generate(request, onDelta, cancelled);
    }
    record Message(String role, String content) {}
    record ProviderRequest(String model, String instructions, List<Message> messages, boolean streaming, me.vonyayou.geminibridge.gateway.ReasoningConfig reasoning) {
        public ProviderRequest(String model, String instructions, List<Message> messages, boolean streaming) { this(model, instructions, messages, streaming, null); }
        public ProviderRequest(String model, String instructions, List<Message> messages) { this(model, instructions, messages, true); }
        public boolean extendedThinking() { return reasoning != null && reasoning.extended(); }
    }
    record ProviderResult(String text, Integer inputTokens, Integer outputTokens, String reasoning) {
        public ProviderResult(String text, Integer inputTokens, Integer outputTokens) { this(text, inputTokens, outputTokens, null); }
    }
}
