package me.vonyayou.geminibridge.history;

import jakarta.servlet.http.HttpServletRequest;
import me.vonyayou.geminibridge.config.Settings;
import me.vonyayou.geminibridge.config.SettingsService;
import me.vonyayou.geminibridge.gateway.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class HistoryService {
    private final HistoryRepository repository;
    private final SettingsService settings;
    private final Redactor redactor;
    public HistoryService(HistoryRepository repository, SettingsService settings, Redactor redactor) {
        this.repository = repository; this.settings = settings; this.redactor = redactor;
    }
    public record Capture(String id, Instant started, long nanoTime, String client, String headers, Settings policy) {}
    public Capture capture(String id, HttpServletRequest request) {
        var headers = new LinkedHashMap<String, String>();
        for (String name : java.util.List.of("Content-Type", "Accept", "Authorization", "Cookie")) {
            String value = request.getHeader(name);
            if (value != null) headers.put(name, name.equals("Authorization") || name.equals("Cookie") ? "[REDACTED]" : redactor.text(value));
        }
        return new Capture(id, Instant.now(), System.nanoTime(), String.valueOf(request.getAttribute("bridge.client") == null ? "unauthenticated" : request.getAttribute("bridge.client")), redactor.json(headers), settings.get());
    }
    public void record(Capture capture, String requested, String resolved, String provider, Object input, Object output,
                       boolean stream, int status, String error, Integer inputTokens, Integer outputTokens) {
        if (!capture.policy().historyEnabled()) return;
        var current = settings.get();
        if (!current.historyEnabled()) return;
        var row = new RequestRecord(capture.id(), capture.started());
        row.requestedModel = redactor.text(requested);
        row.resolvedModel = resolved; row.provider = provider;
        row.requestHeaders = capture.headers();
        if (capture.policy().saveRequestBodies() && current.saveRequestBodies()) row.requestBody = redactor.json(input);
        if (capture.policy().saveResponseBodies() && current.saveResponseBodies()) row.responseBody = redactor.json(output);
        row.durationMs = Math.max(0, (System.nanoTime() - capture.nanoTime()) / 1_000_000);
        row.httpStatus = status; row.streaming = stream; row.errorMessage = redactor.text(error);
        row.inputTokens = inputTokens; row.outputTokens = outputTokens; row.client = capture.client();
        repository.saveAndFlush(row);
    }
    public void rejected(HttpServletRequest request, int status, String error) {
        if (!java.util.Set.of("/v1/responses", "/v1/chat/completions").contains(request.getRequestURI()) || !request.getMethod().equals("POST")) return;
        record(capture("req_" + java.util.UUID.randomUUID().toString().replace("-", ""), request), null, null, null, null, null, false, status, error, null, null);
    }
    public Map<String, Object> list(int page, int size, String search, String model, String status, String direction) {
        if (page < 0 || size < 1 || size > 100 || search.length() > 256 || model.length() > 128 || !java.util.Set.of("all", "success", "error", "cancelled").contains(status) || !java.util.Set.of("asc", "desc").contains(direction))
            throw new ApiException(400, "invalid_filter", "Invalid history pagination or filter.");
        Specification<RequestRecord> filter = (root, query, cb) -> {
            var terms = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (!model.isBlank()) terms.add(cb.equal(root.get("requestedModel"), model));
            if (!search.isBlank()) {
                String value = "%" + search.toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                terms.add(cb.or(cb.like(cb.lower(root.get("id")), value, '\\'), cb.like(cb.lower(root.get("requestedModel")), value, '\\'), cb.like(cb.lower(root.get("client")), value, '\\')));
            }
            if (status.equals("success")) terms.add(cb.between(root.get("httpStatus"), 200, 299));
            if (status.equals("error")) terms.add(cb.and(cb.ge(root.get("httpStatus"), 400), cb.notEqual(root.get("httpStatus"), 499)));
            if (status.equals("cancelled")) terms.add(cb.equal(root.get("httpStatus"), 499));
            return cb.and(terms.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var result = repository.findAll(filter, PageRequest.of(page, size, Sort.by(Sort.Direction.fromString(direction), "timestamp").and(Sort.by("id"))));
        return Map.of("content", result.getContent().stream().map(this::summary).toList(), "totalElements", result.getTotalElements(), "totalPages", result.getTotalPages(), "page", page, "size", size);
    }
    public Map<String, Object> summary(RequestRecord row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", row.id); result.put("timestamp", row.timestamp); result.put("requestedModel", row.requestedModel);
        result.put("resolvedModel", row.resolvedModel); result.put("provider", row.provider);
        result.put("durationMs", row.durationMs); result.put("httpStatus", row.httpStatus); result.put("streaming", row.streaming);
        result.put("inputTokens", row.inputTokens); result.put("outputTokens", row.outputTokens); result.put("client", row.client); result.put("errorMessage", row.errorMessage);
        return result;
    }
    public RequestRecord detail(String id) { return repository.findById(id).orElseThrow(() -> new ApiException(404, "history_not_found", "This request is no longer in history.")); }
    public void clear() { repository.deleteAllInBatch(); }
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 10_000)
    public void retain() { repository.deleteOlderThan(Instant.now().minus(settings.get().retentionDays(), ChronoUnit.DAYS)); }
}
