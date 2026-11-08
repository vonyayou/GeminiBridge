package me.vonyayou.geminibridge.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Component
public class MockProvider implements Provider {
    private final boolean enabled;
    private volatile Instant checkedAt;
    private volatile Instant lastSuccessfulRequest;
    public MockProvider(@Value("${test.mock-enabled:true}") boolean enabled) { this.enabled = enabled; check(); }
    public String id() { return "mock"; }
    public List<Model> discover() {
        return enabled ? List.of(new Model("mock-echo", "Mock Echo", id(), true, true,
                Set.of("text", "conversation", "streaming"), true, checkedAt)) : List.of();
    }
    public ProviderStatus status() {
        return new ProviderStatus(id(), "Mock Provider", enabled ? "available" : "disabled", "not_required",
                enabled ? 1 : 0, true, "Deterministic local echo for gateway testing. This is not a Gemini model.", checkedAt, lastSuccessfulRequest, null);
    }
    public ProviderStatus check() { checkedAt = Instant.now(); return status(); }
    public ProviderResult generate(ProviderRequest request, Consumer<String> onDelta, BooleanSupplier cancelled) {
        if (!enabled) throw new me.vonyayou.geminibridge.gateway.ApiException(503, "provider_disabled", "Mock Provider is disabled.");
        String last = request.messages().stream().filter(m -> m.role().equals("user")).reduce((a,b) -> b).map(Message::content).orElse("");
        String text = "**Mock Provider** · Local test response\n\n" + last + "\n\n_Received " + request.messages().size() + " message(s)" +
                (request.instructions() == null || request.instructions().isBlank() ? "." : " and system instructions.") +
                " No Gemini request was made. Token usage is unavailable._";
        if (text.length() > 65_536) throw new me.vonyayou.geminibridge.gateway.ApiException(400, "mock_input_limit", "Mock Echo accepts up to 64 KiB of output text.");
        for (int offset = 0; offset < text.length();) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
            int end = Math.min(offset + 16, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end++;
            onDelta.accept(text.substring(offset, end));
            offset = end;
            if (cancelled.getAsBoolean()) throw new CancellationException();
            try { Thread.sleep(8); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException(); }
        }
        lastSuccessfulRequest = Instant.now();
        return new ProviderResult(text, null, null);
    }
}
