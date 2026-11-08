package me.vonyayou.geminibridge.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class ResponsesController {
    private final ResponsesService responses;
    public ResponsesController(ResponsesService responses) { this.responses = responses; }
    @PostMapping(value = {"/v1/responses", "/api/playground/responses"}, consumes = "application/json")
    Object respond(@RequestBody JsonNode input, HttpServletRequest request, HttpServletResponse response) {
        if (input.path("stream").asBoolean(false)) {
            response.setHeader("X-Accel-Buffering", "no");
            response.setHeader("Cache-Control", "no-cache, no-transform");
        }
        return responses.respond(input, request);
    }
    @PostMapping("/api/playground/requests/{id}/cancel")
    Map<String, Boolean> cancel(@PathVariable String id, HttpServletRequest request) {
        return responses.cancel(id, String.valueOf(request.getAttribute("bridge.client")));
    }
}
