package me.vonyayou.geminibridge.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.vonyayou.geminibridge.auth.SessionSecrets;
import me.vonyayou.geminibridge.config.*;
import me.vonyayou.geminibridge.history.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistoryServiceTest {
    @TempDir Path directory;
    @Test void defaultsDoNotStoreBodiesAndHeadersAreRedacted() {
        var repository = mock(HistoryRepository.class); var settings = mock(SettingsService.class);
        when(settings.get()).thenReturn(new Settings("127.0.0.1", 8080, true, false, false, 30, "dark", false));
        var history = new HistoryService(repository, settings, new Redactor(new ObjectMapper(), new SessionSecrets()));
        var request = new MockHttpServletRequest("POST", "/v1/responses");
        request.addHeader("Authorization", "Bearer abc123"); request.addHeader("Cookie", "secret=value"); request.addHeader("X-API-Key", "private");
        history.record(history.capture("req_1", request), "mock-echo", "mock-echo", "mock", Map.of("input", "private"), Map.of("output", "private"), false, 200, null, null, null);
        var captor = ArgumentCaptor.forClass(RequestRecord.class); verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().requestBody).isNull(); assertThat(captor.getValue().responseBody).isNull();
        assertThat(captor.getValue().requestHeaders).doesNotContain("abc123", "secret=value", "private").contains("[REDACTED]");
    }
    @Test void disablingHistoryWhileRequestRunsPreventsStorage() {
        var repository = mock(HistoryRepository.class); var settings = mock(SettingsService.class);
        var enabled = new Settings("127.0.0.1", 8080, true, true, true, 30, "dark", false);
        var disabled = new Settings("127.0.0.1", 8080, false, false, false, 30, "dark", false);
        when(settings.get()).thenReturn(enabled, disabled);
        var history = new HistoryService(repository, settings, new Redactor(new ObjectMapper(), new SessionSecrets()));
        var capture = history.capture("req_1", new MockHttpServletRequest());
        history.record(capture, "mock-echo", "mock-echo", "mock", Map.of(), Map.of(), true, 200, null, null, null);
        verifyNoInteractions(repository);
    }
}
