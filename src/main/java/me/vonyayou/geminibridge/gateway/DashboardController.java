package me.vonyayou.geminibridge.gateway;

import me.vonyayou.geminibridge.config.SettingsService;
import me.vonyayou.geminibridge.history.HistoryRepository;
import me.vonyayou.geminibridge.history.HistoryService;
import me.vonyayou.geminibridge.provider.ModelRegistry;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class DashboardController {
    private final HistoryRepository repository;
    private final HistoryService history;
    private final ModelRegistry registry;
    private final SettingsService settings;
    public DashboardController(HistoryRepository repository, HistoryService history, ModelRegistry registry, SettingsService settings) {
        this.repository = repository; this.history = history; this.registry = registry; this.settings = settings;
    }
    @GetMapping("/api/dashboard") Map<String, Object> dashboard() {
        long total = repository.count();
        var result = new LinkedHashMap<String, Object>();
        result.put("totalRequests", total);
        result.put("requestsToday", repository.countByTimestampGreaterThanEqual(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()));
        result.put("averageLatencyMs", repository.averageLatency());
        result.put("successRate", total == 0 ? null : 100.0 * repository.countByHttpStatusBetween(200, 299) / total);
        result.put("availableModels", registry.models().stream().filter(m -> m.available()).count());
        result.put("providers", registry.statuses());
        result.put("recentActivity", repository.findTop8ByOrderByTimestampDesc().stream().map(history::summary).toList());
        result.put("baseUrl", settings.baseUrl()); result.put("historyEnabled", settings.get().historyEnabled());
        return result;
    }
}
