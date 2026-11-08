package me.vonyayou.geminibridge.auth.google;

import me.vonyayou.geminibridge.auth.browser.BrowserProfiles;
import java.net.URI;

public interface BrowserLauncher {
    void open(BrowserProfiles.Source profile, URI url);
}
