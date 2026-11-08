package me.vonyayou.geminibridge.provider.web;

import me.vonyayou.geminibridge.gateway.ApiException;
import java.text.Normalizer;
import java.util.Locale;

public final class WebModelIds {
    private WebModelIds() {}
    public static String canonical(String displayName) {
        String slug = Normalizer.normalize(displayName, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceFirst("^gemini[\\s_-]*", "")
                .replaceAll("[^a-z0-9.]+", "-").replaceAll("^[.-]+|[.-]+$", "");
        if (slug.isBlank() || slug.length() > 120) throw new ApiException(502, "unsupported_model_name", "Gemini Web returned a model name that cannot be represented as a readable model ID.");
        return "gemini-" + slug;
    }
}
