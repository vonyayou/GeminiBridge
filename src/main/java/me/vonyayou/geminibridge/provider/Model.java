package me.vonyayou.geminibridge.provider;

import java.time.Instant;
import java.util.Set;

public record Model(String id, String displayName, String provider, boolean available, boolean mock,
                    Set<String> capabilities, boolean streaming, Instant checkedAt, java.util.List<String> aliases) {
    public Model(String id, String displayName, String provider, boolean available, boolean mock, Set<String> capabilities, boolean streaming, Instant checkedAt) {
        this(id, displayName, provider, available, mock, capabilities, streaming, checkedAt, java.util.List.of());
    }
    public Model { aliases = java.util.List.copyOf(aliases); }
}
