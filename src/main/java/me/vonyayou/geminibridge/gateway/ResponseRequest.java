package me.vonyayou.geminibridge.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import me.vonyayou.geminibridge.provider.Provider;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public record ResponseRequest(String model, String instructions, List<Provider.Message> messages, boolean stream,
                              String previousResponseId, boolean store, JsonNode metadata) {
    private static final Set<String> FIELDS = Set.of("model", "input", "instructions", "stream", "previous_response_id", "store", "metadata", "temperature", "max_output_tokens", "tools", "tool_choice", "text");
    public ResponseRequest withModel(String id) { return new ResponseRequest(id, instructions, messages, stream, previousResponseId, store, metadata); }
    public static ResponseRequest parse(JsonNode root) {
        if (root == null || !root.isObject()) throw invalid("body", "Provide a JSON object.");
        root.fieldNames().forEachRemaining(key -> { if (!FIELDS.contains(key)) throw unsupported(key); });
        String model = string(root, "model", true);
        if (model.length() > 128) throw invalid("model", "Model ID is too long.");
        String instructions = string(root, "instructions", false);
        if (instructions != null && instructions.length() > 65_536) throw invalid("instructions", "System instructions exceed 64 KiB.");
        String previous = string(root, "previous_response_id", false);
        if (previous != null && previous.length() > 128) throw invalid("previous_response_id", "Response ID is too long.");
        for (String key : List.of("temperature", "max_output_tokens")) if (root.hasNonNull(key)) throw unsupported(key);
        if (root.hasNonNull("tools") && (!root.get("tools").isArray() || !root.get("tools").isEmpty())) throw unsupported("tools");
        if (root.hasNonNull("tool_choice") && !root.get("tool_choice").asText().equals("none")) throw unsupported("tool_choice");
        if (root.hasNonNull("text")) {
            JsonNode text = root.get("text");
            if (!text.isObject() || text.size() != 1 || !text.path("format").isObject() || text.path("format").size() != 1 || !text.path("format").path("type").asText().equals("text")) throw unsupported("text");
        }
        List<Provider.Message> messages = new ArrayList<>();
        JsonNode input = root.get("input");
        if (input == null || input.isNull()) throw invalid("input", "Input is required.");
        if (input.isTextual()) messages.add(new Provider.Message("user", input.asText()));
        else if (input.isArray() && input.size() <= 256) {
            for (JsonNode item : input) {
                if (!item.isObject()) throw invalid("input", "Input items must be messages.");
                if (item.has("type") && !item.path("type").asText().equals("message")) throw unsupported("input.type");
                String role = string(item, "role", true);
                if (!Set.of("user", "assistant", "system", "developer").contains(role)) throw invalid("input.role", "Unsupported message role.");
                JsonNode content = item.get("content");
                var buffer = new StringBuilder();
                if (content != null && content.isTextual()) buffer.append(content.asText());
                else if (content != null && content.isArray()) {
                    for (JsonNode part : content) {
                        if (!part.isObject() || !Set.of("input_text", "output_text").contains(part.path("type").asText()) || !part.path("text").isTextual()) throw unsupported("input.content");
                        buffer.append(part.get("text").asText());
                    }
                } else throw invalid("input.content", "Message content must contain text.");
                messages.add(new Provider.Message(role, buffer.toString()));
            }
        } else throw invalid("input", "Input must be a string or an array of up to 256 text messages.");
        if (messages.isEmpty() || messages.stream().mapToInt(m -> m.content().length()).sum() > 65_536) throw invalid("input", "Input must contain messages and must not exceed 64 KiB of text.");
        JsonNode metadata = root.get("metadata");
        if (metadata != null && !metadata.isNull()) {
            if (!metadata.isObject() || metadata.size() > 16) throw invalid("metadata", "Metadata must be an object with at most 16 string pairs.");
            metadata.fields().forEachRemaining(e -> {
                if (e.getKey().length() > 64 || !e.getValue().isTextual() || e.getValue().asText().length() > 512) throw invalid("metadata", "Metadata keys and values must be short strings.");
            });
        }
        return new ResponseRequest(model, instructions, List.copyOf(messages), bool(root, "stream", false), previous, bool(root, "store", true), metadata);
    }
    private static String string(JsonNode root, String key, boolean required) {
        JsonNode value = root.get(key);
        if (value == null || value.isNull()) { if (required) throw invalid(key, "Missing required field: " + key + "."); return null; }
        if (!value.isTextual() || (required && value.asText().isBlank())) throw invalid(key, "Field must be a nonempty string: " + key + ".");
        return value.asText();
    }
    private static boolean bool(JsonNode root, String key, boolean fallback) {
        if (!root.hasNonNull(key)) return fallback;
        if (!root.get(key).isBoolean()) throw invalid(key, "Field must be boolean: " + key + ".");
        return root.get(key).asBoolean();
    }
    private static ApiException unsupported(String key) { return new ApiException(400, "unsupported_parameter", "The selected provider does not support this parameter or input type.", key); }
    private static ApiException invalid(String key, String message) { return new ApiException(400, "invalid_request", message, key); }
}
