package com.be_kongs_ai_chat_mod.ai;

import com.be_kongs_ai_chat_mod.config.AiChatConfig;

import java.util.Map;

public class AiProviderFactory {
    private static final Map<String, String> DEFAULT_BASE_URLS = Map.of(
            "openai", "https://api.openai.com/v1",
            "deepseek", "https://api.deepseek.com/v1",
            "moonshot", "https://api.moonshot.cn/v1",
            "anthropic", "https://api.anthropic.com",
            "ollama", "http://localhost:11434/v1",
            "agnes", "https://apihub.agnes-ai.com/v1"
    );

    public static AiProvider create(AiChatConfig config) {
        return create(config, config.apiKey);
    }

    public static AiProvider create(AiChatConfig config, String apiKeyOverride) {
        String provider = config.provider == null ? "custom" : config.provider.toLowerCase();
        String baseUrl = config.baseUrl;

        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URLS.getOrDefault(provider, "");
        }

        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "[AiChatMod] baseUrl is required for provider '" + provider + "'. Please set it in the config."
            );
        }

        if ("anthropic".equals(provider)) {
            return new AnthropicProvider(baseUrl, config, apiKeyOverride);
        }

        return new OpenAiCompatibleProvider(baseUrl, config, apiKeyOverride);
    }
}