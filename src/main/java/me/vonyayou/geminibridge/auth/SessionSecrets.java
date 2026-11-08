package me.vonyayou.geminibridge.auth;

import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class SessionSecrets {
    private volatile Set<String> values = Set.of();
    public void setProviderSecrets(Set<String> secrets) { values = Set.copyOf(secrets); }
    public String redact(String text) {
        String cleaned = text;
        for (String secret : values) if (!secret.isBlank()) cleaned = cleaned.replace(secret, "[REDACTED]");
        return cleaned;
    }
}
