package me.vonyayou.geminibridge.provider;

import java.time.Instant;

public record ProviderStatus(String id, String name, String connection, String authentication,
                             int availableModels, boolean mock, String description, Instant checkedAt,
                             Instant lastSuccessfulRequest, String lastError) {}
