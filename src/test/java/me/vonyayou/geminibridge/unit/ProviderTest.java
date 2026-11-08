package me.vonyayou.geminibridge.unit;

import me.vonyayou.geminibridge.provider.*;
import me.vonyayou.geminibridge.gateway.ApiException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CancellationException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProviderTest {
    @Test void mockIsExplicitAndDoesNotEstimateTokens() {
        var mock = new MockProvider(true);
        var deltas = new StringBuilder();
        var result = mock.generate(new Provider.ProviderRequest("mock-echo", "Test", List.of(new Provider.Message("user", "Привет 👋"))), deltas::append, () -> false);
        assertThat(result.text()).isEqualTo(deltas.toString()).contains("Mock Provider", "Привет 👋", "No Gemini request");
        assertThat(result.inputTokens()).isNull(); assertThat(result.outputTokens()).isNull();
        assertThat(mock.status().lastSuccessfulRequest()).isNotNull();
        assertThat(mock.discover().getFirst().mock()).isTrue();
    }
    @Test void cancellationStopsGeneration() {
        var mock = new MockProvider(true);
        assertThatThrownBy(() -> mock.generate(new Provider.ProviderRequest("mock-echo", null, List.of(new Provider.Message("user", "hi"))), ignored -> {}, () -> true)).isInstanceOf(CancellationException.class);
        assertThat(mock.status().lastSuccessfulRequest()).isNull();
    }
    @Test void disabledMockHasNoModels() { assertThat(new MockProvider(false).discover()).isEmpty(); }
    @Test void geminiWebRequiresBrowserSessionAndHasNoFallback() {
        var session = mock(me.vonyayou.geminibridge.auth.browser.GeminiWebSession.class);
        when(session.connection()).thenReturn("disconnected");
        when(session.status()).thenReturn(java.util.Map.of("state", "disconnected"));
        when(session.requireAuthenticated()).thenThrow(new ApiException(401, "gemini_reauthentication_required", "Connect the browser session."));
        var web = new GeminiWebProvider(session, mock(me.vonyayou.geminibridge.provider.web.GeminiWebClient.class));
        var registry = new ModelRegistry(List.of(new MockProvider(true), web));
        assertThat(web.discover()).isEmpty();
        assertThat(web.check().connection()).isEqualTo("disconnected");
        assertThatThrownBy(() -> registry.model("gemini-3-pro")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("model_not_found"));
        assertThatThrownBy(() -> web.generate(null, ignored -> {}, () -> false)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(401));
    }
}
