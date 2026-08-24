package com.be_kongs_ai_chat_mod.chat;

import com.be_kongs_ai_chat_mod.BeKongsAiChatMod;
import com.be_kongs_ai_chat_mod.ai.AiProvider;
import com.be_kongs_ai_chat_mod.ai.AiProviderFactory;
import com.be_kongs_ai_chat_mod.ai.ChatMessage;
import com.be_kongs_ai_chat_mod.config.AiChatConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class AiChatManager {
    private static final Logger LOGGER = BeKongsAiChatMod.LOGGER;
    private static AiChatManager instance;

    private static final Field GAMEPROFILE_NAME_FIELD;

    static {
        Field nameField = null;
        try {
            Class<?> gpClass = Class.forName("com.mojang.authlib.GameProfile");
            nameField = gpClass.getDeclaredField("name");
            nameField.setAccessible(true);
        } catch (Exception e) {
            LOGGER.error("[AiChatMod] Failed to init GameProfile reflection", e);
        }
        GAMEPROFILE_NAME_FIELD = nameField;
    }

    private static String getGameProfileName(GameProfile profile) {
        if (profile == null || GAMEPROFILE_NAME_FIELD == null) return null;
        try {
            return (String) GAMEPROFILE_NAME_FIELD.get(profile);
        } catch (Exception e) {
            return null;
        }
    }

    private static final Pattern GENERIC_CHAT_PREFIX =
            Pattern.compile("^\\s*(?:[<\\[][^\\]<>]{0,32}[>\\]]|\\|[^»>]{0,64}[»>])\\s*[:：]?\\s*");

    // 用于提取玩家名的正则（例如 |[称号]玩家名 » 或 [称号]玩家名 »）
    private static final Pattern PLAYER_NAME_PATTERN =
            Pattern.compile("^\\s*\\|?\\s*\\[[^\\]]+\\]\\s*([^\\s»]+)\\s*[»>]");

    private volatile AiChatConfig config;
    private AiProvider triggerProvider;
    private AiProvider scheduleProvider;

    // 触发回复和自动回复各自独立的锁，互不阻塞
    private final AtomicBoolean triggerReplying = new AtomicBoolean(false);
    private final AtomicBoolean scheduleReplying = new AtomicBoolean(false);
    private final AtomicBoolean scheduledRequestQueued = new AtomicBoolean(false);
    private final AtomicBoolean imageGenerating = new AtomicBoolean(false);

    private final AtomicReference<String> lastPlayerMessage = new AtomicReference<>();
    private final AtomicLong lastPlayerMessageTime = new AtomicLong(0);
    private final AtomicLong lastHandledUserMessageTime = new AtomicLong(0);

    private final AtomicLong nextScheduledReplyTime = new AtomicLong(0);
    private final AtomicLong nextTriggerReplyTime = new AtomicLong(0);
    private final AtomicLong nextImageCooldownTime = new AtomicLong(0);

    private final Map<String, Long> recentAiMessages = new ConcurrentHashMap<>();
    // 用于同一玩家同一消息去重（短时间窗口）
    private final Map<String, Long> recentPlayerMessages = new ConcurrentHashMap<>();
    private final Deque<ChatMessage> context = new ArrayDeque<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AiChatMod-Scheduler");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(120))
            .build();

    private volatile Pattern triggerPattern;
    private volatile Pattern imageTriggerPattern;
    private volatile List<Pattern> blockedPatterns = List.of();

    private volatile boolean stripChatPrefix = true;

    public static AiChatManager getInstance() {
        return instance;
    }

    public static void init(AiChatConfig config) {
        instance = new AiChatManager(config);
        instance.startScheduler();
    }

    private AiChatManager(AiChatConfig config) {
        this.config = config;
        this.triggerProvider = safeCreateProvider(config, config.triggerApiKey);
        this.scheduleProvider = safeCreateProvider(config, config.scheduleApiKey);
        compilePatterns();
        nextScheduledReplyTime.set(System.currentTimeMillis() + config.scheduleIntervalSeconds * 1000L);
        nextTriggerReplyTime.set(System.currentTimeMillis());
        nextImageCooldownTime.set(System.currentTimeMillis());
        LOGGER.info("[AiChatMod] AiChatManager initialized. triggerEnabled={}, triggerRegex='{}', imageEnabled={}",
                config.triggerEnabled, config.triggerRegex, config.imageGenerationEnabled);
    }

    public void reloadConfig(AiChatConfig newConfig) {
        this.config = newConfig;
        this.triggerProvider = safeCreateProvider(newConfig, newConfig.triggerApiKey);
        this.scheduleProvider = safeCreateProvider(newConfig, newConfig.scheduleApiKey);
        compilePatterns();

        synchronized (context) {
            context.clear();
        }

        lastPlayerMessage.set(null);
        lastPlayerMessageTime.set(0);
        lastHandledUserMessageTime.set(0);
        nextScheduledReplyTime.set(System.currentTimeMillis() + newConfig.scheduleIntervalSeconds * 1000L);
        nextTriggerReplyTime.set(System.currentTimeMillis());
        nextImageCooldownTime.set(System.currentTimeMillis());
        recentAiMessages.clear();
        recentPlayerMessages.clear();
        triggerReplying.set(false);
        scheduleReplying.set(false);
        imageGenerating.set(false);
        LOGGER.info("[AiChatMod] Config reloaded.");
        if (newConfig.chatLogEnabled && newConfig.chatLogReload) {
            sendLocalChatMessage("§a[AiChatMod] §f配置已重载。");
        }
    }

    public void clearContext() {
        synchronized (context) {
            context.clear();
        }
        LOGGER.info("[AiChatMod] Context cleared.");
        if (config.chatLogEnabled && config.chatLogClearContext) {
            sendLocalChatMessage("§a[AiChatMod] §f上下文已清空。");
        }
    }

    private AiProvider safeCreateProvider(AiChatConfig config, String apiKey) {
        try {
            return AiProviderFactory.create(config, apiKey);
        } catch (Exception e) {
            LOGGER.error("[AiChatMod] Failed to create AI provider: {}", e.getMessage());
            return null;
        }
    }

    private void startScheduler() {
        scheduler.scheduleAtFixedRate(this::schedulerTick, 1, 1, TimeUnit.SECONDS);
    }

    private boolean isInGame() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.getConnection() != null;
    }

    private void schedulerTick() {
        try {
            if (config == null || !config.enabled || !config.scheduleEnabled) return;
            if (scheduleReplying.get() || scheduledRequestQueued.get() || imageGenerating.get()) return;

            if (!isInGame()) {
                lastPlayerMessage.set(null);
                lastPlayerMessageTime.set(0);
                lastHandledUserMessageTime.set(System.currentTimeMillis());
                nextScheduledReplyTime.set(System.currentTimeMillis() + 60_000);
                return;
            }

            cleanupRecentAiMessages();

            long now = System.currentTimeMillis();
            if (now < nextScheduledReplyTime.get()) return;

            String msg = lastPlayerMessage.get();
            if (msg == null || msg.isBlank()) return;
            if (lastPlayerMessageTime.get() <= lastHandledUserMessageTime.get()) return;

            if (scheduledRequestQueued.compareAndSet(false, true)) {
                final long userMessageTime = lastPlayerMessageTime.get();
                lastHandledUserMessageTime.set(userMessageTime);

                Minecraft client = Minecraft.getInstance();
                client.execute(() -> {
                    scheduledRequestQueued.set(false);
                    if (!isInGame()) return;
                    String latestMsg = lastPlayerMessage.get();
                    if (latestMsg != null && !latestMsg.isBlank()) {
                        if (config.chatLogEnabled && config.chatLogSchedule) {
                            sendLocalChatMessage("§a[AiChatMod] §f自动回复已触发。");
                        }
                        startAiReply(latestMsg, userMessageTime, false);
                    }
                });
            }
        } catch (Exception e) {
            LOGGER.error("[AiChatMod] Scheduler error", e);
        }
    }

    // ========== 消息处理 ==========
    public void onPlayerChatMessage(String text) {
        onPlayerChatMessage(text, null);
    }

    public void onPlayerChatMessage(String text, GameProfile sender) {
        if (text == null || text.isBlank()) return;
        if (!isInGame()) return;

        String originalText = text;

        // 1. 获取玩家名（优先从 sender 获取，否则从原始文本提取）
        String playerName = (sender != null) ? getGameProfileName(sender) : null;
        if (playerName == null) {
            playerName = extractPlayerNameFromRawText(originalText);

        }
        // 如果是客户端玩家自己发送的消息，直接忽略（包括 AI 自动发送的消息）
        if (isSelfPlayerName(playerName)) {
            if (config.debugLog) {
                LOGGER.info("[AiChatMod] Ignoring self message from player '{}'", playerName);
            }
            return;
        }

        // 2. 检查玩家黑名单
        if (isPlayerBlocked(playerName)) {
            if (config.debugLog) {
                LOGGER.info("[AiChatMod] Ignoring message from blocked player '{}'", playerName);
            }
            return;
        }

        // 3. 剥离前缀
        text = stripPlayerPrefix(text, sender);

        // 4. 如果来自 GAME 事件且前缀剥离后未变化，视为非玩家消息，忽略
        if (sender == null && text.equals(originalText)) {
            if (config.debugLog) {
                LOGGER.info("[AiChatMod] Ignoring non-player GAME message: '{}'", originalText);
            }
            return;
        }

        // 5. 同一玩家同一消息短时间内去重（2秒）
        String dedupeKey = (playerName == null ? "null" : playerName) + "|" + text;
        long now = System.currentTimeMillis();
        Long lastTime = recentPlayerMessages.get(dedupeKey);
        if (lastTime != null && now - lastTime < 2000L) {
            if (config.debugLog) {
                LOGGER.info("[AiChatMod] Duplicate message ignored (player='{}', text='{}')", playerName, text);
            }
            return;
        }
        recentPlayerMessages.put(dedupeKey, now);

        if (config.debugLog && !originalText.equals(text)) {
            LOGGER.info("[AiChatMod] Stripped chat prefix: '{}' -> '{}'", originalText, text);
            if (config.chatLogEnabled && config.chatLogDebug) {
                sendLocalChatMessage("§a[AiChatMod] §f前缀剥离: " + originalText + " -> " + text);
            }
        }

        // 检查图像生成触发（优先于普通触发）
        if (config.imageGenerationEnabled && matchesImageTrigger(text)) {
            long currentTime = System.currentTimeMillis();
            if (currentTime >= nextImageCooldownTime.get()) {
                nextImageCooldownTime.set(currentTime + config.imageCooldownSeconds * 1000L);
                String prompt = extractImagePrompt(text);
                if (prompt != null && !prompt.isBlank()) {
                    lastHandledUserMessageTime.set(System.currentTimeMillis());
                    lastPlayerMessage.set(text);
                    lastPlayerMessageTime.set(System.currentTimeMillis());
                    if (config.chatLogEnabled && config.chatLogTrigger) {
                        sendLocalChatMessage("§a[AiChatMod] §f文生图已触发，提示词: " + prompt);
                    }
                    generateImage(prompt, sender);
                }
            } else {
                long remaining = nextImageCooldownTime.get() - System.currentTimeMillis();
                LOGGER.info("[AiChatMod] Image generation cooldown active ({} ms remaining).", remaining);
                lastHandledUserMessageTime.set(System.currentTimeMillis());
            }
            return;
        }

        // 普通触发/自动回复逻辑
        lastPlayerMessage.set(text);
        lastPlayerMessageTime.set(System.currentTimeMillis());

        if (config == null || !config.enabled) return;

        if (config.contextEnabled && config.contextLength > 0) {
            addToContext(new ChatMessage("user", text));
        }

        boolean matches = config.triggerEnabled && matchesTrigger(text);
        boolean cooldownPassed = nextTriggerReplyTime.get() <= System.currentTimeMillis();

        if (config.debugLog) {
            LOGGER.info("[AiChatMod] Message='{}', triggerEnabled={}, regexMatch={}, cooldownPassed={}",
                    text, config.triggerEnabled, matches, cooldownPassed);
        }

        if (matches && cooldownPassed) {
            long currentTime = System.currentTimeMillis();
            nextTriggerReplyTime.set(currentTime + config.triggerCooldownSeconds * 1000L);
            lastHandledUserMessageTime.set(lastPlayerMessageTime.get());
            LOGGER.info("[AiChatMod] Trigger reply triggered! Cooldown set for {} seconds.", config.triggerCooldownSeconds);
            if (config.chatLogEnabled && config.chatLogTrigger) {
                sendLocalChatMessage("§a[AiChatMod] §f触发回复已启动。");
            }
            startAiReply(text, lastPlayerMessageTime.get(), true);
        } else if (matches && !cooldownPassed) {
            long remaining = nextTriggerReplyTime.get() - System.currentTimeMillis();
            LOGGER.info("[AiChatMod] Trigger matched but cooldown active ({} ms remaining).", remaining);
            lastHandledUserMessageTime.set(System.currentTimeMillis());
        }
    }

    // ========== 玩家名提取 ==========
    private String extractPlayerNameFromRawText(String rawText) {
        Matcher m = PLAYER_NAME_PATTERN.matcher(rawText);
        if (m.find()) {
            return m.group(1);
        }
        // 尝试匹配 <玩家名> 或 [玩家名]（无称号）
        Pattern p2 = Pattern.compile("^\\s*[<\\[]\\s*([^\\]<>]+)\\s*[>\\]]");
        Matcher m2 = p2.matcher(rawText);
        if (m2.find()) {
            return m2.group(1).trim();
        }
        return null;
    }

    private boolean isPlayerBlocked(String playerName) {
        if (playerName == null || config.blockedPlayers == null) return false;
        for (String blocked : config.blockedPlayers) {
            if (blocked != null && blocked.equalsIgnoreCase(playerName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSelfPlayerName(String playerName) {
        if (playerName == null) return false;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return false;
        String selfName = client.player.getName().getString();
        return selfName != null && selfName.equalsIgnoreCase(playerName);
    }

    // ========== 前缀剥离 ==========
    private String stripPlayerPrefix(String text, GameProfile sender) {
        if (!stripChatPrefix) return text;

        String playerName = getGameProfileName(sender);
        if (playerName != null) {
            String quotedName = Pattern.quote(playerName);
            Pattern prefixPattern = Pattern.compile(
                    "^<[^<>]*" + quotedName + "[^<>]*>\\s*|^\\[[^\\[\\]]*" + quotedName + "[^\\[\\]]*\\]\\s*"
            );
            Matcher matcher = prefixPattern.matcher(text);
            if (matcher.find()) {
                String stripped = text.substring(matcher.end()).trim();
                if (!stripped.isEmpty()) return stripped;
            }

            int nameIndex = text.indexOf(playerName);
            if (nameIndex > 0) {
                int start = -1;
                for (int i = nameIndex - 1; i >= 0; i--) {
                    char c = text.charAt(i);
                    if (c == '<' || c == '[') {
                        start = i;
                        break;
                    }
                }
                int end = -1;
                for (int i = nameIndex + playerName.length(); i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '>' || c == ']') {
                        end = i + 1;
                        break;
                    }
                }
                if (start >= 0 && end > start && end > nameIndex + playerName.length()) {
                    String stripped = text.substring(end).trim();
                    if (!stripped.isEmpty()) return stripped;
                }
            }
            return text;
        }

        Matcher genericMatcher = GENERIC_CHAT_PREFIX.matcher(text);
        if (genericMatcher.find()) {
            String stripped = text.substring(genericMatcher.end()).trim();
            if (!stripped.isEmpty()) return stripped;
        }
        return text;
    }

    // ========== 图像生成相关 ==========
    private boolean matchesImageTrigger(String text) {
        Pattern pattern = imageTriggerPattern;
        return pattern != null && pattern.matcher(text).find();
    }

    private String extractImagePrompt(String text) {
        Pattern pattern = imageTriggerPattern;
        if (pattern == null) return null;

        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            if (matcher.groupCount() >= 1) {
                String group = matcher.group(1);
                if (group != null && !group.isBlank()) {
                    return group.trim();
                }
            }
            String stripped = text.substring(matcher.end()).trim();
            return stripped.isEmpty() ? null : stripped;
        }
        return null;
    }

    private void generateImage(String prompt, GameProfile sender) {
        if (!imageGenerating.compareAndSet(false, true)) {
            LOGGER.info("[AiChatMod] Image generation already in progress.");
            return;
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                String imageUrl = requestImageGeneration(prompt);
                if (imageUrl == null || imageUrl.isBlank()) {
                    return null;
                }

                String uploaderType = config.imageUploaderType;
                if (uploaderType == null) uploaderType = "none";

                if ("catbox".equals(uploaderType)) {
                    String uploadedUrl = uploadToCatbox(imageUrl);
                    if (uploadedUrl != null && !uploadedUrl.isBlank()) {
                        return uploadedUrl;
                    }
                    LOGGER.warn("[AiChatMod] Catbox upload failed, falling back to original URL.");
                } else if ("0x0".equals(uploaderType)) {
                    String uploadedUrl = uploadTo0x0(imageUrl);
                    if (uploadedUrl != null && !uploadedUrl.isBlank()) {
                        return uploadedUrl;
                    }
                    LOGGER.warn("[AiChatMod] 0x0.st upload failed, falling back to original URL.");
                } else if ("imgbb".equals(uploaderType)) {
                    String uploadedUrl = uploadToImgbb(imageUrl);
                    if (uploadedUrl != null && !uploadedUrl.isBlank()) {
                        return uploadedUrl;
                    }
                    LOGGER.warn("[AiChatMod] imgbb upload failed, falling back to original URL.");
                } else if ("custom".equals(uploaderType)) {
                    String uploaderUrl = config.imageUploaderUrl;
                    if (uploaderUrl != null && !uploaderUrl.isBlank()) {
                        String uploadedUrl = uploadImageToCustomHost(imageUrl, uploaderUrl);
                        if (uploadedUrl != null && !uploadedUrl.isBlank()) {
                            return uploadedUrl;
                        }
                        LOGGER.warn("[AiChatMod] Custom image upload failed, falling back to original URL.");
                    }
                }

                return imageUrl;
            } catch (Exception e) {
                LOGGER.error("[AiChatMod] Image generation failed", e);
                return null;
            }
        }).thenAccept(finalUrl -> {
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> {
                imageGenerating.set(false);
                if (finalUrl == null || finalUrl.isBlank()) {
                    if (config.chatLogEnabled && config.chatLogError) {
                        sendLocalChatMessage("§c[AiChatMod] §f图像生成失败，请查看日志。");
                    }
                    return;
                }
                sendChatMessage(finalUrl);
                if (config.chatLogEnabled && config.chatLogTrigger) {
                    sendLocalChatMessage("§a[AiChatMod] §f图像生成完成，已发送 URL。");
                }
            });
        });
    }

    private String requestImageGeneration(String prompt) {
        if (config == null) return null;

        String base = config.imageBaseUrl.trim();
        String endpoint;
        if (base.contains("images/generations")) {
            endpoint = base;
        } else {
            endpoint = normalizeBaseUrl(base) + "images/generations";
        }

        String apiKey = config.imageApiKey;
        String model = config.imageModel;
        String size = config.imageSize;
        String ratio = config.imageRatio;

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("prompt", prompt);
        body.addProperty("size", size);
        body.addProperty("ratio", ratio);

        JsonObject extraBody = new JsonObject();
        extraBody.addProperty("response_format", "url");
        body.add("extra_body", extraBody);

        String json = body.toString();
        int retries = config.imageRetryCount;
        while (retries >= 0) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(config.imageTimeoutSeconds))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .version(HttpClient.Version.HTTP_1_1)
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return parseImageResponse(response.body());
                } else {
                    LOGGER.error("[AiChatMod] Image API returned HTTP {}: {}", response.statusCode(), response.body());
                }
            } catch (Exception e) {
                LOGGER.error("[AiChatMod] Image API request error", e);
            }
            retries--;
        }
        return null;
    }

    private String parseImageResponse(String body) {
        JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
        JsonArray data = obj.getAsJsonArray("data");
        if (data == null || data.isEmpty()) {
            LOGGER.error("[AiChatMod] Image response missing data: {}", body);
            return null;
        }
        JsonObject first = data.get(0).getAsJsonObject();
        if (first.has("url") && !first.get("url").isJsonNull()) {
            return first.get("url").getAsString();
        }
        return null;
    }

    // ========== 图床上传方法 ==========
    private String uploadToCatbox(String imageUrl) {
        int maxAttempts = 2;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest downloadRequest = HttpRequest.newBuilder()
                        .uri(URI.create(imageUrl))
                        .timeout(Duration.ofSeconds(config.imageTimeoutSeconds))
                        .version(HttpClient.Version.HTTP_1_1)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .GET()
                        .build();
                HttpResponse<byte[]> downloadResponse = httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofByteArray());
                if (downloadResponse.statusCode() < 200 || downloadResponse.statusCode() >= 300) {
                    LOGGER.error("[AiChatMod] Catbox upload: failed to download image, HTTP {}", downloadResponse.statusCode());
                    return null;
                }
                byte[] imageData = downloadResponse.body();

                String boundary = "----AiChatModCatbox" + UUID.randomUUID();
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                outputStream.write(("--" + boundary + "\r\n").getBytes());
                outputStream.write("Content-Disposition: form-data; name=\"reqtype\"\r\n\r\n".getBytes());
                outputStream.write("fileupload\r\n".getBytes());
                outputStream.write(("--" + boundary + "\r\n").getBytes());
                outputStream.write("Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"image.png\"\r\n".getBytes());
                outputStream.write("Content-Type: image/png\r\n\r\n".getBytes());
                outputStream.write(imageData);
                outputStream.write(("\r\n--" + boundary + "--\r\n").getBytes());
                byte[] multipartBody = outputStream.toByteArray();

                String apiUrl = config.imageUploaderUrl != null && !config.imageUploaderUrl.isBlank()
                        ? config.imageUploaderUrl
                        : "https://catbox.moe/user/api.php";

                HttpRequest uploadRequest = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .timeout(Duration.ofSeconds(config.imageTimeoutSeconds))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .version(HttpClient.Version.HTTP_1_1)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                        .build();
                HttpResponse<String> uploadResponse = httpClient.send(uploadRequest, HttpResponse.BodyHandlers.ofString());
                if (uploadResponse.statusCode() >= 200 && uploadResponse.statusCode() < 300) {
                    String responseBody = uploadResponse.body().trim();
                    if (responseBody.startsWith("http://") || responseBody.startsWith("https://")) {
                        return responseBody;
                    } else {
                        LOGGER.error("[AiChatMod] Catbox upload returned invalid response: {}", responseBody);
                        return null;
                    }
                } else {
                    LOGGER.error("[AiChatMod] Catbox upload failed: HTTP {} {}", uploadResponse.statusCode(), uploadResponse.body());
                    if (uploadResponse.statusCode() >= 400 && uploadResponse.statusCode() < 500) {
                        return null;
                    }
                }
            } catch (Exception e) {
                lastException = e;
                LOGGER.warn("[AiChatMod] Catbox upload attempt {} failed: {}", attempt, e.getMessage());
            }

            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (lastException != null) {
            LOGGER.error("[AiChatMod] Catbox upload error after retries", lastException);
        }
        return null;
    }

    private String uploadTo0x0(String imageUrl) {
        int maxAttempts = 2;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest downloadRequest = HttpRequest.newBuilder()
                        .uri(URI.create(imageUrl))
                        .timeout(Duration.ofSeconds(config.imageTimeoutSeconds))
                        .version(HttpClient.Version.HTTP_1_1)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .GET()
                        .build();
                HttpResponse<byte[]> downloadResponse = httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofByteArray());
                if (downloadResponse.statusCode() < 200 || downloadResponse.statusCode() >= 300) {
                    LOGGER.error("[AiChatMod] 0x0.st upload: failed to download image, HTTP {}", downloadResponse.statusCode());
                    return null;
                }
                byte[] imageData = downloadResponse.body();

                String boundary = "----AiChatMod0x0" + UUID.randomUUID();
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                outputStream.write(("--" + boundary + "\r\n").getBytes());
                outputStream.write("Content-Disposition: form-data; name=\"file\"; filename=\"image.png\"\r\n".getBytes());
                outputStream.write("Content-Type: image/png\r\n\r\n".getBytes());
                outputStream.write(imageData);
                outputStream.write(("\r\n--" + boundary + "--\r\n").getBytes());
                byte[] multipartBody = outputStream.toByteArray();

                String apiUrl = config.imageUploaderUrl != null && !config.imageUploaderUrl.isBlank()
                        ? config.imageUploaderUrl
                        : "https://0x0.st";

                HttpRequest uploadRequest = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .timeout(Duration.ofSeconds(config.imageTimeoutSeconds))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .version(HttpClient.Version.HTTP_1_1)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                        .build();
                HttpResponse<String> uploadResponse = httpClient.send(uploadRequest, HttpResponse.BodyHandlers.ofString());
                if (uploadResponse.statusCode() >= 200 && uploadResponse.statusCode() < 300) {
                    String responseBody = uploadResponse.body().trim();
                    if (responseBody.startsWith("http://") || responseBody.startsWith("https://")) {
                        return responseBody;
                    } else {
                        LOGGER.error("[AiChatMod] 0x0.st upload returned invalid response: {}", responseBody);
                        return null;
                    }
                } else {
                    LOGGER.error("[AiChatMod] 0x0.st upload failed: HTTP {} {}", uploadResponse.statusCode(), uploadResponse.body());
                    if (uploadResponse.statusCode() >= 400 && uploadResponse.statusCode() < 500) {
                        return null;
                    }
                }
            } catch (Exception e) {
                lastException = e;
                LOGGER.warn("[AiChatMod] 0x0.st upload attempt {} failed: {}", attempt, e.getMessage());
            }

            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (lastException != null) {
            LOGGER.error("[AiChatMod] 0x0.st upload error after retries", lastException);
        }
        return null;
    }

    private String uploadToImgbb(String imageUrl) {
        if (config.imageUploaderUrl == null || config.imageUploaderUrl.isBlank()) {
            LOGGER.error("[AiChatMod] imgbb upload requires imageUploaderUrl with API key.");
            return null;
        }

        int maxAttempts = 2;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest downloadRequest = HttpRequest.newBuilder()
                        .uri(URI.create(imageUrl))
                        .timeout(Duration.ofSeconds(config.imageTimeoutSeconds))
                        .version(HttpClient.Version.HTTP_1_1)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .GET()
                        .build();
                HttpResponse<byte[]> downloadResponse = httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofByteArray());
                if (downloadResponse.statusCode() < 200 || downloadResponse.statusCode() >= 300) {
                    LOGGER.error("[AiChatMod] imgbb upload: failed to download image, HTTP {}", downloadResponse.statusCode());
                    return null;
                }
                byte[] imageData = downloadResponse.body();

                String boundary = "----AiChatModImgbb" + UUID.randomUUID();
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                outputStream.write(("--" + boundary + "\r\n").getBytes());
                outputStream.write("Content-Disposition: form-data; name=\"image\"; filename=\"image.png\"\r\n".getBytes());
                outputStream.write("Content-Type: image/png\r\n\r\n".getBytes());
                outputStream.write(imageData);
                outputStream.write(("\r\n--" + boundary + "--\r\n").getBytes());
                byte[] multipartBody = outputStream.toByteArray();

                HttpRequest uploadRequest = HttpRequest.newBuilder()
                        .uri(URI.create(config.imageUploaderUrl))
                        .timeout(Duration.ofSeconds(config.imageTimeoutSeconds))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .version(HttpClient.Version.HTTP_1_1)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                        .build();
                HttpResponse<String> uploadResponse = httpClient.send(uploadRequest, HttpResponse.BodyHandlers.ofString());
                if (uploadResponse.statusCode() >= 200 && uploadResponse.statusCode() < 300) {
                    String url = extractUrlFromResponse(uploadResponse.body());
                    if (url != null && !url.isBlank()) {
                        return url;
                    }
                } else {
                    LOGGER.error("[AiChatMod] imgbb upload failed: HTTP {} {}", uploadResponse.statusCode(), uploadResponse.body());
                    if (uploadResponse.statusCode() >= 400 && uploadResponse.statusCode() < 500) {
                        return null;
                    }
                }
            } catch (Exception e) {
                lastException = e;
                LOGGER.warn("[AiChatMod] imgbb upload attempt {} failed: {}", attempt, e.getMessage());
            }

            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (lastException != null) {
            LOGGER.error("[AiChatMod] imgbb upload error after retries", lastException);
        }
        return null;
    }

    private String uploadImageToCustomHost(String imageUrl, String uploaderUrl) {
        try {
            HttpRequest downloadRequest = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .timeout(Duration.ofSeconds(config.imageTimeoutSeconds))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET()
                    .build();
            HttpResponse<byte[]> downloadResponse = httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (downloadResponse.statusCode() < 200 || downloadResponse.statusCode() >= 300) {
                LOGGER.error("[AiChatMod] Custom upload: failed to download image, HTTP {}", downloadResponse.statusCode());
                return null;
            }
            byte[] imageData = downloadResponse.body();

            String boundary = "----AiChatModCustom" + UUID.randomUUID();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            outputStream.write(("--" + boundary + "\r\n").getBytes());
            outputStream.write("Content-Disposition: form-data; name=\"file\"; filename=\"image.png\"\r\n".getBytes());
            outputStream.write("Content-Type: image/png\r\n\r\n".getBytes());
            outputStream.write(imageData);
            outputStream.write(("\r\n--" + boundary + "--\r\n").getBytes());
            byte[] multipartBody = outputStream.toByteArray();

            HttpRequest uploadRequest = HttpRequest.newBuilder()
                    .uri(URI.create(uploaderUrl))
                    .timeout(Duration.ofSeconds(config.imageTimeoutSeconds))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .version(HttpClient.Version.HTTP_1_1)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                    .build();
            HttpResponse<String> uploadResponse = httpClient.send(uploadRequest, HttpResponse.BodyHandlers.ofString());
            if (uploadResponse.statusCode() >= 200 && uploadResponse.statusCode() < 300) {
                return extractUrlFromResponse(uploadResponse.body());
            } else {
                LOGGER.error("[AiChatMod] Custom image upload failed: HTTP {} {}", uploadResponse.statusCode(), uploadResponse.body());
                return null;
            }
        } catch (Exception e) {
            LOGGER.error("[AiChatMod] Custom image upload error", e);
            return null;
        }
    }

    private String extractUrlFromResponse(String responseBody) {
        try {
            JsonObject obj = JsonParser.parseString(responseBody).getAsJsonObject();
            if (obj.has("url") && !obj.get("url").isJsonNull()) {
                return obj.get("url").getAsString();
            }
            if (obj.has("data") && obj.get("data").isJsonObject()) {
                JsonObject data = obj.getAsJsonObject("data");
                if (data.has("url") && !data.get("url").isJsonNull()) {
                    return data.get("url").getAsString();
                }
                if (data.has("link") && !data.get("link").isJsonNull()) {
                    return data.get("link").getAsString();
                }
                if (data.has("image") && data.get("image").isJsonObject()) {
                    JsonObject image = data.getAsJsonObject("image");
                    if (image.has("url") && !image.get("url").isJsonNull()) {
                        return image.get("url").getAsString();
                    }
                }
            }
            if (responseBody.trim().startsWith("http://") || responseBody.trim().startsWith("https://")) {
                return responseBody.trim();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String s = baseUrl.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "/";
    }

    // ========== 普通 AI 回复相关 ==========
    private void startAiReply(String userMessage, long userMessageTime, boolean isTrigger) {
        AtomicBoolean replyingFlag = isTrigger ? triggerReplying : scheduleReplying;
        if (!replyingFlag.compareAndSet(false, true)) {
            LOGGER.info("[AiChatMod] {} reply already in progress; skip.", isTrigger ? "Trigger" : "Schedule");
            return;
        }

        AiProvider provider = isTrigger ? triggerProvider : scheduleProvider;
        if (provider == null) {
            LOGGER.error("[AiChatMod] AI provider is null for {}", isTrigger ? "trigger" : "schedule");
            if (config.chatLogEnabled && config.chatLogError) {
                sendLocalChatMessage("§c[AiChatMod] §fAI 提供商未正确配置。");
            }
            replyingFlag.set(false);
            return;
        }

        try {
            if (config == null || !config.enabled) {
                replyingFlag.set(false);
                return;
            }
            if (userMessage == null || userMessage.isBlank()) {
                replyingFlag.set(false);
                return;
            }
            if (!isInGame()) {
                replyingFlag.set(false);
                return;
            }

            List<ChatMessage> requestMessages = buildContext(userMessage);
            String systemPrompt = config.systemPrompt;

            LOGGER.info("[AiChatMod] Requesting AI ({}, model={}, type={})...", config.provider, config.model, isTrigger ? "trigger" : "schedule");

            provider.sendRequest(systemPrompt, requestMessages, config)
                    .whenComplete((reply, error) -> {
                        Minecraft client = Minecraft.getInstance();
                        client.execute(() -> {
                            try {
                                if (error != null) {
                                    LOGGER.error("[AiChatMod] AI request failed", error);
                                    if (config.chatLogEnabled && config.chatLogError) {
                                        sendLocalChatMessage("§c[AiChatMod] §fAI 请求失败: " + error.getMessage());
                                    }
                                    return;
                                }
                                if (reply == null || reply.isBlank()) return;
                                if (!isInGame()) return;

                                handleAiReply(reply, userMessageTime);
                            } finally {
                                replyingFlag.set(false);
                            }
                        });
                    });
        } catch (Exception e) {
            LOGGER.error("[AiChatMod] Unexpected error in startAiReply", e);
            replyingFlag.set(false);
        }
    }

    private void handleAiReply(String reply, long userMessageTime) {
        List<String> chunks = splitReply(reply);
        if (chunks.isEmpty()) return;

        int sent = 0;
        for (String chunk : chunks) {
            if (isBlocked(chunk)) {
                LOGGER.info("[AiChatMod] Skipping blocked AI message: {}", chunk);
                if (config.chatLogEnabled && config.chatLogBlocked) {
                    sendLocalChatMessage("§e[AiChatMod] §f已拦截 AI 消息: " + chunk);
                }
                continue;
            }
            sendChatMessage(chunk);
            recentAiMessages.put(chunk, System.currentTimeMillis());
            sent++;
        }

        if (sent > 0 && config.contextEnabled && config.contextLength > 0) {
            addToContext(new ChatMessage("assistant", reply));
        }

        nextScheduledReplyTime.set(System.currentTimeMillis() + config.scheduleIntervalSeconds * 1000L);
        LOGGER.info("[AiChatMod] Sent {} AI message(s).", sent);
    }

    private List<ChatMessage> buildContext(String currentUserMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        if (config.contextEnabled && config.contextLength > 0) {
            synchronized (context) {
                messages.addAll(context);
            }
            if (messages.isEmpty()) messages.add(new ChatMessage("user", currentUserMessage));
            while (messages.size() > config.contextLength) messages.remove(0);
        } else {
            messages.add(new ChatMessage("user", currentUserMessage));
        }
        return messages;
    }

    private void addToContext(ChatMessage msg) {
        if (config.contextLength <= 0) return;
        synchronized (context) {
            context.addLast(msg);
            while (context.size() > config.contextLength) context.removeFirst();
        }
    }

    private boolean matchesTrigger(String text) {
        Pattern pattern = triggerPattern;
        if (pattern == null) {
            LOGGER.warn("[AiChatMod] Trigger regex pattern is null.");
            return false;
        }
        return pattern.matcher(text).find();
    }

    private void compilePatterns() {
        try {
            triggerPattern = Pattern.compile(config.triggerRegex);
            LOGGER.info("[AiChatMod] Trigger regex compiled: '{}'", config.triggerRegex);
        } catch (PatternSyntaxException e) {
            triggerPattern = null;
            LOGGER.error("[AiChatMod] Invalid trigger regex: '{}'", config.triggerRegex, e);
        }

        try {
            imageTriggerPattern = Pattern.compile(config.imageTriggerRegex);
            LOGGER.info("[AiChatMod] Image trigger regex compiled: '{}'", config.imageTriggerRegex);
        } catch (PatternSyntaxException e) {
            imageTriggerPattern = null;
            LOGGER.error("[AiChatMod] Invalid image trigger regex: '{}'", config.imageTriggerRegex, e);
        }

        List<Pattern> patterns = new ArrayList<>();
        if (config.blockedRegexPatterns != null) {
            for (String patternStr : config.blockedRegexPatterns) {
                try {
                    patterns.add(Pattern.compile(patternStr));
                } catch (PatternSyntaxException e) {
                    LOGGER.error("[AiChatMod] Invalid blocked regex: '{}'", patternStr, e);
                }
            }
        }
        blockedPatterns = List.copyOf(patterns);
    }

    private boolean isBlocked(String text) {
        if (!config.restrictionEnabled) return false;
        for (Pattern pattern : blockedPatterns) {
            if (pattern.matcher(text).find()) return true;
        }
        return false;
    }

    private List<String> splitReply(String reply) {
        List<String> result = new ArrayList<>();
        if (reply == null) return result;

        String normalized = reply.replace("\r\n", "\n").trim();
        if (normalized.isEmpty()) return result;

        int maxChars = Math.max(1, config.maxCharsPerMessage);
        int maxMessages = Math.max(1, config.maxReplyMessages);

        String[] paragraphs = normalized.split("\n");
        for (String paragraph : paragraphs) {
            paragraph = paragraph.trim();
            if (paragraph.isEmpty()) continue;

            while (paragraph.length() > maxChars && result.size() < maxMessages) {
                int cut = maxChars;
                int space = paragraph.lastIndexOf(' ', maxChars);
                if (space > 0) cut = space;
                String chunk = paragraph.substring(0, cut).trim();
                if (chunk.isEmpty()) break;
                result.add(chunk);
                paragraph = paragraph.substring(cut).trim();
            }
            if (!paragraph.isEmpty() && result.size() < maxMessages) result.add(paragraph);
            if (result.size() >= maxMessages) break;
        }
        return result;
    }

    private void sendChatMessage(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getConnection() == null) {
            LOGGER.warn("[AiChatMod] Not connected to a world; cannot send AI chat message.");
            return;
        }
        if (text.length() > 256) text = text.substring(0, 256);
        client.getConnection().sendChat(text);
    }

    private void sendLocalChatMessage(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.player != null) {
            client.player.sendSystemMessage(Component.literal(message));
        }
    }

    private void cleanupRecentAiMessages() {
        long now = System.currentTimeMillis();
        recentAiMessages.entrySet().removeIf(entry -> now - entry.getValue() > 30_000);
        recentPlayerMessages.entrySet().removeIf(entry -> now - entry.getValue() > 5000); // 去重记录保留5秒
    }
}