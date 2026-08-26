package com.be_kongs_ai_chat_mod.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天消息，支持多模态内容（文本 + 图片）。
 * 兼容 OpenAI 格式的多模态消息：
 * content 可以是字符串（纯文本）或 JSON 数组（多模态）
 */
public class ChatMessage {
    public final String role;
    private final Object content; // String 或 JsonArray

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public ChatMessage(String role, JsonArray content) {
        this.role = role;
        this.content = content;
    }

    /**
     * 创建纯文本消息
     */
    public static ChatMessage text(String role, String content) {
        return new ChatMessage(role, content);
    }

    /**
     * 创建多模态消息（文本 + 图片 URL 列表）
     */
    public static ChatMessage withImages(String role, String text, List<String> imageUrls) {
        JsonArray content = new JsonArray();
        // 添加文本部分
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", text);
        content.add(textPart);
        // 添加图片部分
        for (String url : imageUrls) {
            JsonObject imagePart = new JsonObject();
            imagePart.addProperty("type", "image_url");
            JsonObject imageUrlObj = new JsonObject();
            imageUrlObj.addProperty("url", url);
            imagePart.add("image_url", imageUrlObj);
            content.add(imagePart);
        }
        return new ChatMessage(role, content);
    }

    /**
     * 创建多模态消息（文本 + 单个图片 URL）
     */
    public static ChatMessage withImage(String role, String text, String imageUrl) {
        return withImages(role, text, List.of(imageUrl));
    }

    public String getRole() {
        return role;
    }

    public Object getContent() {
        return content;
    }

    public boolean isMultiModal() {
        return content instanceof JsonArray;
    }

    public String getTextContent() {
        if (content instanceof String) {
            return (String) content;
        } else if (content instanceof JsonArray) {
            // 提取文本部分
            for (var element : (JsonArray) content) {
                JsonObject obj = element.getAsJsonObject();
                if ("text".equals(obj.get("type").getAsString())) {
                    return obj.get("text").getAsString();
                }
            }
            return "";
        }
        return "";
    }

    public List<String> getImageUrls() {
        List<String> urls = new ArrayList<>();
        if (content instanceof JsonArray) {
            for (var element : (JsonArray) content) {
                JsonObject obj = element.getAsJsonObject();
                if ("image_url".equals(obj.get("type").getAsString())) {
                    JsonObject imageUrlObj = obj.getAsJsonObject("image_url");
                    if (imageUrlObj != null && imageUrlObj.has("url")) {
                        urls.add(imageUrlObj.get("url").getAsString());
                    }
                }
            }
        }
        return urls;
    }
}