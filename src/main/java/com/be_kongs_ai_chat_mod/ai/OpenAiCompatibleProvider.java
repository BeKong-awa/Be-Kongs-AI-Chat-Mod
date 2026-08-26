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

public class OpenAiCompatibleProvider implements AiProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);
    private final HttpClient httpClient;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutSeconds;
    private final int retryCount;

    public OpenAiCompatibleProvider(String baseUrl, AiChatConfig config) {
        this(baseUrl, config, config.apiKey);
    }

    public OpenAiCompatibleProvider(String baseUrl, AiChatConfig config, String apiKey) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds))
                .build();
        this.endpoint = normalizeBaseUrl(baseUrl) + "chat/completions";
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
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", maxTokens);

        JsonArray messageArray = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JsonObject systemObj = new JsonObject();
            systemObj.addProperty("role", "system");
            systemObj.addProperty("content", systemPrompt);
            messageArray.add(systemObj);
        }
        for (ChatMessage msg : messages) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.getRole());
            Object content = msg.getContent();
            if (content instanceof String) {
                msgObj.addProperty("content", (String) content);
            } else if (content instanceof JsonArray) {
                msgObj.add("content", (JsonArray) content);
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
                    .header("Authorization", "Bearer " + apiKey)
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
        JsonArray choices = obj.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("No choices in response: " + body);
        }
        JsonObject first = choices.get(0).getAsJsonObject();
        JsonObject messageObj = first.getAsJsonObject("message");
        if (messageObj == null) {
            throw new IllegalStateException("No message in response: " + body);
        }
        JsonElement content = messageObj.get("content");
        return content == null ? "" : content.getAsString();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String s = baseUrl.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "/";
    }
}