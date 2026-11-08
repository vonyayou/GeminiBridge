package me.vonyayou.geminibridge.gateway.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import me.vonyayou.geminibridge.gateway.*;
import me.vonyayou.geminibridge.provider.Provider;
import java.util.Map;

public interface CompletionFormat {
    ResponseRequest parse(JsonNode input);
    Map<String, Object> reply(ResponseDocument document, String status, Provider.ProviderResult result, ApiException error);
    void start(ResponseDocument document, StreamOutput stream);
    void delta(ResponseDocument document, StreamOutput stream, String delta);
    void complete(ResponseDocument document, StreamOutput stream, Provider.ProviderResult result);
    void fail(ResponseDocument document, StreamOutput stream, ApiException error);
}
