package me.vonyayou.geminibridge.gateway.protocol;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public class StreamOutput {
    private final SseEmitter emitter;
    private final BooleanSupplier cancelled;
    private final Runnable onDisconnect;
    private int sequence;
    public StreamOutput(SseEmitter emitter, BooleanSupplier cancelled, Runnable onDisconnect) {
        this.emitter = emitter; this.cancelled = cancelled; this.onDisconnect = onDisconnect;
    }
    public void event(String type, Map<String, Object> fields) {
        var event = new LinkedHashMap<String, Object>();
        event.put("type", type); event.put("sequence_number", sequence++); event.putAll(fields);
        send(SseEmitter.event().name(type).data(event));
    }
    public void data(Object data) { send(SseEmitter.event().data(data)); }
    private void send(SseEmitter.SseEventBuilder event) {
        if (cancelled.getAsBoolean()) throw new CancellationException();
        try { emitter.send(event); }
        catch (Exception e) { onDisconnect.run(); throw new CancellationException(); }
    }
}
