package me.vonyayou.geminibridge.auth.browser;

import me.vonyayou.geminibridge.gateway.ApiException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

@Component
public class BrowserProfiles {
    public record Profile(String id, String browser, String name) {}
    public record Source(Profile profile, Path database) {}
    private final List<Path> roots;
    public BrowserProfiles(Environment environment) {
        String custom = environment.getProperty("bridge.firefox-root");
        if (custom != null && !custom.isBlank()) { roots = List.of(Path.of(custom)); return; }
        Path home = Path.of(System.getProperty("user.home"));
        var paths = new ArrayList<>(List.of(home.resolve(".config/mozilla/firefox"), home.resolve(".mozilla/firefox"),
                home.resolve(".var/app/org.mozilla.firefox/.mozilla/firefox"), home.resolve("Library/Application Support/Firefox/Profiles")));
        String appData = System.getenv("APPDATA");
        if (appData != null) paths.add(Path.of(appData).resolve("Mozilla/Firefox/Profiles"));
        roots = List.copyOf(paths);
    }
    public List<Source> sources() {
        var sources = new ArrayList<Source>();
        var seen = new HashSet<Path>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var entries = Files.list(root)) {
                entries.filter(Files::isDirectory).limit(50).sorted().forEach(profile -> {
                    Path db = profile.resolve("cookies.sqlite");
                    try {
                        if (!Files.isRegularFile(db) || !Files.isReadable(db) || !seen.add(db.toRealPath())) return;
                        String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(db.toRealPath().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 20);
                        sources.add(new Source(new Profile(id, "Firefox", profile.getFileName().toString()), db));
                    } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }
        return List.copyOf(sources);
    }
    public List<Profile> profiles() { return sources().stream().map(Source::profile).toList(); }
    public Source resolve(String id) {
        return sources().stream().filter(s -> s.profile().id().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(404, "browser_profile_not_found", "The selected Firefox profile is no longer available."));
    }
}
