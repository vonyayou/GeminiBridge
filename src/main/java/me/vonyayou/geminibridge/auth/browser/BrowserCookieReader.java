package me.vonyayou.geminibridge.auth.browser;

import me.vonyayou.geminibridge.gateway.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;

@Component
public class BrowserCookieReader {
    public record Cookies(Map<String, String> values, Instant expiresAt) {
        public Cookies { values = Map.copyOf(values); }
        @Override public String toString() { return "Cookies[values=REDACTED]"; }
    }
    private final Path temporaryRoot;
    public BrowserCookieReader(@Value("${bridge.data-dir:./data}") String dataDir) { temporaryRoot = Path.of(dataDir).resolve("browser-tmp"); }
    public Cookies read(BrowserProfiles.Source source) {
        Path snapshot = null;
        try {
            Files.createDirectories(temporaryRoot);
            restrict(temporaryRoot, "rwx------");
            snapshot = Files.createTempDirectory(temporaryRoot, "snapshot-");
            restrict(snapshot, "rwx------");
            Path db = snapshot.resolve("cookies.sqlite");
            boolean stable = false;
            for (int attempt = 0; attempt < 3; attempt++) {
                String before = signature(source.database());
                Files.copy(source.database(), db, StandardCopyOption.REPLACE_EXISTING);
                restrict(db, "rw-------");
                Path wal = Path.of(source.database() + "-wal");
                if (Files.exists(wal)) { Files.copy(wal, snapshot.resolve("cookies.sqlite-wal"), StandardCopyOption.REPLACE_EXISTING); restrict(snapshot.resolve("cookies.sqlite-wal"), "rw-------"); }
                else Files.deleteIfExists(snapshot.resolve("cookies.sqlite-wal"));
                if (before.equals(signature(source.database()))) { stable = true; break; }
            }
            if (!stable) throw new ApiException(409, "browser_busy", "The browser cookie database is changing. Try connecting again.");
            var cookies = new LinkedHashMap<String, String>();
            Instant expiry = Instant.now().plusSeconds(600);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                 var query = connection.prepareStatement("SELECT name, value, expiry FROM moz_cookies WHERE host IN ('.google.com','google.com','.gemini.google.com','gemini.google.com') AND name IN ('__Secure-1PSID','__Secure-1PSIDTS','__Secure-1PSIDCC','__Secure-3PSID','__Secure-3PSIDTS','__Secure-3PSIDCC','GOOGLE_ABUSE_EXEMPTION') AND originAttributes = '' ORDER BY lastAccessed ASC")) {
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        long seconds = rows.getLong("expiry");
                        if (seconds > 10_000_000_000L) seconds /= 1000;
                        if (seconds <= Instant.now().getEpochSecond()) continue;
                        String value = rows.getString("value");
                        if (value == null || value.isBlank() || value.length() > 8192 || value.chars().anyMatch(c -> c < 33 || c > 126 || c == ';' || c == '\r' || c == '\n')) continue;
                        cookies.put(rows.getString("name"), value);
                        if (Instant.ofEpochSecond(seconds).isBefore(expiry)) expiry = Instant.ofEpochSecond(seconds);
                    }
                }
            }
            if (!cookies.containsKey("__Secure-1PSID")) throw new ApiException(401, "gemini_cookie_missing", "Sign in at gemini.google.com in the selected Firefox profile, then reconnect.");
            return new Cookies(cookies, expiry);
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException(503, "browser_cookie_read_failed", "The selected browser cookie database could not be read. Check its permissions and try again."); }
        finally {
            if (snapshot != null) {
                try (var files = Files.list(snapshot)) { files.forEach(file -> { try { Files.deleteIfExists(file); } catch (Exception ignored) {} }); }
                catch (Exception ignored) {}
                try { Files.deleteIfExists(snapshot); } catch (Exception ignored) {}
            }
        }
    }
    private static String signature(Path database) throws Exception {
        Path wal = Path.of(database + "-wal");
        return Files.size(database) + ":" + Files.getLastModifiedTime(database) + ":" + (Files.exists(wal) ? Files.size(wal) + ":" + Files.getLastModifiedTime(wal) : "none");
    }
    private static void restrict(Path path, String permissions) throws Exception {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    }
}
