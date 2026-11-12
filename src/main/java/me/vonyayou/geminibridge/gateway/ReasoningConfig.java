package me.vonyayou.geminibridge.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Gemini Web exposes standard and extended thinking, rather than token budgets. */
public record ReasoningConfig(String effort, String summary) {
    public ReasoningConfig(String effort) { this(effort, null); }
    public boolean extended() { return "high".equals(effort) || "xhigh".equals(effort); }

    public static ReasoningConfig parse(JsonNode root) {
        JsonNode reasoning = root.get("reasoning"), thinking = root.get("thinking");
        if (root.hasNonNull("reasoning") && root.hasNonNull("thinking"))
            throw invalid("thinking", "Use either thinking or reasoning, not both.");
        if (root.hasNonNull("thinking")) {
            if (!thinking.isBoolean()) throw invalid("thinking", "Thinking must be boolean.");
            return new ReasoningConfig(thinking.asBoolean() ? "high" : "low");
        }
        if (!root.hasNonNull("reasoning")) return null;
        if (!reasoning.isObject()) throw invalid("reasoning", "Reasoning must be an object.");
        reasoning.fieldNames().forEachRemaining(key -> {
            if (!Set.of("effort", "summary").contains(key)) throw new ApiException(400, "unsupported_parameter", "Gemini Web does not support this reasoning parameter.", "reasoning." + key);
        });
        var config = parseEffort(reasoning.get("effort"), "reasoning.effort");
        if (!reasoning.hasNonNull("summary")) return config;
        if (!reasoning.get("summary").isTextual()) throw invalid("reasoning.summary", "Reasoning summary must be a string.");
        if (!reasoning.get("summary").asText().equals("auto"))
            throw new ApiException(400, "unsupported_parameter", "Gemini Web returns its own thinking summary. Use summary=auto; summary length cannot be controlled.", "reasoning.summary");
        return new ReasoningConfig(config == null ? null : config.effort(), "auto");
    }

    public static ReasoningConfig parseEffort(JsonNode value, String param) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid(param, "Reasoning effort must be a string.");
        String effort = value.asText();
        if (!Set.of("low", "medium", "high", "xhigh").contains(effort))
            throw new ApiException(400, "unsupported_parameter", "Gemini Web supports standard thinking (low/medium) and extended thinking (high/xhigh). It cannot disable a model's native reasoning or set a token budget.", param);
        return new ReasoningConfig(effort);
    }

    private static ApiException invalid(String param, String message) { return new ApiException(400, "invalid_request", message, param); }
}
