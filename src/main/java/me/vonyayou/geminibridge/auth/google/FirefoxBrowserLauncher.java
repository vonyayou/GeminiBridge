package me.vonyayou.geminibridge.auth.google;

import me.vonyayou.geminibridge.auth.browser.BrowserProfiles;
import me.vonyayou.geminibridge.gateway.ApiException;
import me.vonyayou.geminibridge.provider.web.GeminiVerificationRequired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class FirefoxBrowserLauncher implements BrowserLauncher {
    private final Environment environment;
    public FirefoxBrowserLauncher(Environment environment) { this.environment = environment; }
    public List<String> command(BrowserProfiles.Source profile, URI url) {
        boolean login = "https".equals(url.getScheme()) && "accounts.google.com".equals(url.getHost())
                && url.getRawUserInfo() == null && url.getFragment() == null && (url.getPort() == -1 || url.getPort() == 443)
                && "/AccountChooser".equals(url.getRawPath());
        if (!login && !GeminiVerificationRequired.isAllowed(url)) throw new IllegalArgumentException("Only Google sign-in and verification HTTPS URLs can be opened");
        String executable = environment.getProperty("bridge.firefox-executable");
        if (executable == null || executable.isBlank()) executable = findFirefox();
        if (executable == null) throw new ApiException(503, "firefox_not_found", "Firefox could not be found. Open Gemini in the selected profile, then connect its session.");
        return List.of(executable, "--profile", profile.database().getParent().toAbsolutePath().toString(), "--new-tab", url.toASCIIString());
    }
    @Override public void open(BrowserProfiles.Source profile, URI url) {
        var command = command(profile, url);
        try {
            Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (process.waitFor(750, TimeUnit.MILLISECONDS) && process.exitValue() != 0)
                throw new ApiException(503, "firefox_launch_failed", "Firefox could not open this profile. Open it manually and complete Google sign-in there.");
        } catch (ApiException error) { throw error; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new ApiException(503, "firefox_launch_interrupted", "Opening Firefox was interrupted. Try again."); }
        catch (Exception error) { throw new ApiException(503, "firefox_launch_failed", "Firefox could not be opened from this server. Open it manually on this machine."); }
    }
    private static String findFirefox() {
        var candidates = new ArrayList<Path>();
        String searchPath = System.getenv("PATH");
        if (searchPath != null) for (String folder : searchPath.split(java.io.File.pathSeparator)) {
            candidates.add(Path.of(folder).resolve("firefox")); candidates.add(Path.of(folder).resolve("firefox.exe"));
        }
        candidates.add(Path.of("/Applications/Firefox.app/Contents/MacOS/firefox"));
        for (String name : List.of("ProgramFiles", "ProgramFiles(x86)")) {
            String directory = System.getenv(name);
            if (directory != null) candidates.add(Path.of(directory).resolve("Mozilla Firefox/firefox.exe"));
        }
        return candidates.stream().filter(path -> Files.isRegularFile(path) && Files.isExecutable(path)).map(Path::toString).findFirst().orElse(null);
    }
}
