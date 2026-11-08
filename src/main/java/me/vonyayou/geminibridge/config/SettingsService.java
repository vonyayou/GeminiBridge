package me.vonyayou.geminibridge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SettingsService {
    private final SettingsRepository repository;
    private final ObjectMapper mapper;
    private final Environment environment;
    private volatile Settings current;

    public SettingsService(SettingsRepository repository, ObjectMapper mapper, Environment environment) {
        this.repository = repository; this.mapper = mapper; this.environment = environment;
        current = repository.findById(1L).map(entity -> {
            try { return mapper.readValue(entity.json, Settings.class); }
            catch (Exception e) { throw new IllegalStateException("Stored settings are invalid"); }
        }).orElse(new Settings(activeHost(), activePort(), true, false, false, 30, "dark", false));
    }
    public Settings get() { return current; }
    public String activeHost() { return environment.getProperty("server.address", "127.0.0.1"); }
    public int activePort() { return environment.getProperty("local.server.port", Integer.class, environment.getProperty("server.port", Integer.class, 8080)); }
    public String baseUrl() {
        String host = activeHost();
        if (host.equals("0.0.0.0") || host.equals("::")) host = "127.0.0.1";
        if (host.contains(":")) host = "[" + host + "]";
        return "http://" + host + ":" + activePort() + "/v1";
    }
    public Map<String, Object> view() {
        var view = new LinkedHashMap<String, Object>(mapper.convertValue(current, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        view.put("baseUrl", baseUrl());
        view.put("activeHost", activeHost()); view.put("activePort", activePort());
        view.put("restartRequired", !current.host().equals(activeHost()) || current.port() != activePort());
        view.put("authenticationRequired", false);
        return view;
    }
    public synchronized Map<String, Object> update(Settings settings) {
        try {
            Path directory = Path.of(environment.getProperty("bridge.data-dir", "./data"));
            Path temporary = Files.createTempFile(directory, "server-", ".tmp");
            Files.writeString(temporary, "server.address=${GB_HOST:" + settings.host() + "}\nserver.port=${GB_PORT:" + settings.port() + "}\n");
            Files.move(temporary, directory.resolve("server.properties"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            repository.saveAndFlush(new SettingEntity(mapper.writeValueAsString(settings)));
            current = settings;
            return view();
        } catch (Exception e) { throw new IllegalStateException("Cannot persist settings"); }
    }
}
