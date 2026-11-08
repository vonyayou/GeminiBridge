package me.vonyayou.geminibridge.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.vonyayou.geminibridge.config.Settings;
import me.vonyayou.geminibridge.history.HistoryService;
import me.vonyayou.geminibridge.provider.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResponseAliasTest {
    @Test void legacyIdsResolveToReadableIdsWithoutChangingTheModelOrRequestHistory() throws Exception {
        String alias = "gemini-web-original-id", canonical = "gemini-3.1-pro";
        var provider = mock(Provider.class);
        when(provider.id()).thenReturn("gemini-web");
        when(provider.discover()).thenReturn(List.of(new Model(canonical, "3.1 Pro", "gemini-web", true, false, Set.of("text"), true, Instant.now(), List.of(alias))));
        when(provider.generate(any(), any(), any())).thenAnswer(invocation -> {
            Provider.ProviderRequest request = invocation.getArgument(0);
            assertThat(request.model()).isEqualTo(canonical);
            return new Provider.ProviderResult("Selected exact upstream model", null, null);
        });
        var history = mock(HistoryService.class);
        var capture = new HistoryService.Capture("req_test", Instant.now(), System.nanoTime(), "local", "{}", new Settings("127.0.0.1", 8080, true, false, false, 30, "dark", false));
        when(history.capture(anyString(), any())).thenReturn(capture);
        var registry = new ModelRegistry(List.of(provider));
        assertThat(registry.model(alias).id()).isEqualTo(canonical);
        var service = new ResponsesService(registry, history, new ConversationStore(), 10000);
        try {
            var input = new ObjectMapper().readTree("{\"model\":\"" + alias + "\",\"input\":\"Hello\",\"store\":false}");
            Object result = service.respond(input, new MockHttpServletRequest("POST", "/v1/responses"));
            assertThat(((Map<?, ?>) result).get("model")).isEqualTo(canonical);
            verify(history).record(eq(capture), eq(alias), eq(canonical), eq("gemini-web"), eq(input), any(), eq(false), eq(200), isNull(), isNull(), isNull());
        } finally { service.shutdown(); }
    }
}
