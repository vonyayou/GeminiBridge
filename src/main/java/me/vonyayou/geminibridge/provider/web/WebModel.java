package me.vonyayou.geminibridge.provider.web;

import java.util.List;

public record WebModel(String id, String displayName, String upstreamId, List<Object> selector) {
    public WebModel { selector = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(selector)); }
}
