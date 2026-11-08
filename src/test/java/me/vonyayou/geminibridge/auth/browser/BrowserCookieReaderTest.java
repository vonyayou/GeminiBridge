package me.vonyayou.geminibridge.auth.browser;

import me.vonyayou.geminibridge.gateway.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

class BrowserCookieReaderTest {
    @TempDir Path directory;
    private Path database() throws Exception {
        Path profile = Files.createDirectories(directory.resolve("profiles/test.default"));
        Path database = profile.resolve("cookies.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE moz_cookies (name TEXT,value TEXT,host TEXT,expiry INTEGER,originAttributes TEXT,lastAccessed INTEGER)");
        }
        return database;
    }
    private BrowserProfiles profiles() { return new BrowserProfiles(new MockEnvironment().withProperty("bridge.firefox-root", directory.resolve("profiles").toString())); }
    private void cookie(Path database, String name, String value, String domain, long expiry, String container) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var query = connection.prepareStatement("INSERT INTO moz_cookies VALUES(?,?,?,?,?,?)")) {
            query.setString(1, name); query.setString(2, value); query.setString(3, domain); query.setLong(4, expiry); query.setString(5, container); query.setLong(6, 1); query.executeUpdate();
        }
    }
    @Test void readsOnlyGoogleSessionCookiesAndDeletesSnapshot() throws Exception {
        Path database = database(); long expiry = java.time.Instant.now().plusSeconds(3600).getEpochSecond();
        cookie(database, "__Secure-1PSID", "own-session", ".google.com", expiry, "");
        cookie(database, "__Secure-1PSIDTS", "own-timestamp", ".google.com", expiry * 1000, "");
        cookie(database, "__Secure-3PSID", "own-related-session", ".google.com", expiry, "");
        cookie(database, "__Secure-1PSIDCC", "own-session-check", ".google.com", expiry, "");
        cookie(database, "GOOGLE_ABUSE_EXEMPTION", "completed-verification", ".google.com", expiry, "");
        cookie(database, "GOOGLE_ABUSE_EXEMPTION", "other-verification", ".example.com", expiry, "");
        cookie(database, "GOOGLE_ABUSE_EXEMPTION", "expired-verification", ".google.com", 1, "");
        cookie(database, "GOOGLE_ABUSE_EXEMPTION", "container-verification", ".google.com", expiry, "^userContextId=1");
        cookie(database, "__Secure-1PSID", "other-site", ".example.com", expiry, "");
        cookie(database, "__Secure-1PSID", "other-container", ".google.com", expiry, "^userContextId=1");
        cookie(database, "password", "private", ".google.com", expiry, "");
        long modified = Files.getLastModifiedTime(database).toMillis();
        var reader = new BrowserCookieReader(directory.resolve("data").toString());
        var cookies = reader.read(profiles().sources().getFirst());
        assertThat(cookies.values()).containsOnlyKeys("__Secure-1PSID", "__Secure-1PSIDTS", "__Secure-3PSID", "__Secure-1PSIDCC", "GOOGLE_ABUSE_EXEMPTION");
        assertThat(cookies.values()).containsEntry("GOOGLE_ABUSE_EXEMPTION", "completed-verification");
        assertThat(cookies.values().get("__Secure-1PSID")).isEqualTo("own-session");
        assertThat(cookies.toString()).doesNotContain("own-session", "own-timestamp", "completed-verification");
        assertThat(Files.getLastModifiedTime(database).toMillis()).isEqualTo(modified);
        try (var files = Files.list(directory.resolve("data/browser-tmp"))) { assertThat(files.count()).isZero(); }
    }
    @Test void readsCommittedWalDataFromAnOpenBrowserDatabase() throws Exception {
        Path database = database();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL"); statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.execute("INSERT INTO moz_cookies VALUES('__Secure-1PSID','wal-session','.google.com'," + java.time.Instant.now().plusSeconds(3600).getEpochSecond() + ",'',1)");
            assertThat(Files.exists(Path.of(database + "-wal"))).isTrue();
            var cookies = new BrowserCookieReader(directory.resolve("data").toString()).read(profiles().sources().getFirst());
            assertThat(cookies.values().get("__Secure-1PSID")).isEqualTo("wal-session");
        }
    }
    @Test void expiredOrMissingCookiesRequireLogin() throws Exception {
        Path database = database(); cookie(database, "__Secure-1PSID", "expired", ".google.com", 1, "");
        assertThatThrownBy(() -> new BrowserCookieReader(directory.resolve("data").toString()).read(profiles().sources().getFirst()))
                .isInstanceOfSatisfying(ApiException.class, e -> { assertThat(e.status()).isEqualTo(401); assertThat(e.code()).isEqualTo("gemini_cookie_missing"); });
    }
    @Test void profileIdentifiersCannotBeUsedAsArbitraryFilePaths() throws Exception {
        database(); var profiles = profiles();
        assertThat(profiles.profiles()).hasSize(1);
        assertThatThrownBy(() -> profiles.resolve("../../etc/passwd")).isInstanceOf(ApiException.class);
    }
}
