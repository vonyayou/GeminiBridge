package me.vonyayou.geminibridge.gateway;

import me.vonyayou.geminibridge.provider.Provider;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

@Component
public class ConversationStore {
    private record Context(String owner, List<Provider.Message> messages, Instant expires) {}
    private final LinkedHashMap<String, Context> contexts = new LinkedHashMap<>();
    public synchronized List<Provider.Message> previous(String id, String owner) {
        prune();
        if (id == null) return List.of();
        var context = contexts.get(id);
        if (context == null || !context.owner().equals(owner)) throw new ApiException(404, "previous_response_not_found", "Previous response is unavailable. Context expires after 30 minutes and is cleared on restart.", "previous_response_id");
        return context.messages();
    }
    public synchronized void save(String id, String owner, List<Provider.Message> messages, String answer) {
        prune();
        var combined = new java.util.ArrayList<>(messages);
        combined.add(new Provider.Message("assistant", answer));
        if (combined.stream().mapToInt(m -> m.content().length()).sum() > 131_072) return;
        while (contexts.size() >= 100) contexts.remove(contexts.keySet().iterator().next());
        contexts.put(id, new Context(owner, List.copyOf(combined), Instant.now().plusSeconds(1800)));
    }
    private void prune() { contexts.values().removeIf(c -> c.expires().isBefore(Instant.now())); }
}
