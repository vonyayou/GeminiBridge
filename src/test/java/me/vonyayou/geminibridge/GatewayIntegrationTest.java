package me.vonyayou.geminibridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.vonyayou.geminibridge.config.*;
import me.vonyayou.geminibridge.history.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite:test_run/integration/gateway.db?busy_timeout=10000&journal_mode=WAL", "bridge.data-dir=test_run/integration", "bridge.sse-heartbeat-ms=100"})
@AutoConfigureMockMvc
class GatewayIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired HistoryRepository repository;
    @Autowired HistoryService history;
    @Autowired SettingsService settings;
    String apiKey;

    @BeforeEach void setup() throws Exception {
        repository.deleteAllInBatch();
        settings.update(new Settings("127.0.0.1", 8080, true, false, false, 30, "dark", false));
        apiKey = "sk-local-test-client";
    }
    private MvcResult response(String body) throws Exception {
        return mvc.perform(post("/v1/responses").header("Authorization", "Bearer " + apiKey).contentType("application/json").content(body)).andReturn();
    }
    @Test void ordinaryResponsePersistsMetadataWithoutBodies() throws Exception {
        var result = response("{\"model\":\"mock-echo\",\"input\":\"Hello\"}");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode output = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(output.path("object").asText()).isEqualTo("response");
        assertThat(output.path("status").asText()).isEqualTo("completed");
        assertThat(output.path("model").asText()).isEqualTo("mock-echo");
        assertThat(output.path("output").get(0).path("content").get(0).path("text").asText()).contains("Hello", "Mock Provider");
        assertThat(output.hasNonNull("usage")).isFalse();
        var record = repository.findAll().getFirst();
        assertThat(record.resolvedModel).isEqualTo("mock-echo"); assertThat(record.httpStatus).isEqualTo(200);
        assertThat(record.client).startsWith("client-"); assertThat(record.requestBody).isNull(); assertThat(record.responseBody).isNull();
    }
    @Test void streamHasOrderedResponsesEventsAndMatchingOutput() throws Exception {
        var initial = response("{\"model\":\"mock-echo\",\"input\":\"Привет 👋\",\"stream\":true}");
        assertThat(initial.getRequest().isAsyncStarted()).isTrue();
        initial.getAsyncResult(10_000);
        var result = mvc.perform(asyncDispatch(initial)).andExpect(status().isOk()).andReturn();
        var stream = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var events = stream.lines().filter(line -> line.startsWith("data:")).map(line -> {
            try { return mapper.readTree(line.substring(5)); } catch (Exception e) { throw new RuntimeException(e); }
        }).toList();
        assertThat(events.getFirst().path("type").asText()).isEqualTo("response.created");
        assertThat(events.getLast().path("type").asText()).isEqualTo("response.completed");
        for (int i = 0; i < events.size(); i++) assertThat(events.get(i).path("sequence_number").asInt()).isEqualTo(i);
        assertThat(events.stream().map(e -> e.path("type").asText()).toList()).containsSubsequence("response.output_item.added", "response.content_part.added", "response.output_text.delta", "response.output_text.done", "response.content_part.done", "response.output_item.done", "response.completed");
        String deltas = events.stream().filter(e -> e.path("type").asText().equals("response.output_text.delta")).map(e -> e.path("delta").asText()).collect(java.util.stream.Collectors.joining());
        assertThat(deltas).contains("Привет 👋");
        assertThat(events.getLast().path("response").path("output").get(0).path("content").get(0).path("text").asText()).isEqualTo(deltas);
        assertThat(repository.findAll().getFirst().streaming).isTrue();
    }
    @Test void heartbeatKeepsTheStreamActiveWithoutChangingResponsesEventNumbers() throws Exception {
        String input = "keep-alive ".repeat(100);
        var initial = response(mapper.writeValueAsString(Map.of("model", "mock-echo", "input", input, "stream", true)));
        initial.getAsyncResult(10_000);
        var result = mvc.perform(asyncDispatch(initial)).andExpect(status().isOk()).andReturn();
        String stream = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(stream).contains(":keep-alive");
        var events = stream.lines().filter(line -> line.startsWith("data:")).map(line -> {
            try { return mapper.readTree(line.substring(5)); } catch (Exception e) { throw new RuntimeException(e); }
        }).toList();
        for (int i = 0; i < events.size(); i++) assertThat(events.get(i).path("sequence_number").asInt()).isEqualTo(i);
        assertThat(events.getLast().path("type").asText()).isEqualTo("response.completed");
    }
    @Test void nonStreamingPlaygroundRequestsCanBeExplicitlyCancelled() throws Exception {
        String cancellationId = "pg_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String input = "cancellable request ".repeat(1000);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var request = executor.submit(() -> mvc.perform(post("/api/playground/responses")
                    .header("X-Bridge-Request-Id", cancellationId).contentType("application/json")
                    .content(mapper.writeValueAsString(Map.of("model", "mock-echo", "input", input, "stream", false)))).andReturn());
            boolean cancelled = false;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (!cancelled && System.nanoTime() < deadline) {
                var result = mvc.perform(post("/api/playground/requests/" + cancellationId + "/cancel")).andExpect(status().isOk()).andReturn();
                cancelled = mapper.readTree(result.getResponse().getContentAsString()).path("cancelled").asBoolean();
                if (!cancelled) Thread.sleep(10);
            }
            assertThat(cancelled).isTrue();
            assertThat(request.get(3, java.util.concurrent.TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(499);
        }
        var row = repository.findAll().getFirst();
        assertThat(row.httpStatus).isEqualTo(499); assertThat(row.streaming).isFalse();
    }
    @Test void unknownModelsAndUnsupportedOptionsAreRecorded() throws Exception {
        assertThat(response("{\"model\":\"gemini-web-pro\",\"input\":\"hello\"}").getResponse().getStatus()).isEqualTo(404);
        var result = response("{\"model\":\"mock-echo\",\"input\":\"hello\",\"temperature\":0.5}");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("error").path("code").asText()).isEqualTo("unsupported_parameter");
        assertThat(repository.count()).isEqualTo(2);
        assertThat(repository.findAll()).allMatch(r -> r.resolvedModel == null);
    }
    @Test void requestsWithoutKeysWorkAndMalformedJsonIsRecorded() throws Exception {
        mvc.perform(post("/v1/responses").contentType("application/json").content("{\"model\":\"mock-echo\",\"input\":\"hello\"}")).andExpect(status().isOk());
        assertThat(response("{invalid").getResponse().getStatus()).isEqualTo(400);
        assertThat(repository.count()).isEqualTo(2);
    }
    @Test void managementOpensDirectlyAndCreatesNoKeyFilesOrLoginSession() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isOk()).andExpect(cookie().doesNotExist("JSESSIONID"));
        mvc.perform(get("/api/settings")).andExpect(status().isOk()).andExpect(jsonPath("$.authenticationRequired").value(false));
        mvc.perform(get("/api/auth/profiles")).andExpect(status().isOk());
        assertThat(Files.exists(Path.of("test_run/integration/admin.key"))).isFalse();
        assertThat(Files.exists(Path.of("test_run/integration/api.key"))).isFalse();
        mvc.perform(post("/api/auth/start")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("browser_profile_required"));
    }
    @Test void removedKeyAndLoginEndpointsAreNotAdvertised() throws Exception {
        mvc.perform(get("/api/session")).andExpect(status().isNotFound());
        mvc.perform(post("/api/settings/api-key/rotate")).andExpect(status().isNotFound());
    }
    @Test void bodiesAreOptInAndSecretsAreRemoved() throws Exception {
        settings.update(new Settings("127.0.0.1", 8080, true, true, true, 30, "dark", false));
        response(mapper.writeValueAsString(Map.of("model", "mock-echo", "input", "Bearer hidden-token " + apiKey)));
        var record = repository.findAll().getFirst();
        assertThat(record.requestBody).contains("[REDACTED]").doesNotContain(apiKey, "hidden-token");
        assertThat(record.responseBody).contains("[REDACTED]").doesNotContain(apiKey, "hidden-token");
    }
    @Test void contextCanContinueButStoreFalseDoesNotRetainIt() throws Exception {
        var first = mapper.readTree(response("{\"model\":\"mock-echo\",\"input\":\"first\"}").getResponse().getContentAsString());
        var next = response(mapper.writeValueAsString(Map.of("model", "mock-echo", "input", "second", "previous_response_id", first.path("id").asText())));
        assertThat(next.getResponse().getContentAsString()).contains("Received 3 message(s)");
        var unstored = mapper.readTree(response("{\"model\":\"mock-echo\",\"input\":\"first\",\"store\":false}").getResponse().getContentAsString());
        assertThat(response(mapper.writeValueAsString(Map.of("model", "mock-echo", "input", "second", "previous_response_id", unstored.path("id").asText()))).getResponse().getStatus()).isEqualTo(404);
    }
    @Test void concurrentRequestsHaveUniqueHistoryRecords() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 6).mapToObj(i -> executor.submit(() -> response("{\"model\":\"mock-echo\",\"input\":\"parallel\"}").getResponse().getStatus())).toList();
            for (var task : tasks) assertThat(task.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(repository.count()).isEqualTo(6);
        assertThat(repository.findAll().stream().map(r -> r.id).distinct().count()).isEqualTo(6);
    }
    @Test void historyFiltersPaginationRetentionAndDeletionUseSqlite() throws Exception {
        response("{\"model\":\"mock-echo\",\"input\":\"hello\"}");
        response("{\"model\":\"missing\",\"input\":\"hello\"}");
        var old = new RequestRecord("req_old", Instant.now().minusSeconds(40L * 86400)); old.httpStatus = 200;
        repository.saveAndFlush(old);
        history.retain(); assertThat(repository.existsById("req_old")).isFalse();
        mvc.perform(get("/api/history").param("status", "error").param("size", "1")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].requestedModel").value("missing"));
        mvc.perform(get("/api/history").param("search", "%")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/history").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/history")).andExpect(status().isOk());
        assertThat(repository.count()).isZero();
    }
    @Test void arbitraryApiKeysAreAcceptedAndCorsIsStillEnforced() throws Exception {
        mvc.perform(put("/api/settings").header("Origin", "https://attacker.example").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        for (String key : List.of("x", "local", "another-client")) {
            mvc.perform(get("/v1/models").header("Authorization", "Bearer " + key)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("mock-echo"));
        }
        mvc.perform(get("/v1/models")).andExpect(status().isOk());
    }
}
