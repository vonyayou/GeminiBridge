package me.vonyayou.geminibridge.gateway;

import me.vonyayou.geminibridge.provider.Provider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ResponseDocument {
    private final String id = "resp_" + java.util.UUID.randomUUID().toString().replace("-", "");
    private final String itemId = "msg_" + java.util.UUID.randomUUID().toString().replace("-", "");
    private final long createdAt = java.time.Instant.now().getEpochSecond();
    private final ResponseRequest request;
    public ResponseDocument(ResponseRequest request) { this.request = request; }
    public String id() { return id; }
    public String itemId() { return itemId; }
    public long createdAt() { return createdAt; }
    public String model() { return request.model(); }
    public Map<String, Object> part(String text) { return Map.of("type", "output_text", "text", text, "annotations", List.of(), "logprobs", List.of()); }
    public Map<String, Object> item(String text, boolean completed) {
        return Map.of("id", itemId, "type", "message", "role", "assistant", "status", completed ? "completed" : "in_progress", "content", completed ? List.of(part(text)) : List.of());
    }
    public Map<String, Object> document(String status, Provider.ProviderResult result, ApiException error) {
        var response = new LinkedHashMap<String, Object>();
        response.put("id", id); response.put("object", "response"); response.put("created_at", createdAt); response.put("status", status);
        response.put("completed_at", status.equals("completed") ? java.time.Instant.now().getEpochSecond() : null);
        response.put("model", request.model()); response.put("error", error == null ? null : Errors.body(error).get("error"));
        response.put("incomplete_details", null); response.put("instructions", request.instructions());
        response.put("output", result == null ? List.of() : List.of(item(result.text(), true)));
        response.put("parallel_tool_calls", false); response.put("tools", List.of()); response.put("tool_choice", "none");
        response.put("previous_response_id", request.previousResponseId()); response.put("store", request.store());
        response.put("metadata", request.metadata() == null ? Map.of() : request.metadata());
        response.put("text", Map.of("format", Map.of("type", "text")));
        if (result != null && result.inputTokens() != null && result.outputTokens() != null)
            response.put("usage", Map.of("input_tokens", result.inputTokens(), "input_tokens_details", Map.of("cached_tokens", 0), "output_tokens", result.outputTokens(), "output_tokens_details", Map.of("reasoning_tokens", 0), "total_tokens", result.inputTokens() + result.outputTokens()));
        else response.put("usage", null);
        return response;
    }
}
