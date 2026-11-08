package me.vonyayou.geminibridge.history;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/history")
public class HistoryController {
    private final HistoryService history;
    public HistoryController(HistoryService history) { this.history = history; }
    @GetMapping Map<String, Object> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "15") int size,
            @RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "") String model,
            @RequestParam(defaultValue = "all") String status, @RequestParam(defaultValue = "desc") String direction) {
        return history.list(page, size, search, model, status, direction);
    }
    @GetMapping("/{id}") RequestRecord detail(@PathVariable String id) { return history.detail(id); }
    @DeleteMapping Map<String, Boolean> clear() { history.clear(); return Map.of("cleared", true); }
}
