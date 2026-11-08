package me.vonyayou.geminibridge.provider;

public final class ProviderPrompt {
    private ProviderPrompt() {}
    public static String format(Provider.ProviderRequest request) {
        var text = new StringBuilder();
        if (request.instructions() != null && !request.instructions().isBlank()) text.append("System instructions:\n").append(request.instructions()).append("\n\n");
        if (request.messages().size() == 1 && (request.instructions() == null || request.instructions().isBlank())) text.append(request.messages().getFirst().content());
        else {
            text.append("Continue this conversation. Respond to the final message.\n\n");
            for (var message : request.messages()) text.append(message.role()).append(":\n").append(message.content()).append("\n\n");
        }
        return text.toString();
    }
}
