package me.vonyayou.geminibridge.config;

import jakarta.validation.constraints.*;

public record Settings(
    @NotBlank @Pattern(regexp = "127\\.0\\.0\\.1|localhost|::1") String host,
    @Min(1024) @Max(65535) int port,
    boolean historyEnabled,
    boolean saveRequestBodies,
    boolean saveResponseBodies,
    @Min(1) @Max(3650) int retentionDays,
    @NotBlank @Pattern(regexp = "dark|light|system") String theme,
    boolean compactSidebar
) {}
