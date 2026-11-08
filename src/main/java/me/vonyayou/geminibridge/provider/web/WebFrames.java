package me.vonyayou.geminibridge.provider.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.vonyayou.geminibridge.gateway.ApiException;
import java.io.BufferedReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class WebFrames {
    private final ObjectMapper mapper;
    public WebFrames(ObjectMapper mapper) { this.mapper = mapper; }
    public void read(Reader source, BooleanSupplier cancelled, Consumer<JsonNode> frame) throws Exception {
        var reader = new BufferedReader(source);
        String line;
        long size = 0;
        while ((line = reader.readLine()) != null) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            size += line.getBytes(StandardCharsets.UTF_8).length;
            if (size > 16_777_216) throw new ApiException(502, "gemini_response_too_large", "Gemini Web returned more than 16 MiB of stream data.");
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.equals(")]}'") || trimmed.matches("\\d+")) continue;
            if (!trimmed.startsWith("[")) throw new ApiException(502, "gemini_protocol_changed", "Gemini Web returned an unexpected response format.");
            JsonNode parts;
            try { parts = mapper.readTree(trimmed); }
            catch (Exception e) { throw new ApiException(502, "gemini_protocol_changed", "Gemini Web returned malformed stream data."); }
            if (!parts.isArray()) throw new ApiException(502, "gemini_protocol_changed", "Gemini Web returned an unexpected response format.");
            for (JsonNode part : parts) if (part.isArray()) frame.accept(part);
        }
    }
    public static JsonNode at(JsonNode node, int... path) {
        for (int index : path) {
            if (node == null || !node.isArray() || index >= node.size()) return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
            node = node.get(index);
        }
        return node == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : node;
    }
}
