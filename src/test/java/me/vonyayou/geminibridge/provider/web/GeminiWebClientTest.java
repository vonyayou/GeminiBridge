package me.vonyayou.geminibridge.provider.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.vonyayou.geminibridge.auth.browser.BrowserCookieReader;
import me.vonyayou.geminibridge.gateway.ApiException;
import me.vonyayou.geminibridge.provider.Provider;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class GeminiWebClientTest {
    MockWebServer server;
    final ObjectMapper mapper = new ObjectMapper();
    GeminiWebClient client;
    @BeforeEach void start() throws Exception { server = new MockWebServer(); server.start(); client = new GeminiWebClient(mapper, new OkHttpClient(), server.url("/").toString()); }
    @AfterEach void stop() throws Exception { server.shutdown(); }
    private WebSessionData session() { return new WebSessionData(Map.of("__Secure-1PSID", "test-session"), "test-csrf", "build", "sid", "en", "request-session", Instant.now().plusSeconds(600)); }
    private List<Object> status(int code) {
        var result = new ArrayList<Object>(Collections.nCopies(18, null)); result.set(14, code); result.set(15, List.of()); result.set(16, List.of()); result.set(17, List.of()); return result;
    }
    private List<Object> model(String id, String name, int number) {
        var model = new ArrayList<Object>(Collections.nCopies(20, null)); model.set(0, id); model.set(11, name); model.set(17, number); return model;
    }
    private String frame(List<Object> data, String method) throws Exception { String json = mapper.writeValueAsString(List.of(Arrays.asList("wrb.fr", method, mapper.writeValueAsString(data)))); return ")]}'\n\n" + json.length() + "\n" + json + "\n"; }
    private List<Object> candidate(String text, boolean done) {
        var candidate = new ArrayList<Object>(Collections.nCopies(9, null)); candidate.set(0, "candidate"); candidate.set(1, List.of(text)); candidate.set(8, List.of(done ? 2 : 1));
        var data = new ArrayList<Object>(Collections.nCopies(5, null)); data.set(4, List.of(candidate)); return data;
    }
    @Test void initializesOnlyAuthenticatedPagesAndDoesNotFollowSignInRedirects() throws Exception {
        server.enqueue(new MockResponse().setBody("<script>data={\"SNlM0e\":\"csrf\",\"cfb2h\":\"build\",\"FdrFJe\":\"sid\",\"TuX5cc\":\"en\"}</script>"));
        var data = client.initialize(new BrowserCookieReader.Cookies(Map.of("__Secure-1PSID", "own-session"), Instant.now().plusSeconds(600)));
        assertThat(data.accessToken()).isEqualTo("csrf"); assertThat(data.toString()).doesNotContain("csrf", "own-session");
        assertThat(server.takeRequest().getHeader("Cookie")).isEqualTo("__Secure-1PSID=own-session");
        var noRedirects = new GeminiWebClient(mapper, new OkHttpClient.Builder().followRedirects(false).build(), server.url("/").toString());
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "https://accounts.google.com"));
        assertThatThrownBy(() -> noRedirects.initialize(new BrowserCookieReader.Cookies(Map.of("__Secure-1PSID", "test"), Instant.now().plusSeconds(600))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("gemini_reauthentication_required"));
    }
    @Test void followsOnlySameOriginAppRedirectsAndRetainsTheSelectedAccount() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/u/1/app"));
        server.enqueue(new MockResponse().setBody("<script>data={\"SNlM0e\":\"csrf\",\"TuX5cc\":\"en\"}</script>"));
        var data = client.initialize(new BrowserCookieReader.Cookies(Map.of("__Secure-1PSID", "session-secret"), Instant.now().plusSeconds(600)));
        assertThat(data.appPath()).isEqualTo("/u/1/app"); assertThat(data.accountIndex()).isEqualTo("1");
        assertThat(server.takeRequest().getPath()).isEqualTo("/app");
        assertThat(server.takeRequest().getPath()).isEqualTo("/u/1/app");
        var payload = status(1000); payload.set(15, List.of(model("pro", "3.1 Pro", 3)));
        server.enqueue(new MockResponse().setBody(frame(payload, "otAQ7b")));
        client.discover(data);
        var request = server.takeRequest();
        assertThat(request.getRequestUrl().queryParameter("authuser")).isEqualTo("1");
        assertThat(request.getRequestUrl().queryParameter("source-path")).isEqualTo("/u/1/app");
    }
    @Test void accountEmailComesOnlyFromTheSignedInPageMetadata() {
        server.enqueue(new MockResponse().setBody("{\"SNlM0e\":\"csrf\",\"oPEP7c\":\"person@example.com\"}"));
        var data = client.initialize(new BrowserCookieReader.Cookies(Map.of("__Secure-1PSID", "session-secret"), Instant.now().plusSeconds(600)));
        assertThat(data.accountEmail()).isEqualTo("person@example.com");
        server.enqueue(new MockResponse().setBody("{\"SNlM0e\":\"csrf\",\"oPEP7c\":\"not-an-email\"}"));
        assertThat(client.initialize(new BrowserCookieReader.Cookies(Map.of("__Secure-1PSID", "session-secret"), Instant.now().plusSeconds(600))).accountEmail()).isNull();
    }
    @Test void preservesCookieUpdatesBetweenInitializationDiscoveryAndGeneration() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"SNlM0e\":\"csrf\"}").setHeader("Set-Cookie", "__Secure-1PSIDCC=renewed-session-cookie; Path=/; HttpOnly"));
        var data = client.initialize(new BrowserCookieReader.Cookies(Map.of("__Secure-1PSID", "session-secret"), Instant.now().plusSeconds(600)));
        server.takeRequest();
        var payload = status(1000); payload.set(15, List.of(model("pro", "3.1 Pro", 3)));
        server.enqueue(new MockResponse().setBody(frame(payload, "otAQ7b")).setHeader("Set-Cookie", "__Secure-1PSIDTS=renewed-session-timestamp; Path=/; HttpOnly"));
        var model = client.discover(data).getFirst();
        assertThat(server.takeRequest().getHeader("Cookie")).contains("__Secure-1PSIDCC=renewed-session-cookie");
        server.enqueue(new MockResponse().setBody(frame(candidate("Done", true), "generate")));
        client.generate(data, model, new Provider.ProviderRequest(model.id(), null, List.of(new Provider.Message("user", "Hello"))), ignored -> {}, () -> false);
        assertThat(server.takeRequest().getHeader("Cookie")).contains("__Secure-1PSIDCC=renewed-session-cookie", "__Secure-1PSIDTS=renewed-session-timestamp");
        assertThat(data.cookieJar().toString()).doesNotContain("renewed-session-cookie", "renewed-session-timestamp");
        assertThat(data.cookieJar().loadForRequest(okhttp3.HttpUrl.get("https://accounts.google.com/"))).isEmpty();
    }
    @Test void neverFollowsBrowserChallengesOrLeaksRedirectQueryTokensInErrors() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/sorry/index?token=private-query-token"));
        assertThatThrownBy(() -> client.initialize(new BrowserCookieReader.Cookies(Map.of("__Secure-1PSID", "session-secret"), Instant.now().plusSeconds(600))))
                .isInstanceOfSatisfying(ApiException.class, e -> { assertThat(e.status()).isEqualTo(403); assertThat(e.code()).isEqualTo("gemini_security_check_required"); assertThat(e.param()).isEqualTo("session_page"); assertThat(e.getMessage()).doesNotContain("private-query-token"); });
        server.takeRequest(); assertThat(server.getRequestCount()).isEqualTo(1);
    }
    @Test void carriesBrowserVerificationThroughDiscoveryAndGeneration() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"SNlM0e\":\"csrf\"}"));
        var data = client.initialize(new BrowserCookieReader.Cookies(Map.of("__Secure-1PSID", "session-secret", "GOOGLE_ABUSE_EXEMPTION", "verification-secret"), Instant.now().plusSeconds(600)));
        assertThat(server.takeRequest().getHeader("Cookie")).contains("GOOGLE_ABUSE_EXEMPTION=verification-secret");
        var payload = status(1000); payload.set(15, List.of(model("pro", "3.1 Pro", 3)));
        server.enqueue(new MockResponse().setBody(frame(payload, "otAQ7b")));
        var model = client.discover(data).getFirst();
        assertThat(server.takeRequest().getHeader("Cookie")).contains("GOOGLE_ABUSE_EXEMPTION=verification-secret");
        server.enqueue(new MockResponse().setBody(frame(candidate("Recovered", true), "generate")));
        var result = client.generate(data, model, new Provider.ProviderRequest(model.id(), null, List.of(new Provider.Message("user", "Hello"))), ignored -> {}, () -> false);
        assertThat(result.text()).isEqualTo("Recovered");
        assertThat(server.takeRequest().getHeader("Cookie")).contains("GOOGLE_ABUSE_EXEMPTION=verification-secret");
        assertThat(data.cookieJar().sessionValues()).containsEntry("GOOGLE_ABUSE_EXEMPTION", "verification-secret");
        assertThat(data.cookieJar().secrets()).contains("verification-secret");
        assertThat(data.cookieJar().loadForRequest(okhttp3.HttpUrl.get("https://www.google.com/sorry/index"))).isEmpty();
    }
    @Test void generationSecurityRedirectsAreReportedSeparatelyFromExpiredLogin() {
        var payload = status(1000); payload.set(15, List.of(model("pro", "3.1 Pro", 3)));
        var model = client.parseModels(mapper.valueToTree(payload)).getFirst();
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "https://www.google.com/sorry/index?token=private-token"));
        assertThatThrownBy(() -> client.generate(session(), model, new Provider.ProviderRequest(model.id(), null, List.of(new Provider.Message("user", "Hello"))), ignored -> {}, () -> false))
                .isInstanceOfSatisfying(ApiException.class, e -> { assertThat(e.status()).isEqualTo(403); assertThat(e.code()).isEqualTo("gemini_security_check_required"); assertThat(e.param()).isEqualTo("generation"); assertThat(e.getMessage()).doesNotContain("private-token"); });
        assertThat(server.getRequestCount()).isEqualTo(1);
    }
    @Test void recordsTheExactChallengeUrlWithoutFollowingIt() {
        var url = java.net.URI.create("https://accounts.google.com/challenge/recaptcha?token=private-token");
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", url.toString()));
        assertThatThrownBy(() -> client.initialize(new BrowserCookieReader.Cookies(Map.of("__Secure-1PSID", "session-secret"), Instant.now().plusSeconds(600))))
                .isInstanceOfSatisfying(GeminiVerificationRequired.class, error -> {
                    assertThat(error.verificationUrl()).isEqualTo(url);
                    assertThat(error.toString()).doesNotContain("private-token");
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
    }
    @Test void discoversExactAccountModelsAndBuildsSelectorsFromAccountTier() throws Exception {
        var payload = status(1000); payload.set(15, List.of(model("abc123", "Web Pro", 4), model("def456", "Web Fast", 11))); payload.set(17, List.of(115));
        server.enqueue(new MockResponse().setBody(frame(payload, "otAQ7b")));
        var models = client.discover(session());
        assertThat(models).extracting(WebModel::id).containsExactly("gemini-web-pro", "gemini-web-fast");
        assertThat(models.getFirst().displayName()).isEqualTo("Web Pro");
        assertThat(models.getFirst().selector().get(4)).isEqualTo("abc123"); assertThat(models.getFirst().selector().get(11)).isEqualTo(4);
        assertThat(models.getFirst().selector().getLast()).isEqualTo(4);
        assertThat(server.takeRequest().getRequestUrl().queryParameter("rpcids")).isEqualTo("otAQ7b");
    }
    @Test void modelIdsUseReadableNamesAndKeepTheExactUpstreamSelector() {
        var payload = status(1000); payload.set(15, List.of(model("a1", "3.1 Pro", 3), model("a2", "3.5 Flash-Lite", 6), model("a3", "Gemini 3.8 Flash", 1), model("a4", "4 Argon", 8)));
        var models = client.parseModels(mapper.valueToTree(payload));
        assertThat(models).extracting(WebModel::id).containsExactly("gemini-3.1-pro", "gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-4-argon");
        assertThat(models.getFirst().upstreamId()).isEqualTo("a1");
        assertThat(models.getFirst().selector().get(4)).isEqualTo("a1");
        assertThat(models.getFirst().selector().getLast()).isEqualTo(3);
    }
    @Test void duplicateReadableNamesNeverSelectAnArbitraryUpstreamModel() {
        var payload = status(1000); payload.set(15, List.of(model("a1", "3.1 Pro", 3), model("a2", "Gemini 3.1 Pro", 8)));
        assertThatThrownBy(() -> client.parseModels(mapper.valueToTree(payload))).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("ambiguous_model_names"));
    }
    @Test void neverTreatsGuestOrRestrictedAccountsAsConnected() throws Exception {
        for (int code : List.of(1016, 1060, 1040, 1033)) {
            var payload = status(code); payload.set(15, List.of(model("abc123", "Web Pro", 4)));
            assertThatThrownBy(() -> client.parseModels(mapper.valueToTree(payload))).isInstanceOf(ApiException.class);
        }
        var unknown = status(1000); unknown.set(14, null);
        assertThatThrownBy(() -> client.parseModels(mapper.valueToTree(unknown))).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("gemini_protocol_changed"));
    }
    @Test void acceptsTheObservedDefaultStatusOnlyWithVerifiedAccountStructure() {
        var payload = status(1000); payload.set(0, 1); payload.set(14, null); payload.set(15, List.of(model("abc", "Web model", 4)));
        assertThat(client.parseModels(mapper.valueToTree(payload))).hasSize(1);
        payload.set(0, null);
        assertThatThrownBy(() -> client.parseModels(mapper.valueToTree(payload))).isInstanceOf(ApiException.class);
    }
    @Test void streamsUnicodeAndSendsExactDiscoveredModelWithoutFallback() throws Exception {
        var payload = status(1000); payload.set(15, List.of(model("abc123", "Web Pro", 4)));
        var model = client.parseModels(mapper.valueToTree(payload)).getFirst();
        server.enqueue(new MockResponse().setBody(frame(candidate("Привет", false), "generate") + frame(candidate("Привет 👋", true), "generate")).throttleBody(7, 1, TimeUnit.MILLISECONDS));
        var deltas = new StringBuilder();
        var result = client.generate(session(), model, new Provider.ProviderRequest(model.id(), "Be brief", List.of(new Provider.Message("user", "Hello"))), deltas::append, () -> false);
        assertThat(result.text()).isEqualTo("Привет 👋").isEqualTo(deltas.toString()); assertThat(result.inputTokens()).isNull();
        var request = server.takeRequest();
        var selector = mapper.readTree(request.getHeader("x-goog-ext-525001261-jspb")); assertThat(selector.get(4).asText()).isEqualTo("abc123");
        String form = request.getBody().readUtf8();
        String encoded = Arrays.stream(form.split("&")).filter(p -> p.startsWith("f.req=")).findFirst().orElseThrow().substring(6);
        var wire = mapper.readTree(java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8)); var inner = mapper.readTree(wire.get(1).asText());
        assertThat(inner.get(79).asInt()).isEqualTo(4); assertThat(inner.get(45).asInt()).isEqualTo(1);
        assertThat(inner.get(0).get(0).asText()).contains("Be brief", "user:", "Hello");
    }
    @Test void incompleteAndRevisedStreamsAreErrors() throws Exception {
        var payload = status(1000); payload.set(15, List.of(model("abc", "Web model", 4))); var model = client.parseModels(mapper.valueToTree(payload)).getFirst();
        var request = new Provider.ProviderRequest(model.id(), null, List.of(new Provider.Message("user", "Hello")));
        server.enqueue(new MockResponse().setBody(frame(candidate("partial", false), "generate")));
        assertThatThrownBy(() -> client.generate(session(), model, request, ignored -> {}, () -> false)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("gemini_stream_incomplete"));
        server.enqueue(new MockResponse().setBody(frame(candidate("first", false), "generate") + frame(candidate("changed", true), "generate")));
        assertThatThrownBy(() -> client.generate(session(), model, request, ignored -> {}, () -> false)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("gemini_stream_revised"));
        server.enqueue(new MockResponse().setBody(frame(candidate("first", false), "generate") + frame(candidate("changed", true), "generate")));
        var completed = client.generate(session(), model, new Provider.ProviderRequest(model.id(), null, request.messages(), false), ignored -> {}, () -> false);
        assertThat(completed.text()).isEqualTo("changed");
    }
    @Test void usageLimitDoesNotProduceACompletedResponse() throws Exception {
        var payload = status(1000); payload.set(15, List.of(model("abc", "Web model", 4))); var model = client.parseModels(mapper.valueToTree(payload)).getFirst();
        var part = new ArrayList<Object>(Collections.nCopies(6, null)); part.set(5, Arrays.asList(null, null, List.of(Arrays.asList(null, List.of(1037)))));
        server.enqueue(new MockResponse().setBody(mapper.writeValueAsString(List.of(part)) + "\n"));
        assertThatThrownBy(() -> client.generate(session(), model, new Provider.ProviderRequest(model.id(), null, List.of(new Provider.Message("user", "hello"))), ignored -> {}, () -> false))
                .isInstanceOfSatisfying(ApiException.class, e -> { assertThat(e.status()).isEqualTo(429); assertThat(e.code()).isEqualTo("gemini_usage_limit"); });
    }
    @Test void cancellationClosesAnIdleUpstreamCall() throws Exception {
        var payload = status(1000); payload.set(15, List.of(model("abc", "Web model", 4))); var model = client.parseModels(mapper.valueToTree(payload)).getFirst();
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> client.generate(session(), model, new Provider.ProviderRequest(model.id(), null, List.of(new Provider.Message("user", "hello"))), ignored -> {}, cancelled::get));
            assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull(); cancelled.set(true);
            assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(java.util.concurrent.CancellationException.class);
        }
    }
    @Test void anUpstreamTimeoutIsReportedAsATimeoutRatherThanAnAuthenticationFailure() {
        var timedClient = new GeminiWebClient(mapper, new OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).callTimeout(1, TimeUnit.SECONDS).build(), server.url("/").toString());
        var payload = status(1000); payload.set(15, List.of(model("abc", "Web model", 4))); var model = timedClient.parseModels(mapper.valueToTree(payload)).getFirst();
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        assertThatThrownBy(() -> timedClient.generate(session(), model, new Provider.ProviderRequest(model.id(), null, List.of(new Provider.Message("user", "hello"))), ignored -> {}, () -> false))
                .isInstanceOfSatisfying(ApiException.class, e -> { assertThat(e.status()).isEqualTo(504); assertThat(e.code()).isEqualTo("gemini_timeout"); });
    }
}
