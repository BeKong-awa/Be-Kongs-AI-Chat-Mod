package com.be_kongs_ai_chat_mod.ai;

import com.be_kongs_ai_chat_mod.config.AiChatConfig;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface AiProvider {
    CompletableFuture<String> sendRequest(String systemPrompt, List<ChatMessage> messages, AiChatConfig config);
}