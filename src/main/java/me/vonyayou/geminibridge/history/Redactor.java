package me.vonyayou.geminibridge.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.vonyayou.geminibridge.auth.SessionSecrets;
import org.springframework.stereotype.Component;
import java.util.regex.Pattern;

@Component
public class Redactor {
    private static final Pattern SECRET_FIELD = Pattern.compile("(?i).*(authorization|cookie|api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|credential).*" );
    private static final Pattern SECRET_TEXT = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+|\\b(sk-[A-Za-z0-9_-]{8,}|AIza[A-Za-z0-9_-]{20,})\\b");
    private final ObjectMapper mapper;
    private final SessionSecrets credentials;
    public Redactor(ObjectMapper mapper, SessionSecrets credentials) { this.mapper = mapper; this.credentials = credentials; }
    public String json(Object value) {
        JsonNode node = mapper.valueToTree(value);
        scrub(node);
        try { return mapper.writeValueAsString(node); } catch (Exception e) { return null; }
    }
    public String text(String value) {
        if (value == null) return null;
        return credentials.redact(SECRET_TEXT.matcher(value).replaceAll("[REDACTED]"));
    }
    private void scrub(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            var names = new java.util.ArrayList<String>();
            object.fieldNames().forEachRemaining(names::add);
            for (String key : names) {
                JsonNode value = object.get(key);
                if (SECRET_FIELD.matcher(key).matches()) object.put(key, "[REDACTED]");
                else if (value.isTextual()) object.put(key, text(value.asText()));
                else scrub(value);
            }
        } else if (node.isArray()) {
            var array = (com.fasterxml.jackson.databind.node.ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isTextual()) array.set(i, mapper.getNodeFactory().textNode(text(array.get(i).asText())));
                else scrub(array.get(i));
            }
        }
    }
}
