package me.vonyayou.geminibridge.auth.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.vonyayou.geminibridge.auth.SessionSecrets;
import me.vonyayou.geminibridge.gateway.ApiException;
import me.vonyayou.geminibridge.provider.web.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;

@Service
public class GeminiWebSession {
    public record Snapshot(WebSessionData data, List<WebModel> models, long version) {
        public Snapshot { models = List.copyOf(models); }
        @Override public String toString() { return "Snapshot[credentials=REDACTED,models=" + models.size() + "]"; }
    }
    private final BrowserProfiles profiles;
    private final BrowserCookieReader reader;
    private final GeminiWebClient client;
    private final SessionSecrets credentials;
    private final ObjectMapper mapper;
    private final Path selectionFile;
    private volatile Snapshot snapshot;
    private volatile String state = "disconnected";
    private volatile String profileId;
    private volatile String lastError;
    private volatile Instant checkedAt = Instant.now();
    private volatile Instant lastSuccessfulRequest;
    private volatile ApiException generationBlock;
    private volatile GeminiVerificationRequired verification;
    private volatile List<WebModel> knownModels = List.of();
    private volatile String accountEmail;
    private volatile String source = "browser";
    private volatile Map<String, String> manualCookies = Map.of();
    private String principal;
    private long version;
    public GeminiWebSession(BrowserProfiles profiles, BrowserCookieReader reader, GeminiWebClient client, SessionSecrets credentials, ObjectMapper mapper, Environment environment) {
        this.profiles = profiles; this.reader = reader; this.client = client; this.credentials = credentials; this.mapper = mapper;
        selectionFile = Path.of(environment.getProperty("bridge.data-dir", "./data")).resolve("browser-session.json");
    }
    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        if (!Files.isRegularFile(selectionFile)) return;
        try {
            var selection = mapper.readTree(Files.readString(selectionFile));
            source = selection.path("source").asText("browser").equals("manual") ? "manual" : "browser";
            if (source.equals("manual")) {
                if (selection.path("enabled").asBoolean(false)) { state = "reauthentication_required"; lastError = "Manual cookies are kept only in memory. Enter them again after restarting GeminiBridge."; }
                return;
            }
            if (selection.path("enabled").asBoolean(false) && selection.path("profileId").isTextual()) {
                String id = selection.path("profileId").asText();
                Thread.ofVirtual().start(() -> { try { connect(id); } catch (ApiException ignored) {} });
            }
        } catch (Exception e) { state = "error"; lastError = "The saved browser selection could not be loaded. Select a profile and reconnect."; }
    }
    public synchronized Map<String, Object> connect(String selectedProfileId) {
        return connect(selectedProfileId, true);
    }
    private Map<String, Object> connect(String selectedProfileId, boolean retryGeneration) {
        if (selectedProfileId == null || selectedProfileId.isBlank()) {
            if (profileId != null) selectedProfileId = profileId;
            else {
                var list = profiles.profiles();
                if (list.size() != 1) throw new ApiException(400, "browser_profile_required", "Select a Firefox profile to connect your Gemini Web session.");
                selectedProfileId = list.getFirst().id();
            }
        }
        var browser = profiles.resolve(selectedProfileId);
        if (snapshot == null || !selectedProfileId.equals(profileId) || !source.equals("browser")) version++;
        if (!selectedProfileId.equals(profileId) || !source.equals("browser")) { generationBlock = null; knownModels = List.of(); accountEmail = null; principal = null; }
        source = "browser"; manualCookies = Map.of();
        profileId = selectedProfileId; state = "connecting"; lastError = null;
        try {
            var cookies = reader.read(browser);
            authenticate(cookies, retryGeneration);
            saveSelection(true);
            return status();
        } catch (ApiException e) { failure(e); throw e; }
        catch (Exception e) { var error = new ApiException(500, "session_configuration_error", "The browser selection could not be saved."); failure(error); throw error; }
    }
    public synchronized Map<String, Object> connectManual(Map<String, String> input) {
        var cookies = ManualCookieInput.parse(input);
        if (!source.equals("manual") || !manualCookies.equals(cookies.values())) { generationBlock = null; knownModels = List.of(); accountEmail = null; principal = null; }
        source = "manual"; profileId = null; manualCookies = cookies.values();
        version++; snapshot = null; state = "connecting"; lastError = null;
        try {
            saveSelection(true);
            authenticate(cookies, true);
            return status();
        } catch (ApiException error) { failure(error); throw error; }
        catch (Exception error) { var failure = new ApiException(500, "session_configuration_error", "The manual session preference could not be saved."); failure(failure); throw failure; }
    }
    private void authenticate(BrowserCookieReader.Cookies cookies, boolean retryGeneration) {
        String identity;
        try {
            String value = cookies.values().getOrDefault("__Secure-1PSID", "") + "\n" + cookies.values().getOrDefault("__Secure-3PSID", "");
            identity = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException("Cannot identify the supplied session"); }
        if (principal != null && !principal.equals(identity)) { generationBlock = null; knownModels = List.of(); accountEmail = null; }
        principal = identity;
        credentials.setProviderSecrets(new HashSet<>(cookies.values().values()));
        var data = client.initialize(cookies);
        accountEmail = data.accountEmail(); rememberSecrets(data);
        var models = client.discover(data); rememberSecrets(data);
        knownModels = List.copyOf(models);
        if (generationBlock != null && !retryGeneration) throw generationBlock;
        generationBlock = null;
        verification = null;
        snapshot = new Snapshot(data, models, version); state = "authenticated"; checkedAt = Instant.now();
    }
    public synchronized Map<String, Object> logout() {
        verification = null;
        snapshot = null; state = "disconnected"; lastError = null; checkedAt = Instant.now(); generationBlock = null; knownModels = List.of(); accountEmail = null; manualCookies = Map.of(); principal = null; version++; credentials.setProviderSecrets(Set.of());
        try { saveSelection(false); } catch (Exception e) { throw new ApiException(500, "session_configuration_error", "Session access was cleared, but the saved browser selection could not be disabled."); }
        return status();
    }
    public synchronized void check() {
        if (source.equals("manual")) {
            if (state.equals("disconnected") || manualCookies.isEmpty()) { checkedAt = Instant.now(); return; }
            var values = new LinkedHashMap<>(manualCookies);
            var current = snapshot;
            if (current != null && current.data().cookieJar() != null) values.putAll(current.data().cookieJar().sessionValues());
            synchronized (this) {
                try {
                    var cookies = ManualCookieInput.parse(values);
                    manualCookies = cookies.values(); state = "connecting"; lastError = null;
                    authenticate(cookies, false);
                } catch (ApiException error) { failure(error); }
            }
            return;
        }
        if (profileId == null) { checkedAt = Instant.now(); return; }
        if (state.equals("disconnected")) { checkedAt = Instant.now(); return; }
        try { connect(profileId, false); } catch (ApiException ignored) {}
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void refreshBeforeExpiry() {
        var current = snapshot;
        if (current != null && state.equals("authenticated") && current.data().expiresAt().isBefore(Instant.now().plusSeconds(90))) check();
    }
    public synchronized Snapshot requireAuthenticated() {
        if (snapshot != null && snapshot.data().expiresAt().isBefore(Instant.now())) check();
        if (snapshot == null && generationBlock != null) throw generationBlock;
        if (snapshot == null) throw new ApiException(401, "gemini_reauthentication_required", "Connect a session in Settings using browser import or manually entered cookies.");
        return snapshot;
    }
    public Snapshot current() {
        var current = snapshot;
        if (current == null || current.data().expiresAt().isBefore(Instant.now())) return null;
        return current;
    }
    public boolean isCurrent(Snapshot current) { var active = snapshot; return active != null && active.version() == current.version(); }
    public synchronized void generationFailed(Snapshot current, ApiException error) {
        if (!isCurrent(current)) return;
        lastError = error.getMessage();
        if (error.code().equals("gemini_security_check_required")) generationBlock = error;
        if (error.status() == 401 || error.status() == 403 || error.code().equals("gemini_model_rejected") || error.code().equals("gemini_protocol_changed")) failure(error);
    }
    public void succeeded() {
        var current = snapshot;
        if (current != null) rememberSecrets(current.data());
        lastSuccessfulRequest = Instant.now(); lastError = null;
    }
    public String connection() { return current() != null ? "available" : state.equals("disconnected") ? "disconnected" : state.equals("connecting") ? "connecting" : "unavailable"; }
    public Map<String, Object> status() {
        var result = new LinkedHashMap<String, Object>();
        var current = current();
        boolean active = current != null;
        result.put("provider", "gemini-web"); result.put("state", active ? "authenticated" : state.equals("authenticated") ? "reauthentication_required" : state);
        result.put("supported", true); result.put("unofficial", true); result.put("credentialStored", false); result.put("sessionLoaded", active);
        result.put("profileId", profileId); result.put("checkedAt", checkedAt); result.put("availableModels", active ? current.models().size() : 0);
        result.put("accountEmail", accountEmail);
        result.put("source", source); result.put("manualCookiesLoaded", !manualCookies.isEmpty());
        result.put("verificationAvailable", verificationUrl() != null);
        result.put("message", lastError != null ? lastError : active ? source.equals("manual") ? "Connected using your manually entered Google session cookies." : "Connected through your selected Firefox session." : source.equals("manual") ? "Enter Google session cookie values to connect." : "Select the Firefox profile where you are signed in to Gemini Web.");
        return result;
    }
    public Instant checkedAt() { return checkedAt; }
    public Instant lastSuccessfulRequest() { return lastSuccessfulRequest; }
    public String lastError() { return lastError; }
    private java.net.URI verificationUrl() {
        var pending = verification;
        return state.equals("verification_required") && pending != null ? pending.verificationUrl() : null;
    }
    public synchronized java.net.URI requireVerificationUrl(String selectedProfileId) {
        var url = verificationUrl();
        if (url == null) throw new ApiException(409, "google_verification_unavailable", "No current Google verification link is available. Reconnect and retry the request to obtain a fresh check.");
        if (source.equals("browser") && !Objects.equals(profileId, selectedProfileId))
            throw new ApiException(409, "google_verification_profile_mismatch", "Open verification in the Firefox profile used for this connection.");
        return url;
    }
    public List<BrowserProfiles.Profile> profiles() { return profiles.profiles(); }
    public List<WebModel> catalog() { return knownModels; }
    public ApiException unavailableModel(String id) {
        var block = generationBlock;
        return block != null && knownModels.stream().anyMatch(model -> model.id().equals(id) || ("gemini-web-" + model.upstreamId()).equals(id)) ? block : null;
    }
    private void rememberSecrets(WebSessionData data) {
        var secrets = new HashSet<>(data.cookies().values()); secrets.add(data.accessToken());
        if (data.cookieJar() != null) secrets.addAll(data.cookieJar().secrets());
        credentials.setProviderSecrets(secrets);
    }
    private void failure(ApiException error) {
        verification = error instanceof GeminiVerificationRequired required ? required : null;
        snapshot = null; state = error.code().equals("gemini_security_check_required") ? "verification_required" : error.status() == 401 ? "reauthentication_required" : "error"; lastError = error.getMessage(); checkedAt = Instant.now(); credentials.setProviderSecrets(Set.of());
    }
    private void saveSelection(boolean enabled) throws Exception {
        Files.createDirectories(selectionFile.getParent());
        var value = new LinkedHashMap<String, Object>(); value.put("enabled", enabled); value.put("profileId", profileId); value.put("source", source);
        Path temporary = Files.createTempFile(selectionFile.getParent(), "browser-session-", ".tmp");
        try {
            if (temporary.getFileSystem().supportedFileAttributeViews().contains("posix")) Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(temporary, mapper.writeValueAsString(value));
            Files.move(temporary, selectionFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
}
