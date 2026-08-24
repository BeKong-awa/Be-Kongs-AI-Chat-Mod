package com.be_kongs_ai_chat_mod.ai;

public class ChatMessage {
    public final String role;
    public final String content;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
}