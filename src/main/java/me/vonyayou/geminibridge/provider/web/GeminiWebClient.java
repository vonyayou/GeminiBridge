package me.vonyayou.geminibridge.provider.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.vonyayou.geminibridge.auth.browser.BrowserCookieReader;
import me.vonyayou.geminibridge.gateway.ApiException;
import me.vonyayou.geminibridge.provider.Provider;
import me.vonyayou.geminibridge.provider.ProviderPrompt;
import okhttp3.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.io.StringReader;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Component
public class GeminiWebClient {
    private static final String MODEL_HEADER = "x-goog-ext-525001261-jspb";
    private static final String BARD_ERROR_TYPE = "type.googleapis.com/assistant.boq.bard.application.BardErrorInfo";
    private static final int GENERATION_RETRIES = 2;
    private final ObjectMapper mapper;
    private final OkHttpClient client;
    private final HttpUrl origin;
    private final WebFrames frames;
    private final AtomicInteger requestIds = new AtomicInteger(10000);
    @org.springframework.beans.factory.annotation.Autowired
    public GeminiWebClient(ObjectMapper mapper, Environment environment) {
        this(mapper, new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(110, TimeUnit.SECONDS)
                .callTimeout(115, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build(),
                environment.getProperty("bridge.gemini-web-origin", "https://gemini.google.com"));
    }
    public GeminiWebClient(ObjectMapper mapper, OkHttpClient client, String origin) {
        this.mapper = mapper; this.client = client.newBuilder().followRedirects(false).followSslRedirects(false).build(); this.origin = Objects.requireNonNull(HttpUrl.parse(origin)); this.frames = new WebFrames(mapper);
        if (!this.origin.isHttps() && !Set.of("localhost", "127.0.0.1", "::1").contains(this.origin.host())) throw new IllegalArgumentException("Gemini Web requires HTTPS");
    }
    public WebSessionData initialize(BrowserCookieReader.Cookies cookies) {
        var jar = new SessionCookieJar(origin, cookies.values());
        var connection = client.newBuilder().cookieJar(jar).build();
        HttpUrl pageUrl = origin.newBuilder().addPathSegment("app").build();
        try {
            for (int attempt = 0; attempt < 4; attempt++) {
                var request = headers(new Request.Builder().url(pageUrl)).get().build();
                try (var response = connection.newCall(request).execute()) {
                    if (response.isRedirect()) {
                        HttpUrl next = safePageRedirect(response);
                        if (next != null) { pageUrl = next; continue; }
                    }
                    validate(response, "session_page");
                    String page = limited(response, 8_388_608);
                    String token = value(page, "SNlM0e");
                    if (token == null || token.isBlank()) throw new ApiException(401, "gemini_reauthentication_required", "Gemini Web did not accept the supplied session. Complete sign-in in the source browser and enter fresh cookies or reimport the browser session.", "session_page");
                    String account = pageUrl.queryParameter("authuser");
                    var path = Pattern.compile("/u/(\\d+)/app/?").matcher(pageUrl.encodedPath());
                    if (path.matches()) account = path.group(1);
                    if (account != null && !account.matches("\\d{1,3}")) account = null;
                    String email = value(page, "oPEP7c");
                    if (email != null && !email.matches("[^@\\s]{1,64}@[^@\\s]{1,190}\\.[^@\\s]{2,63}")) email = null;
                    return new WebSessionData(cookies.values(), token, value(page, "cfb2h"), value(page, "FdrFJe"),
                            Optional.ofNullable(value(page, "TuX5cc")).orElse("en"), UUID.randomUUID().toString().toUpperCase(Locale.ROOT), cookies.expiresAt(), jar, pageUrl.encodedPath(), account, email);
                }
            }
            throw new ApiException(502, "gemini_redirect_loop", "Gemini Web kept redirecting its session page. Retry later.", "session_page");
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw networkError(e); }
    }
    public List<WebModel> discover(WebSessionData session) {
        HttpUrl url = query("/_/BardChatUi/data/batchexecute", session).addQueryParameter("rpcids", "otAQ7b").addQueryParameter("source-path", session.appPath()).build();
        var selector = new ArrayList<Object>(Collections.nCopies(16, null));
        selector.set(0, 1); selector.set(8, List.of(4, 5, 6, 8)); selector.add(session.requestSessionId());
        try {
            String payload = mapper.writeValueAsString(List.of(List.of(Arrays.asList("otAQ7b", "[]", null, "generic"))));
            var body = new FormBody.Builder().add("at", session.accessToken()).add("f.req", payload).build();
            var request = headers(new Request.Builder().url(url)).header(MODEL_HEADER, mapper.writeValueAsString(selector)).header("x-goog-ext-73010989-jspb", "[0]").post(body).build();
            try (var response = connection(session).newCall(request).execute()) {
                validate(response, "model_discovery");
                var found = new AtomicReference<JsonNode>();
                frames.read(new StringReader(limited(response, 8_388_608)), () -> false, part -> {
                    if (WebFrames.at(part, 0).asText().equals("wrb.fr") && WebFrames.at(part, 1).asText().equals("otAQ7b") && WebFrames.at(part, 2).isTextual()) {
                        try { found.set(mapper.readTree(part.get(2).asText())); }
                        catch (Exception e) { throw protocolError(); }
                    }
                });
                if (found.get() == null) throw protocolError();
                return parseModels(found.get());
            }
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw networkError(e); }
    }
    public List<WebModel> parseModels(JsonNode payload) {
        JsonNode status = WebFrames.at(payload, 14);
        boolean defaultStatus = status.isNull() && WebFrames.at(payload, 0).isIntegralNumber() && WebFrames.at(payload, 0).asInt() == 1
                && WebFrames.at(payload, 15).isArray() && WebFrames.at(payload, 16).isArray() && WebFrames.at(payload, 17).isArray();
        if (!status.isIntegralNumber() && !defaultStatus) throw protocolError();
        if (!defaultStatus && status.asInt() != 1000) {
            if (status.asInt() == 1016) throw new ApiException(401, "gemini_reauthentication_required", "Gemini Web reports an unauthenticated session. Enter fresh cookies or reimport your signed-in browser session.");
            if (Set.of(1040, 1042).contains(status.asInt())) throw new ApiException(403, "gemini_action_required", "Open Gemini Web and review its updated terms before reconnecting.");
            throw new ApiException(403, "gemini_access_restricted", "Google has restricted Gemini Web access for this session (status " + status.asInt() + "). Open Gemini Web to resolve it. No access checks are bypassed.");
        }
        JsonNode list = WebFrames.at(payload, 15);
        if (!list.isArray()) throw protocolError();
        JsonNode tiers = WebFrames.at(payload, 16), capabilities = WebFrames.at(payload, 17);
        int capacityField = has(tiers, 21) || has(tiers, 22) ? 12 : 11;
        int capacity = has(tiers, 21) ? 1 : has(tiers, 22) ? 2 : has(capabilities, 115) ? 4 : has(tiers, 16) || has(capabilities, 106) ? 3 : has(tiers, 8) || has(capabilities, 19) ? 2 : 1;
        var models = new LinkedHashMap<String, WebModel>();
        for (JsonNode item : list) {
            String id = WebFrames.at(item, 0).asText("");
            if (!id.matches("[a-zA-Z0-9._-]{1,100}")) continue;
            String name = firstText(item, 12, 2, 1, 10, 11, 19);
            if (name.isBlank()) continue;
            JsonNode modelNumber = WebFrames.at(item, 17);
            if (!modelNumber.isIntegralNumber()) modelNumber = WebFrames.at(item, 9);
            if (!modelNumber.isIntegralNumber()) throw protocolError();
            var selector = new ArrayList<Object>(Collections.nCopies(capacityField == 12 ? 16 : 15, null));
            selector.set(0, 1); selector.set(4, id); selector.set(7, 0); selector.set(8, List.of(4, 5, 6, 8));
            selector.set(capacityField, capacity); selector.set(selector.size() - 1, modelNumber.asInt());
            String publicId = WebModelIds.canonical(name);
            var previous = models.putIfAbsent(publicId, new WebModel(publicId, name, id, selector));
            if (previous != null && !previous.upstreamId().equals(id)) throw new ApiException(502, "ambiguous_model_names", "Gemini Web reported different models with the same readable name. No model was selected.");
        }
        return List.copyOf(models.values());
    }
    public Provider.ProviderResult generate(WebSessionData session, WebModel model, Provider.ProviderRequest input, Consumer<String> delta, BooleanSupplier cancelled) {
        return generate(session, model, input, delta, ignored -> {}, cancelled);
    }
    public Provider.ProviderResult generate(WebSessionData session, WebModel model, Provider.ProviderRequest input, Consumer<String> delta, Consumer<String> reasoningDelta, BooleanSupplier cancelled) {
        long timeoutMillis = client.callTimeoutMillis() > 0 ? Math.min(client.callTimeoutMillis(), 115_000) : 115_000;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        var connection = connection(session);
        var outputStarted = new java.util.concurrent.atomic.AtomicBoolean();
        Consumer<String> trackedDelta = text -> { outputStarted.set(true); delta.accept(text); };
        Consumer<String> trackedReasoningDelta = text -> { outputStarted.set(true); reasoningDelta.accept(text); };
        for (int attempt = 0; ; attempt++) {
            checkCancelled(cancelled);
            try {
                return generateAttempt(connection, session, model, input, trackedDelta, trackedReasoningDelta, cancelled, deadline);
            } catch (RetryableGenerationError error) {
                if (outputStarted.get() || attempt >= GENERATION_RETRIES) throw error;
                long delay = TimeUnit.SECONDS.toNanos(5L << attempt);
                if (deadline - System.nanoTime() <= delay) throw error;
                waitForRetry(delay, cancelled);
            }
        }
    }
    private Provider.ProviderResult generateAttempt(OkHttpClient connection, WebSessionData session, WebModel model, Provider.ProviderRequest input, Consumer<String> delta, Consumer<String> reasoningDelta, BooleanSupplier cancelled, long deadline) {
        String text = ProviderPrompt.format(input);
        var payload = new ArrayList<Object>(Collections.nCopies(81, null));
        payload.set(0, Arrays.asList(text, 0, null, null, null, null, 0)); payload.set(1, List.of(session.language()));
        payload.set(2, Arrays.asList("", "", "", null, null, null, null, null, null, ""));
        payload.set(6, List.of(1)); payload.set(7, 1); payload.set(10, 1); payload.set(11, 0); payload.set(17, List.of(List.of(0)));
        payload.set(18, 0); payload.set(27, 1); payload.set(30, List.of(4)); payload.set(41, List.of(1)); payload.set(45, 1);
        int thinkingLevel = input.extendedThinking() ? 2 : 1;
        payload.set(53, 0); payload.set(61, List.of()); payload.set(68, 1); payload.set(79, model.selector().getLast()); payload.set(80, thinkingLevel);
        String nonce = UUID.randomUUID().toString().toUpperCase(Locale.ROOT); payload.set(59, nonce);
        var selector = new ArrayList<>(model.selector()); selector.add(thinkingLevel); selector.add(session.requestSessionId());
        try {
            String wire = mapper.writeValueAsString(Arrays.asList(null, mapper.writeValueAsString(payload)));
            var body = new FormBody.Builder().add("at", session.accessToken()).add("f.req", wire).build();
            var request = headers(new Request.Builder().url(query("/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate", session).build()))
                    .header(MODEL_HEADER, mapper.writeValueAsString(selector)).header("x-goog-ext-73010989-jspb", "[0]").header("x-goog-ext-73010990-jspb", "[0,0,0]")
                    .header("x-goog-ext-525005358-jspb", mapper.writeValueAsString(List.of(nonce, 1))).post(body).build();
            Call call = connection.newCall(request);
            call.timeout().deadlineNanoTime(deadline);
            var watcher = Thread.ofVirtual().start(() -> {
                try { while (!Thread.currentThread().isInterrupted()) { if (cancelled.getAsBoolean()) { call.cancel(); return; } Thread.sleep(25); } }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            });
            try (var response = call.execute()) {
                validate(response, "generation");
                var answer = new StringBuilder(); var reasoning = new StringBuilder(); var complete = new java.util.concurrent.atomic.AtomicBoolean();
                frames.read(Objects.requireNonNull(response.body()).charStream(), cancelled, part -> {
                    ApiException error = streamError(part);
                    if (error != null) throw error;
                    JsonNode encoded = WebFrames.at(part, 2);
                    if (!encoded.isTextual()) return;
                    JsonNode data;
                    try { data = mapper.readTree(encoded.asText()); } catch (Exception e) { throw protocolError(); }
                    JsonNode candidate = WebFrames.at(data, 4, 0);
                    JsonNode thoughts = WebFrames.at(candidate, 37, 0, 0);
                    if (thoughts.isTextual() && !thoughts.asText().isEmpty()) appendOutput(reasoning, thoughts.asText(), reasoningDelta, input.streaming(), "gemini_reasoning_revised");
                    JsonNode content = WebFrames.at(candidate, 1, 0);
                    if (content.isTextual()) appendOutput(answer, content.asText(), delta, input.streaming(), "gemini_stream_revised");
                    if (WebFrames.at(candidate, 8, 0).asInt() == 2) complete.set(true);
                });
                if (!complete.get()) throw new ApiException(502, "gemini_stream_incomplete", "Gemini Web closed the stream before confirming completion.");
                if (answer.isEmpty()) throw new ApiException(502, "gemini_empty_response", "Gemini Web returned no supported text output.");
                return new Provider.ProviderResult(answer.toString(), null, null, reasoning.isEmpty() ? null : reasoning.toString());
            } finally { watcher.interrupt(); call.cancel(); }
        } catch (java.util.concurrent.CancellationException e) { throw e; }
        catch (ApiException e) { throw e; }
        catch (Exception e) { if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException(); throw networkError(e); }
    }
    private static void appendOutput(StringBuilder accumulated, String next, Consumer<String> delta, boolean streaming, String errorCode) {
        if (!next.startsWith(accumulated.toString())) {
            if (streaming) throw new ApiException(502, errorCode, "Gemini Web revised already streamed text. Retry with stream=false to receive its final text.");
            accumulated.setLength(0);
        }
        if (next.length() > accumulated.length()) { delta.accept(next.substring(accumulated.length())); accumulated.setLength(0); accumulated.append(next); }
    }
    private Request.Builder headers(Request.Builder request) {
        return request.header("Origin", origin.toString().replaceAll("/$", "")).header("Referer", origin.newBuilder().addPathSegment("app").build().toString())
                .header("User-Agent", "GeminiBridge/1.0 (local user-authorized session adapter)").header("X-Same-Domain", "1");
    }
    private HttpUrl.Builder query(String path, WebSessionData session) {
        var builder = origin.resolve(path).newBuilder().addQueryParameter("hl", session.language()).addQueryParameter("_reqid", String.valueOf(requestIds.getAndAdd(100000))).addQueryParameter("rt", "c");
        if (session.buildLabel() != null) builder.addQueryParameter("bl", session.buildLabel());
        if (session.sessionId() != null) builder.addQueryParameter("f.sid", session.sessionId());
        if (session.accountIndex() != null) builder.addQueryParameter("authuser", session.accountIndex());
        return builder;
    }
    private OkHttpClient connection(WebSessionData session) {
        var jar = session.cookieJar() == null ? new SessionCookieJar(origin, session.cookies()) : session.cookieJar();
        return client.newBuilder().cookieJar(jar).build();
    }
    private HttpUrl safePageRedirect(Response response) {
        String location = response.header("Location");
        HttpUrl target = location == null ? null : response.request().url().resolve(location);
        if (target == null || !target.scheme().equals(origin.scheme()) || !target.host().equals(origin.host()) || target.port() != origin.port()) return null;
        return target.encodedPath().matches("/app/?|/u/\\d{1,3}/app/?") ? target : null;
    }
    private void validate(Response response, String stage) {
        String location = response.header("Location");
        HttpUrl target = location == null ? null : response.request().url().resolve(location);
        if (response.isRedirect() && target != null && (GeminiVerificationRequired.isAllowed(target.uri())
                || target.host().equals(origin.host()) && target.encodedPath().matches("/(sorry|challenge)(/.*)?"))) {
            throw new GeminiVerificationRequired(target.uri(), stage);
        }
        if (target != null && target.host().equals("consent.google.com")) throw new ApiException(403, "gemini_browser_action_required", "Google requires an action on its consent page. Complete that action in the source browser before reconnecting.", stage);
        if (response.code() == 401 || response.code() >= 300 && response.code() < 400) {
            String detail = stage.equals("session_page") ? "opening the Gemini session page" : stage.equals("model_discovery") ? "checking the account models" : "generating a response";
            throw new ApiException(401, "gemini_reauthentication_required", "Google requires sign-in or browser verification while " + detail + " (HTTP " + response.code() + "). Open Gemini in the source browser, complete the requested action, and enter fresh cookies or reimport its session.", stage);
        }
        if (response.code() == 403) throw new ApiException(403, "gemini_access_restricted", "Google denied this session request. Browser and device checks are not bypassed.");
        if (response.code() == 429) throw new ApiException(429, "gemini_rate_limit", "Google rate-limited this session. Wait before reconnecting or sending another request.");
        if (!response.isSuccessful()) throw new ApiException(502, "gemini_upstream_error", "Gemini Web returned HTTP " + response.code() + ".");
    }
    private String limited(Response response, int limit) throws Exception {
        byte[] bytes = Objects.requireNonNull(response.body()).byteStream().readNBytes(limit + 1);
        if (bytes.length > limit) throw new ApiException(502, "gemini_response_too_large", "Gemini Web returned an oversized response.");
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
    private String value(String page, String key) throws Exception {
        var match = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\")").matcher(page);
        return match.find() ? mapper.readValue(match.group(1), String.class) : null;
    }
    private static String firstText(JsonNode item, int... indices) { for (int index : indices) if (WebFrames.at(item, index).isTextual() && !item.get(index).asText().isBlank()) return item.get(index).asText(); return ""; }
    private static boolean has(JsonNode node, int value) { if (node.isArray()) for (JsonNode item : node) if (item.isIntegralNumber() && item.asInt() == value) return true; return false; }
    private static ApiException protocolError() { return new ApiException(502, "gemini_protocol_changed", "Gemini Web returned an unsupported protocol format. No fallback model was selected."); }
    private static ApiException streamError(JsonNode part) {
        if (!WebFrames.at(part, 0).asText().equals("wrb.fr")) return null;
        JsonNode rpc = WebFrames.at(part, 5, 0);
        JsonNode details = WebFrames.at(part, 5, 2);
        if (details.isArray()) for (JsonNode detail : details) {
            if (!WebFrames.at(detail, 0).asText().equals(BARD_ERROR_TYPE)) continue;
            JsonNode code = WebFrames.at(detail, 1, 0);
            if (code.isIntegralNumber() && code.asInt() > 0) return streamError(code.asInt(), rpc.asInt());
        }
        if (rpc.isIntegralNumber() && rpc.asInt() != 0) {
            if (rpc.asInt() == 8) return new ApiException(429, "gemini_resource_exhausted", "Gemini Web cannot accept this request right now. Please retry later.");
            return new ApiException(502, "gemini_upstream_error", "Gemini Web returned RPC error " + rpc.asInt() + ".");
        }
        return null;
    }
    private static ApiException streamError(int code, int rpc) {
        if (code == 1095) return new RetryableGenerationError(rpc == 8 ? 429 : 503);
        if (code == 1037) return new ApiException(429, "gemini_usage_limit", "Gemini Web reports that this model's usage limit has been reached.");
        if (code == 1052 || code == 1050) return new ApiException(502, "gemini_model_rejected", "Gemini Web rejected the selected model. Refresh model availability; no model substitution was made.");
        if (code == 1060) return new ApiException(403, "gemini_access_restricted", "Google restricted access for this session. Resolve it in Gemini Web.");
        if (rpc == 8) return new ApiException(429, "gemini_resource_exhausted", "Gemini Web cannot accept this request right now (error " + code + "). Please retry later.");
        return new ApiException(502, "gemini_generation_error", "Gemini Web reported generation error " + code + ".");
    }
    private static final class RetryableGenerationError extends ApiException {
        private RetryableGenerationError(int status) {
            super(status, "gemini_temporarily_unavailable", "Gemini Web is temporarily unable to generate a response (error 1095). Please retry later.");
        }
    }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
    }
    private static void waitForRetry(long delay, BooleanSupplier cancelled) {
        long until = System.nanoTime() + delay;
        while (true) {
            checkCancelled(cancelled);
            long remaining = until - System.nanoTime();
            if (remaining <= 0) return;
            try { TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(125))); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException(); }
        }
    }
    private static ApiException networkError(Exception error) {
        if (error instanceof java.io.InterruptedIOException) return new ApiException(504, "gemini_timeout", "Gemini Web timed out while preparing the response. Try a shorter prompt or retry later.");
        return new ApiException(502, "gemini_network_error", "Could not complete the connection to Gemini Web. Check the network and try again.");
    }
}
