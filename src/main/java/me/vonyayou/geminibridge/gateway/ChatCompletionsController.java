package me.vonyayou.geminibridge.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
public class ChatCompletionsController {
    private final ResponsesService gateway;
    public ChatCompletionsController(ResponsesService gateway) { this.gateway = gateway; }
    @PostMapping(value = "/v1/chat/completions", consumes = "application/json")
    Object complete(@RequestBody JsonNode input, HttpServletRequest request, HttpServletResponse response) {
        if (input.path("stream").asBoolean(false)) {
            response.setHeader("X-Accel-Buffering", "no"); response.setHeader("Cache-Control", "no-cache, no-transform");
        }
        return gateway.chatCompletions(input, request);
    }
}
