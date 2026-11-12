package me.vonyayou.geminibridge.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import me.vonyayou.geminibridge.history.HistoryService;
import me.vonyayou.geminibridge.provider.ModelRegistry;
import me.vonyayou.geminibridge.provider.Provider;
import me.vonyayou.geminibridge.gateway.protocol.*;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ResponsesService {
    private final ModelRegistry registry;
    private final HistoryService history;
    private final ConversationStore conversations;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> Thread.ofPlatform().daemon().name("bridge-sse-heartbeat").unstarted(runnable));
    private final long heartbeatMs;
    private final Semaphore slots = new Semaphore(16);
    private final ConcurrentHashMap<String, LiveRequest> live = new ConcurrentHashMap<>();
    private record PendingCancellation(String owner, long expiresAt) {}
    private final ConcurrentHashMap<String, PendingCancellation> pendingCancellations = new ConcurrentHashMap<>();

    private static final class LiveRequest {
        final String owner;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile Thread worker;
        LiveRequest(String owner) { this.owner = owner; }
        void cancel() { cancelled.set(true); if (worker != null) worker.interrupt(); }
    }
    public ResponsesService(ModelRegistry registry, HistoryService history, ConversationStore conversations,
            @org.springframework.beans.factory.annotation.Value("${bridge.sse-heartbeat-ms:10000}") long heartbeatMs) {
        this.registry = registry; this.history = history; this.conversations = conversations;
        if (heartbeatMs < 100) throw new IllegalArgumentException("SSE heartbeat interval must be at least 100 ms");
        this.heartbeatMs = heartbeatMs;
    }
    public Object respond(JsonNode input, HttpServletRequest http) { return respond(input, http, new ResponsesFormat()); }
    public Object chatCompletions(JsonNode input, HttpServletRequest http) { return respond(input, http, new ChatCompletionsFormat()); }
    private Object respond(JsonNode input, HttpServletRequest http, CompletionFormat format) {
        String requestId = "req_" + java.util.UUID.randomUUID().toString().replace("-", "");
        var capture = history.capture(requestId, http);
        String requestedModel = input != null && input.path("model").isTextual() ? input.path("model").asText() : null;
        ResponseRequest request;
        Provider provider;
        java.util.List<Provider.Message> messages;
        try {
            var parsed = format.parse(input);
            var model = registry.model(parsed.model());
            request = parsed.withModel(model.id());
            provider = registry.provider(model.provider());
            messages = new ArrayList<>(conversations.previous(request.previousResponseId(), capture.client()));
            messages.addAll(request.messages());
            if (messages.size() > 512 || messages.stream().mapToInt(m -> m.content().length()).sum() > 131_072)
                throw new ApiException(400, "context_too_large", "Conversation context exceeds the provider limit.");
            if (!slots.tryAcquire()) throw new ApiException(429, "gateway_busy", "The gateway already has 16 active requests. Try again shortly.");
        } catch (ApiException e) {
            String requested = input != null && input.path("model").isTextual() ? input.path("model").asText() : null;
            history.record(capture, requested, null, null, input, Errors.body(e), input != null && input.path("stream").asBoolean(false), e.status(), e.getMessage(), null, null);
            throw e;
        }
        var document = new ResponseDocument(request);
        var providerRequest = new Provider.ProviderRequest(request.model(), request.instructions(), messages, request.stream(), request.reasoning());
        if (!request.stream()) {
            var active = new LiveRequest(capture.client());
            active.worker = Thread.currentThread();
            String cancellationId = http.getRequestURI().equals("/api/playground/responses") ? http.getHeader("X-Bridge-Request-Id") : null;
            if (cancellationId == null) cancellationId = document.id();
            try {
                if (!cancellationId.equals(document.id()) && !cancellationId.matches("pg_[0-9a-f]{32}")) throw new ApiException(400, "invalid_cancellation_id", "Invalid Playground request identifier.");
                synchronized (live) {
                    pendingCancellations.values().removeIf(value -> value.expiresAt() < System.currentTimeMillis());
                    if (live.putIfAbsent(cancellationId, active) != null) throw new ApiException(409, "request_already_active", "This Playground request identifier is already active.");
                    var pending = pendingCancellations.remove(cancellationId);
                    if (pending != null && pending.owner().equals(capture.client())) active.cancel();
                }
                var result = provider.generate(providerRequest, ignored -> {}, ignored -> {}, () -> active.cancelled.get() || Thread.currentThread().isInterrupted());
                var response = format.reply(document, "completed", result, null);
                if (request.store()) conversations.save(document.id(), capture.client(), messages, result.text());
                history.record(capture, requestedModel, request.model(), provider.id(), input, response, false, 200, null, result.inputTokens(), result.outputTokens());
                return response;
            } catch (CancellationException e) {
                Thread.interrupted();
                var failure = new ApiException(499, "request_cancelled", "The client cancelled the request.");
                history.record(capture, requestedModel, request.model(), provider.id(), input, Errors.body(failure), false, 499, failure.getMessage(), null, null);
                throw failure;
            } catch (ApiException e) {
                history.record(capture, requestedModel, request.model(), provider.id(), input, Errors.body(e), false, e.status(), e.getMessage(), null, null);
                throw e;
            } catch (Exception e) {
                var failure = new ApiException(500, "provider_error", "The provider could not complete the request.");
                history.record(capture, requestedModel, request.model(), provider.id(), input, Errors.body(failure), false, 500, failure.getMessage(), null, null);
                throw failure;
            } finally { live.remove(cancellationId, active); Thread.interrupted(); slots.release(); }
        }

        var emitter = new SseEmitter(provider.timeoutMillis());
        var active = new LiveRequest(capture.client());
        live.put(document.id(), active);
        emitter.onTimeout(active::cancel);
        emitter.onError(error -> active.cancel());
        emitter.onCompletion(() -> active.cancelled.set(true));
        executor.submit(() -> {
            active.worker = Thread.currentThread();
            var stream = new StreamOutput(emitter, active.cancelled::get, active::cancel);
            Provider.ProviderResult result = null;
            ApiException error = null;
            int status = 200;
            var heartbeat = heartbeats.scheduleAtFixedRate(() -> {
                if (active.cancelled.get()) return;
                try { emitter.send(SseEmitter.event().comment("keep-alive")); }
                catch (Exception e) { active.cancel(); }
            }, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
            try {
                format.start(document, stream);
                result = provider.generate(providerRequest, delta -> format.delta(document, stream, delta), delta -> format.reasoningDelta(document, stream, delta), active.cancelled::get);
                if (request.store()) conversations.save(document.id(), capture.client(), messages, result.text());
                format.complete(document, stream, result);
            } catch (CancellationException e) {
                status = 499; error = new ApiException(499, "request_cancelled", "The client cancelled or disconnected.");
            } catch (Exception e) {
                status = e instanceof ApiException a ? a.status() : 500;
                error = e instanceof ApiException a ? a : new ApiException(500, "provider_error", "The provider could not complete the request.");
                try { format.fail(document, stream, error); } catch (Exception ignored) {}
            } finally {
                heartbeat.cancel(false);
                Thread.interrupted();
                try {
                    history.record(capture, requestedModel, request.model(), provider.id(), input, format.reply(document, error == null ? "completed" : "failed", result, error), true, status, error == null ? null : error.getMessage(), result == null ? null : result.inputTokens(), result == null ? null : result.outputTokens());
                } finally {
                    live.remove(document.id()); slots.release(); emitter.complete();
                }
            }
        });
        return emitter;
    }
    public Map<String, Boolean> cancel(String id, String owner) {
        synchronized (live) {
            var active = live.get(id);
            if (active == null) {
                if (!id.matches("pg_[0-9a-f]{32}")) return Map.of("cancelled", false);
                pendingCancellations.values().removeIf(value -> value.expiresAt() < System.currentTimeMillis());
                if (pendingCancellations.size() >= 256) throw new ApiException(429, "too_many_cancellations", "Too many pending cancellation requests. Try again shortly.");
                pendingCancellations.put(id, new PendingCancellation(owner, System.currentTimeMillis() + 30_000));
                return Map.of("cancelled", true);
            }
            if (!active.owner.equals(owner)) throw new ApiException(404, "request_not_found", "The request does not exist.");
            active.cancel();
            return Map.of("cancelled", true);
        }
    }
    @PreDestroy void shutdown() { live.values().forEach(LiveRequest::cancel); heartbeats.shutdownNow(); executor.close(); }
}
