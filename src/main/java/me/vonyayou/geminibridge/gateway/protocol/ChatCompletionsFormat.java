package me.vonyayou.geminibridge.gateway.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.vonyayou.geminibridge.gateway.*;
import me.vonyayou.geminibridge.provider.Provider;
import java.util.*;

public class ChatCompletionsFormat implements CompletionFormat {
    private static final Set<String> FIELDS = Set.of("model", "messages", "stream", "temperature", "max_tokens", "max_completion_tokens", "response_format", "tools", "tool_choice", "n");
    public ResponseRequest parse(JsonNode input) {
        if (input == null || !input.isObject()) throw new ApiException(400, "invalid_request", "Provide a JSON object.");
        input.fieldNames().forEachRemaining(key -> { if (!FIELDS.contains(key)) throw unsupported(key); });
        for (String key : List.of("temperature", "max_tokens", "max_completion_tokens")) if (input.hasNonNull(key)) throw unsupported(key);
        if (input.hasNonNull("n") && (!input.get("n").isIntegralNumber() || input.get("n").asInt() != 1)) throw unsupported("n");
        if (input.hasNonNull("response_format")) {
            JsonNode format = input.get("response_format");
            if (!format.isObject() || format.size() != 1 || !format.path("type").asText().equals("text")) throw unsupported("response_format");
        }
        JsonNode messages = input.get("messages");
        if (messages == null || !messages.isArray()) throw new ApiException(400, "invalid_request", "Messages must be an array of text messages.", "messages");
        var normalized = messages.deepCopy();
        for (JsonNode message : normalized) {
            if (!message.isObject()) throw new ApiException(400, "invalid_request", "Messages must contain message objects.", "messages");
            if (message.hasNonNull("tool_calls") || message.hasNonNull("function_call")) throw unsupported("messages.tool_calls");
            JsonNode content = message.get("content");
            if (content != null && content.isArray()) {
                for (JsonNode part : content) {
                    if (!part.isObject() || !part.path("type").asText().equals("text") || !part.path("text").isTextual()) throw unsupported("messages.content");
                    ((ObjectNode) part).put("type", "input_text");
                }
            }
        }
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        if (input.has("model")) request.set("model", input.get("model"));
        if (input.has("stream")) request.set("stream", input.get("stream"));
        if (input.has("tools")) request.set("tools", input.get("tools"));
        if (input.has("tool_choice")) request.set("tool_choice", input.get("tool_choice"));
        request.set("input", normalized); request.put("store", false);
        return ResponseRequest.parse(request);
    }
    public Map<String, Object> reply(ResponseDocument doc, String status, Provider.ProviderResult result, ApiException error) {
        if (error != null) return Errors.body(error);
        var body = envelope(doc, "chat.completion");
        body.put("choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", result.text()), "finish_reason", "stop")));
        if (result.inputTokens() != null && result.outputTokens() != null) body.put("usage", Map.of("prompt_tokens", result.inputTokens(), "completion_tokens", result.outputTokens(), "total_tokens", result.inputTokens() + result.outputTokens()));
        else body.put("usage", null);
        return body;
    }
    public void start(ResponseDocument doc, StreamOutput stream) { chunk(doc, stream, Map.of("role", "assistant", "content", ""), null); }
    public void delta(ResponseDocument doc, StreamOutput stream, String text) { chunk(doc, stream, Map.of("content", text), null); }
    public void complete(ResponseDocument doc, StreamOutput stream, Provider.ProviderResult result) { chunk(doc, stream, Map.of(), "stop"); stream.data("[DONE]"); }
    public void fail(ResponseDocument doc, StreamOutput stream, ApiException error) { stream.data(Errors.body(error)); }
    private Map<String, Object> envelope(ResponseDocument doc, String type) {
        var body = new LinkedHashMap<String, Object>();
        body.put("id", "chatcmpl-" + doc.id().substring(5)); body.put("object", type); body.put("created", doc.createdAt()); body.put("model", doc.model());
        return body;
    }
    private void chunk(ResponseDocument doc, StreamOutput stream, Map<String, Object> delta, String finishReason) {
        var body = envelope(doc, "chat.completion.chunk");
        var choice = new LinkedHashMap<String, Object>(); choice.put("index", 0); choice.put("delta", delta); choice.put("finish_reason", finishReason);
        body.put("choices", List.of(choice)); stream.data(body);
    }
    private static ApiException unsupported(String field) { return new ApiException(400, "unsupported_parameter", "The selected provider does not support parameter '" + field + "'. Use provider defaults and prompt instructions for JSON output.", field); }
}
