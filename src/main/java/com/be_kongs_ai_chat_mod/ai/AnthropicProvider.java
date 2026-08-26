package com.be_kongs_ai_chat_mod.ai;

import com.be_kongs_ai_chat_mod.config.AiChatConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class AnthropicProvider implements AiProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnthropicProvider.class);
    private final HttpClient httpClient;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutSeconds;
    private final int retryCount;

    public AnthropicProvider(String baseUrl, AiChatConfig config) {
        this(baseUrl, config, config.apiKey);
    }

    public AnthropicProvider(String baseUrl, AiChatConfig config, String apiKey) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds))
                .build();
        this.endpoint = normalizeBaseUrl(baseUrl) + "v1/messages";
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = config.model;
        this.temperature = config.temperature;
        this.maxTokens = config.maxTokens;
        this.timeoutSeconds = config.timeoutSeconds;
        this.retryCount = config.retryCount;
    }

    @Override
    public CompletableFuture<String> sendRequest(String systemPrompt, List<ChatMessage> messages, AiChatConfig config) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", temperature);

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.addProperty("system", systemPrompt);
        }

        JsonArray messageArray = new JsonArray();
        for (ChatMessage msg : messages) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", "assistant".equals(msg.getRole()) ? "assistant" : "user");
            Object content = msg.getContent();
            if (content instanceof String) {
                msgObj.addProperty("content", (String) content);
            } else if (content instanceof JsonArray) {
                // Anthropic 格式：content 是数组，每个元素有 type 和 text/image_url
                JsonArray anthropicContent = new JsonArray();
                for (var element : (JsonArray) content) {
                    JsonObject obj = element.getAsJsonObject();
                    String type = obj.get("type").getAsString();
                    if ("text".equals(type)) {
                        JsonObject textPart = new JsonObject();
                        textPart.addProperty("type", "text");
                        textPart.addProperty("text", obj.get("text").getAsString());
                        anthropicContent.add(textPart);
                    } else if ("image_url".equals(type)) {
                        JsonObject imagePart = new JsonObject();
                        imagePart.addProperty("type", "image");
                        JsonObject source = new JsonObject();
                        source.addProperty("type", "url");
                        source.addProperty("url", obj.getAsJsonObject("image_url").get("url").getAsString());
                        imagePart.add("source", source);
                        anthropicContent.add(imagePart);
                    }
                }
                msgObj.add("content", anthropicContent);
            }
            messageArray.add(msgObj);
        }
        body.add("messages", messageArray);
        return sendWithRetry(body.toString(), retryCount);
    }

    private CompletableFuture<String> sendWithRetry(String json, int retriesLeft) {
        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, throwable) -> {
                        if (throwable != null) {
                            if (retriesLeft > 0) {
                                sendWithRetry(json, retriesLeft - 1).whenComplete((r, t) -> {
                                    if (t != null) future.completeExceptionally(t);
                                    else future.complete(r);
                                });
                            } else {
                                future.completeExceptionally(throwable);
                            }
                            return;
                        }
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            try {
                                future.complete(parseResponse(response.body()));
                            } catch (Exception e) {
                                future.completeExceptionally(e);
                            }
                        } else {
                            if (retriesLeft > 0) {
                                sendWithRetry(json, retriesLeft - 1).whenComplete((r, t) -> {
                                    if (t != null) future.completeExceptionally(t);
                                    else future.complete(r);
                                });
                            } else {
                                future.completeExceptionally(new RuntimeException(
                                        "HTTP " + response.statusCode() + ": " + response.body()
                                ));
                            }
                        }
                    });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private String parseResponse(String body) {
        JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
        JsonArray content = obj.getAsJsonArray("content");
        if (content == null || content.isEmpty()) {
            throw new IllegalStateException("No content in Anthropic response: " + body);
        }
        JsonObject first = content.get(0).getAsJsonObject();
        JsonElement text = first.get("text");
        return text == null ? "" : text.getAsString();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String s = baseUrl.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "/";
    }
}