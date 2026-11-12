package me.vonyayou.geminibridge.gateway.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import me.vonyayou.geminibridge.gateway.*;
import me.vonyayou.geminibridge.provider.Provider;
import java.util.List;
import java.util.Map;

public class ResponsesFormat implements CompletionFormat {
    public ResponseRequest parse(JsonNode input) { return ResponseRequest.parse(input); }
    public Map<String, Object> reply(ResponseDocument doc, String status, Provider.ProviderResult result, ApiException error) { return doc.document(status, result, error); }
    public void start(ResponseDocument doc, StreamOutput stream) {
        stream.event("response.created", Map.of("response", doc.document("in_progress", null, null)));
        stream.event("response.in_progress", Map.of("response", doc.document("in_progress", null, null)));
        stream.event("response.output_item.added", Map.of("output_index", 0, "item", doc.item("", false)));
        stream.event("response.content_part.added", Map.of("item_id", doc.itemId(), "output_index", 0, "content_index", 0, "part", doc.part("")));
    }
    public void delta(ResponseDocument doc, StreamOutput stream, String text) {
        stream.event("response.output_text.delta", Map.of("item_id", doc.itemId(), "output_index", 0, "content_index", 0, "delta", text, "logprobs", List.of()));
    }
    public void reasoningDelta(ResponseDocument doc, StreamOutput stream, String text) {
        if (!doc.reasoningStarted()) {
            doc.startReasoning();
            stream.event("response.output_item.added", Map.of("output_index", 1, "item", doc.reasoningItem("", false)));
            stream.event("response.reasoning_summary_part.added", Map.of("item_id", doc.reasoningId(), "output_index", 1, "summary_index", 0, "part", doc.summaryPart("")));
        }
        stream.event("response.reasoning_summary_text.delta", Map.of("item_id", doc.reasoningId(), "output_index", 1, "summary_index", 0, "delta", text));
    }
    public void complete(ResponseDocument doc, StreamOutput stream, Provider.ProviderResult result) {
        if (result.reasoning() != null && !result.reasoning().isEmpty()) {
            if (!doc.reasoningStarted()) reasoningDelta(doc, stream, result.reasoning());
            stream.event("response.reasoning_summary_text.done", Map.of("item_id", doc.reasoningId(), "output_index", 1, "summary_index", 0, "text", result.reasoning()));
            stream.event("response.reasoning_summary_part.done", Map.of("item_id", doc.reasoningId(), "output_index", 1, "summary_index", 0, "part", doc.summaryPart(result.reasoning())));
            stream.event("response.output_item.done", Map.of("output_index", 1, "item", doc.reasoningItem(result.reasoning(), true)));
        }
        stream.event("response.output_text.done", Map.of("item_id", doc.itemId(), "output_index", 0, "content_index", 0, "text", result.text(), "logprobs", List.of()));
        stream.event("response.content_part.done", Map.of("item_id", doc.itemId(), "output_index", 0, "content_index", 0, "part", doc.part(result.text())));
        stream.event("response.output_item.done", Map.of("output_index", 0, "item", doc.item(result.text(), true)));
        stream.event("response.completed", Map.of("response", doc.document("completed", result, null)));
    }
    public void fail(ResponseDocument doc, StreamOutput stream, ApiException error) { stream.event("response.failed", Map.of("response", doc.document("failed", null, error))); }
}
