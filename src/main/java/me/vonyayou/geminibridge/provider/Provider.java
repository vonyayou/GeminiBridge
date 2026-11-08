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
    record Message(String role, String content) {}
    record ProviderRequest(String model, String instructions, List<Message> messages, boolean streaming) {
        public ProviderRequest(String model, String instructions, List<Message> messages) { this(model, instructions, messages, true); }
    }
    record ProviderResult(String text, Integer inputTokens, Integer outputTokens) {}
}
