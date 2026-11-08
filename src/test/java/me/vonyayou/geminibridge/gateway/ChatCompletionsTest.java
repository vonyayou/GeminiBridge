package me.vonyayou.geminibridge.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.vonyayou.geminibridge.history.HistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite:test_run/chat-completions/gateway.db?busy_timeout=10000&journal_mode=WAL", "bridge.data-dir=test_run/chat-completions", "bridge.sse-heartbeat-ms=100"})
@AutoConfigureMockMvc
class ChatCompletionsTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired HistoryRepository history;
    @BeforeEach void clear() { history.deleteAllInBatch(); }
    @Test void chatRequestsUseTheSharedGatewayAndReturnChatCompletionEnvelopes() throws Exception {
        var request = Map.of("model", "mock-echo", "messages", List.of(Map.of("role", "system", "content", "Be brief"), Map.of("role", "user", "content", "Hello")));
        var result = mvc.perform(post("/v1/chat/completions").contentType("application/json").content(mapper.writeValueAsString(request))).andExpect(status().isOk()).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("id").asText()).startsWith("chatcmpl-"); assertThat(body.path("object").asText()).isEqualTo("chat.completion");
        assertThat(body.path("model").asText()).isEqualTo("mock-echo");
        assertThat(body.at("/choices/0/message/role").asText()).isEqualTo("assistant");
        assertThat(body.at("/choices/0/message/content").asText()).contains("Hello", "Mock Provider");
        assertThat(body.at("/choices/0/finish_reason").asText()).isEqualTo("stop");
        assertThat(body.hasNonNull("usage")).isFalse();
        var record = history.findAll().getFirst(); assertThat(record.httpStatus).isEqualTo(200); assertThat(record.requestedModel).isEqualTo("mock-echo");
    }
    @Test void streamsRoleTextCompletionAndDoneWithOneStableId() throws Exception {
        String text = "Привет 👋 ".repeat(100);
        var request = Map.of("model", "mock-echo", "stream", true, "messages", List.of(Map.of("role", "user", "content", text)));
        var initial = mvc.perform(post("/v1/chat/completions").contentType("application/json").content(mapper.writeValueAsString(request))).andExpect(request().asyncStarted()).andReturn();
        initial.getAsyncResult(10000);
        var result = mvc.perform(asyncDispatch(initial)).andExpect(status().isOk()).andReturn();
        String stream = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var data = stream.lines().filter(line -> line.startsWith("data:")).map(line -> line.substring(5).strip()).toList();
        assertThat(data.getLast()).isEqualTo("[DONE]");
        var chunks = data.stream().filter(line -> !line.equals("[DONE]")).map(line -> {
            try { return mapper.readTree(line); } catch (Exception e) { throw new RuntimeException(e); }
        }).toList();
        assertThat(chunks.getFirst().at("/choices/0/delta/role").asText()).isEqualTo("assistant");
        assertThat(chunks.getLast().at("/choices/0/finish_reason").asText()).isEqualTo("stop");
        assertThat(chunks.stream().map(chunk -> chunk.path("id").asText()).distinct().count()).isEqualTo(1);
        assertThat(chunks).allMatch(chunk -> chunk.path("object").asText().equals("chat.completion.chunk"));
        String deltas = chunks.stream().map(chunk -> chunk.at("/choices/0/delta/content").asText("")).collect(java.util.stream.Collectors.joining());
        assertThat(deltas).contains(text); assertThat(stream).contains(":keep-alive");
        assertThat(history.findAll().getFirst().streaming).isTrue();
    }
    @ParameterizedTest
    @ValueSource(strings = {"temperature", "max_completion_tokens", "max_tokens", "response_format", "reasoning_effort"})
    void unsupportedControlsReturnNegotiableErrors(String field) throws Exception {
        var request = new LinkedHashMap<String, Object>(); request.put("model", "mock-echo"); request.put("messages", List.of(Map.of("role", "user", "content", "Hello")));
        request.put(field, field.equals("response_format") ? Map.of("type", "json_schema", "json_schema", Map.of("name", "test", "schema", Map.of("type", "object"))) : 1);
        var result = mvc.perform(post("/v1/chat/completions").contentType("application/json").content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("unsupported_parameter")).andExpect(jsonPath("$.error.param").value(field)).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("does not support", field);
        assertThat(history.findAll().getFirst().httpStatus).isEqualTo(400);
    }
    @Test void unknownModelsAndMalformedJsonRemainStructuredErrors() throws Exception {
        mvc.perform(post("/v1/chat/completions").contentType("application/json").content("{\"model\":\"missing\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("model_not_found"));
        mvc.perform(post("/v1/chat/completions").contentType("application/json").content("{invalid")).andExpect(status().isBadRequest());
        assertThat(history.count()).isEqualTo(2);
    }
    @Test void acceptsChatTextPartsAndRejectsImagesAndTools() throws Exception {
        mvc.perform(post("/v1/chat/completions").contentType("application/json").content("{\"model\":\"mock-echo\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}]}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.choices[0].message.content").exists());
        mvc.perform(post("/v1/chat/completions").contentType("application/json").content("{\"model\":\"mock-echo\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.com/image.png\"}}]}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.param").value("messages.content"));
        mvc.perform(post("/v1/chat/completions").contentType("application/json").content("{\"model\":\"mock-echo\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}],\"tools\":[{\"type\":\"function\"}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.param").value("tools"));
    }
}
