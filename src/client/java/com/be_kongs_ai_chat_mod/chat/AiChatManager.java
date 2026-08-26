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

    // 通用聊天前缀正则：<名前> / [称号]名前 » / |称号]名前»
    private static final Pattern GENERIC_CHAT_PREFIX =
            Pattern.compile("^\\s*(?:[<\\[][^\\]<>]{0,32}[>\\]]|\\|[^»>]{0,64}[»>])\\s*[:：]?\\s*");

    // 提取玩家名：|[称号]玩家名 » 或 [称号]玩家名 »
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

    private volatile Pattern[] triggerPatterns;
    private volatile Pattern imageTriggerPattern;
    private volatile Pattern imageUrlPattern;
    private volatile List<Pattern> blockedPatterns = List.of();

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
        LOGGER.info("[AiChatMod] AiChatManager initialized. triggerEnabled={}, imageEnabled={}",
                config.triggerEnabled, config.imageGenerationEnabled);
    }

    public void reloadConfig(AiChatConfig newConfig) {
        this.config = newConfig;
        this.triggerProvider = safeCreateProvider(newConfig, newConfig.triggerApiKey);
        this.scheduleProvider = safeCreateProvider(newConfig, newConfig.scheduleApiKey);
        compilePatterns();

        synchronized (context) { context.clear(); }
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
        synchronized (context) { context.clear(); }
        LOGGER.info("[AiChatMod] Context cleared.");
        if (config.chatLogEnabled && config.chatLogClearContext) {
            sendLocalChatMessage("§a[AiChatMod] §f上下文已清空。");
        }
    }

    private AiProvider safeCreateProvider(AiChatConfig cfg, String apiKey) {
        try {
            return AiProviderFactory.create(cfg, apiKey);
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

    // ── 定时调度器 ──────────────────────────────────────────────────────────────

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

    // ── 消息处理入口 ────────────────────────────────────────────────────────────

    public void onPlayerChatMessage(String text) {
        onPlayerChatMessage(text, null);
    }

    public void onPlayerChatMessage(String text, GameProfile sender) {
        if (text == null || text.isBlank()) return;
        if (!isInGame()) return;

        String originalText = text;

        // 1. 获取玩家名
        String playerName = (sender != null) ? getGameProfileName(sender) : null;
        if (playerName == null) {
            playerName = extractPlayerNameFromRawText(originalText);
        }

        // 2. 过滤自身消息（但放行自己发送的特殊触发词，如"关闭触发回复"）
        if (isSelfPlayerName(playerName)) {
            String strippedText = stripPlayerPrefix(text, sender).trim();
            boolean isAiEcho = recentAiMessages.containsKey(strippedText);
            if (!isAiEcho && checkSpecialTriggers(strippedText, sender)) {
                return; // 自己发送的配置短语，已处理
            }
            if (config.debugLog) LOGGER.info("[AiChatMod] Ignoring self message from '{}'", playerName);
            return;
        }

        // 3. 黑名单检查
        if (isPlayerBlocked(playerName)) {
            if (config.debugLog) LOGGER.info("[AiChatMod] Ignoring blocked player '{}'", playerName);
            return;
        }

        // 4. 白名单检查（whitelistMode=true 时启用）
        if (config.whitelistMode && !isPlayerWhitelisted(playerName)) {
            if (config.debugLog) LOGGER.info("[AiChatMod] Player '{}' not in whitelist, ignoring.", playerName);
            return;
        }

        // 5. 剥离前缀（兼容多种聊天格式）
        text = stripPlayerPrefix(text, sender);

        // 6. GAME 事件非玩家消息过滤
        if (sender == null && config.filterGameEvents && text.equals(originalText)) {
            if (config.debugLog) LOGGER.info("[AiChatMod] Ignoring non-player GAME message: '{}'", originalText);
            return;
        }

        // 7. 去重
        String dedupeKey = (playerName == null ? "null" : playerName) + "|" + text;
        long now = System.currentTimeMillis();
        Long lastTime = recentPlayerMessages.get(dedupeKey);
        if (lastTime != null && now - lastTime < 2000L) {
            if (config.debugLog) LOGGER.info("[AiChatMod] Duplicate message ignored (player='{}', text='{}')", playerName, text);
            return;
        }
        recentPlayerMessages.put(dedupeKey, now);

        if (config.debugLog && !originalText.equals(text)) {
            LOGGER.info("[AiChatMod] Stripped chat prefix: '{}' -> '{}'", originalText, text);
            if (config.chatLogEnabled && config.chatLogDebug) {
                sendLocalChatMessage("§a[AiChatMod] §f前缀剥离: " + originalText + " -> " + text);
            }
        }

        // 8. 检测图片 URL（先于触发检查）
        if (config.imageExtractEnabled) {
            String imageUrl = extractImageUrl(text);
            if (imageUrl != null && !imageUrl.isBlank()) {
                // 新逻辑：将图片 URL 发送给 AI 进行识别（Vision API）
                if (config.chatLogEnabled && config.chatLogTrigger) {
                    sendLocalChatMessage("§a[AiChatMod] §f检测到图片 URL，发送给 AI 识别。");
                }
                // 发送图片给 AI 识别（同时带上原始消息，便于提取玩家的实际提问）
                handleImageUrlForRecognition(imageUrl, text, sender);
                return; // 图片识别后不再处理触发
            }
        }

        // 9. 文生图触发检查
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

        // 10. 检查特殊触发词（修改配置）
        if (checkSpecialTriggers(text, sender)) {
            // 特殊触发词已处理，不再继续
            return;
        }

        // 11. 更新最后消息记录（供自动回复使用）
        lastPlayerMessage.set(text);
        lastPlayerMessageTime.set(System.currentTimeMillis());

        if (config == null || !config.enabled) return;

        // 12. 加入上下文
        if (config.contextEnabled && config.contextLength > 0) {
            addToContext(new ChatMessage("user", text));
        }

        // 13. 触发回复检查（使用新的触发词列表）
        boolean matches = config.triggerEnabled && matchesTriggerList(text);
        boolean cooldownPassed = nextTriggerReplyTime.get() <= System.currentTimeMillis();

        if (config.debugLog) {
            LOGGER.info("[AiChatMod] Message='{}', triggerMatch={}, cooldownPassed={}", text, matches, cooldownPassed);
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

    // ── 玩家名提取 ────────────────────────────────────────────────────────────

    private String extractPlayerNameFromRawText(String rawText) {
        Matcher m = PLAYER_NAME_PATTERN.matcher(rawText);
        if (m.find()) return m.group(1);

        // 尝试 <玩家名> 或 [玩家名]（无称号）
        Pattern p2 = Pattern.compile("^\\s*[<\\[]\\s*([^\\]<>]+)\\s*[>\\]]");
        Matcher m2 = p2.matcher(rawText);
        if (m2.find()) return m2.group(1).trim();
        return null;
    }

    private boolean isPlayerBlocked(String playerName) {
        if (playerName == null || config.blockedPlayers == null) return false;
        for (String blocked : config.blockedPlayers) {
            if (blocked != null && blocked.equalsIgnoreCase(playerName)) return true;
        }
        return false;
    }

    private boolean isPlayerWhitelisted(String playerName) {
        if (playerName == null || config.whitelistedPlayers == null || config.whitelistedPlayers.isEmpty()) return true;
        for (String whitelisted : config.whitelistedPlayers) {
            if (whitelisted != null && whitelisted.equalsIgnoreCase(playerName)) return true;
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

    // ── 前缀剥离（兼容多种服务器聊天格式）────────────────────────────────────────

    private String stripPlayerPrefix(String text, GameProfile sender) {
        if (!config.stripChatPrefix) return text;

        String playerName = getGameProfileName(sender);
        if (playerName != null) {
            // 精确前缀：<名前> / [称号]名前
            String quotedName = Pattern.quote(playerName);
            Pattern prefixPattern = Pattern.compile(
                    "^<[^<>]*" + quotedName + "[^<>]*>\\s*|^\\[[^\\[\\]]*" + quotedName + "[^\\[\\]]*\\]\\s*"
            );
            Matcher matcher = prefixPattern.matcher(text);
            if (matcher.find()) {
                String stripped = text.substring(matcher.end()).trim();
                if (!stripped.isEmpty()) return stripped;
            }

            // 反向查找括号边界
            int nameIndex = text.indexOf(playerName);
            if (nameIndex > 0) {
                int start = -1;
                for (int i = nameIndex - 1; i >= 0; i--) {
                    char c = text.charAt(i);
                    if (c == '<' || c == '[') { start = i; break; }
                }
                int end = -1;
                for (int i = nameIndex + playerName.length(); i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '>' || c == ']') { end = i + 1; break; }
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

    // ── 图片 URL 检测 ───────────────────────────────────────────────────────────

    private String extractImageUrl(String text) {
        if (imageUrlPattern == null) return null;
        Matcher m = imageUrlPattern.matcher(text);
        if (m.find()) {
            String url = m.group(0).trim();
            // 去掉紧随其后的标点
            url = url.replaceAll("[.,;:!?）]$", "");
            return url.isBlank() ? null : url;
        }
        return null;
    }

    // ── 新增：处理图片 URL 用于 AI 识别 ────────────────────────────────────────

    private void handleImageUrlForRecognition(String imageUrl, String fullText, GameProfile sender) {
        // 发送图片 URL 给 AI 进行识别（使用 Vision API）
        lastHandledUserMessageTime.set(System.currentTimeMillis());
        lastPlayerMessage.set(fullText != null && !fullText.isBlank() ? fullText : imageUrl);
        lastPlayerMessageTime.set(System.currentTimeMillis());

        if (config.chatLogEnabled && config.chatLogTrigger) {
            sendLocalChatMessage("§a[AiChatMod] §f正在识别图片内容...");
        }

        // 优先使用玩家的实际提问（去掉 URL 后的文字），否则回退到默认描述提示词。
        // 这样"图中有什么/这是什么"等问题能一并传给 AI，而不是固定的"请描述图片"。
        String prompt = "请描述这张图片的内容。";
        if (fullText != null && imageUrl != null) {
            String question = fullText.replace(imageUrl, "").trim();
            if (!question.isBlank()) prompt = question;
        }

        startImageRecognitionReply(prompt, imageUrl, sender);
    }

    private void startImageRecognitionReply(String prompt, String imageUrl, GameProfile sender) {
        AtomicBoolean replyingFlag = triggerReplying; // 使用触发回复的锁
        if (!replyingFlag.compareAndSet(false, true)) {
            LOGGER.info("[AiChatMod] Image recognition reply already in progress; skip.");
            return;
        }

        AiProvider provider = triggerProvider;
        if (provider == null) {
            LOGGER.error("[AiChatMod] AI provider is null for image recognition");
            if (config.chatLogEnabled && config.chatLogError) {
                sendLocalChatMessage("§c[AiChatMod] §fAI 提供商未正确配置。");
            }
            replyingFlag.set(false);
            return;
        }

        try {
            if (config == null || !config.enabled) { replyingFlag.set(false); return; }
            if (!isInGame()) { replyingFlag.set(false); return; }

            // 创建多模态消息：文本提示 + 图片 URL
            ChatMessage userMessage = ChatMessage.withImage("user", prompt, imageUrl);
            List<ChatMessage> requestMessages = buildContextForImage(userMessage);
            String systemPrompt = config.systemPrompt;

            LOGGER.info("[AiChatMod] Requesting AI image recognition (model={})...", config.model);

            provider.sendRequest(systemPrompt, requestMessages, config)
                    .whenComplete((reply, error) -> {
                        Minecraft client = Minecraft.getInstance();
                        client.execute(() -> {
                            try {
                                if (error != null) {
                                    LOGGER.error("[AiChatMod] AI image recognition request failed", error);
                                    if (config.chatLogEnabled && config.chatLogError) {
                                        sendLocalChatMessage("§c[AiChatMod] §f图片识别失败: " + error.getMessage());
                                    }
                                    return;
                                }
                                if (reply == null || reply.isBlank()) return;
                                if (!isInGame()) return;
                                handleImageRecognitionReply(reply, prompt, sender);
                            } finally {
                                replyingFlag.set(false);
                            }
                        });
                    });
        } catch (Exception e) {
            LOGGER.error("[AiChatMod] Unexpected error in startImageRecognitionReply", e);
            replyingFlag.set(false);
        }
    }

    private void handleImageRecognitionReply(String reply, String prompt, GameProfile sender) {
        if (reply == null || reply.isBlank()) return;
        // 记录用户的图片提问（纯文本、不含图片），保持上下文连贯，
        // 同时避免后续请求重复携带图片。
        if (config.contextEnabled && config.contextLength > 0) {
            addToContext(new ChatMessage("user", prompt));
        }
        // 走统一的回复管线：分段、拦截过滤、非法字符净化、上下文记录（assistant 侧）
        handleAiReply(reply, System.currentTimeMillis());
        if (config.chatLogEnabled && config.chatLogTrigger) {
            sendLocalChatMessage("§a[AiChatMod] §f图片识别完成。");
        }
    }

    private List<ChatMessage> buildContextForImage(ChatMessage currentUserMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        if (config.contextEnabled && config.contextLength > 0) {
            synchronized (context) { messages.addAll(context); }
            // 给当前的图片消息留出位置，确保它总能作为最后一条附加进去；
            // 旧实现只在上下文为空时才附加当前消息，导致上下文非空时图片请求
            // 里根本没有图片，AI 只会回答上下文里的旧消息。
            while (messages.size() >= config.contextLength) messages.remove(0);
        }
        // 始终把当前的多模态消息（文本 + 图片）放在请求末尾
        messages.add(currentUserMessage);
        return messages;
    }

    // ── 新增：检查特殊触发词（修改配置） ──────────────────────────────────────

    private boolean checkSpecialTriggers(String text, GameProfile sender) {
        if (config == null || config.configTriggers == null || text == null) return false;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return false;

        for (AiChatConfig.ConfigTrigger trigger : config.configTriggers) {
            if (trigger == null || trigger.regex == null || trigger.regex.isBlank()) continue;
            try {
                Pattern pattern = Pattern.compile(trigger.regex);
                Matcher matcher = pattern.matcher(trimmed);
                if (matcher.find()) {
                    // 找到匹配的特殊触发词，执行对应动作（反馈消息由动作内部按结果发送）
                    executeConfigTriggerAction(trigger.action, trigger.description, sender);
                    return true; // 已处理，不再继续
                }
            } catch (PatternSyntaxException e) {
                LOGGER.error("[AiChatMod] Invalid config trigger regex: '{}'", trigger.regex, e);
            }
        }
        return false;
    }

    private void executeConfigTriggerAction(AiChatConfig.ConfigTriggerAction action,
                                            String description, GameProfile sender) {
        if (config == null || action == null) return;

        String resultMsg = null;   // 执行结果反馈
        boolean configMutated = false; // 是否修改了配置（需要保存 + 热重载）

        switch (action) {
            case DISABLE_TRIGGER:
                config.triggerEnabled = false;
                configMutated = true;
                resultMsg = "触发回复已关闭。";
                break;
            case ENABLE_TRIGGER:
                config.triggerEnabled = true;
                configMutated = true;
                resultMsg = "触发回复已开启。";
                break;
            case CLEAR_CONTEXT:
                clearContext();
                resultMsg = "上下文已清空。";
                break;
            case RELOAD_CONFIG: {
                AiChatConfig reloadedConfig = AiChatConfig.load();
                BeKongsAiChatMod.config = reloadedConfig;
                reloadConfig(reloadedConfig);
                resultMsg = "配置已重载。";
                break;
            }
            case TOGGLE_ENABLED:
                config.enabled = !config.enabled;
                configMutated = true;
                resultMsg = "模组总开关已" + (config.enabled ? "开启" : "关闭") + "。";
                break;
            case DISABLE_ENABLED:
                config.enabled = false;
                configMutated = true;
                resultMsg = "模组已关闭。";
                break;
            case ENABLE_ENABLED:
                config.enabled = true;
                configMutated = true;
                resultMsg = "模组已开启。";
                break;
            case DISABLE_SCHEDULE:
                config.scheduleEnabled = false;
                configMutated = true;
                resultMsg = "自动回复已关闭。";
                break;
            case ENABLE_SCHEDULE:
                config.scheduleEnabled = true;
                configMutated = true;
                resultMsg = "自动回复已开启。";
                break;
            case DISABLE_IMAGE_GENERATION:
                config.imageGenerationEnabled = false;
                configMutated = true;
                resultMsg = "文生图已关闭。";
                break;
            case ENABLE_IMAGE_GENERATION:
                config.imageGenerationEnabled = true;
                configMutated = true;
                resultMsg = "文生图已开启。";
                break;
            case DISABLE_IMAGE_EXTRACT:
                config.imageExtractEnabled = false;
                configMutated = true;
                resultMsg = "图片识别已关闭。";
                break;
            case ENABLE_IMAGE_EXTRACT:
                config.imageExtractEnabled = true;
                configMutated = true;
                resultMsg = "图片识别已开启。";
                break;
            // ── 兼容旧配置的"切换"动作 ──
            case TOGGLE_SCHEDULE:
                config.scheduleEnabled = !config.scheduleEnabled;
                configMutated = true;
                resultMsg = "自动回复已" + (config.scheduleEnabled ? "开启" : "关闭") + "。";
                break;
            case TOGGLE_IMAGE_GENERATION:
                config.imageGenerationEnabled = !config.imageGenerationEnabled;
                configMutated = true;
                resultMsg = "文生图已" + (config.imageGenerationEnabled ? "开启" : "关闭") + "。";
                break;
            case TOGGLE_IMAGE_EXTRACT:
                config.imageExtractEnabled = !config.imageExtractEnabled;
                configMutated = true;
                resultMsg = "图片识别已" + (config.imageExtractEnabled ? "开启" : "关闭") + "。";
                break;
        }

        // 修改了配置的动作：落盘并热重载（RELOAD_CONFIG 已在分支内自行处理）
        if (configMutated) {
            config.save();
            BeKongsAiChatMod.config = config;
            reloadConfig(config);
        }

        if (resultMsg != null) {
            LOGGER.info("[AiChatMod] Config trigger '{}' executed: {}", description, resultMsg);
            if (config.chatLogEnabled && config.chatLogTrigger) {
                sendLocalChatMessage("§a[AiChatMod] §f" + resultMsg);
            }
        }
    }

    // ── 文生图 ────────────────────────────────────────────────────────────────

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
                if (group != null && !group.isBlank()) return group.trim();
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
                if (imageUrl == null || imageUrl.isBlank()) return null;

                String uploaderType = config.imageUploaderType;
                if (uploaderType == null) uploaderType = "none";

                if ("catbox".equals(uploaderType)) {
                    String uploadedUrl = uploadToCatbox(imageUrl);
                    if (uploadedUrl != null && !uploadedUrl.isBlank()) return uploadedUrl;
                    LOGGER.warn("[AiChatMod] Catbox upload failed, falling back to original URL.");
                } else if ("0x0".equals(uploaderType)) {
                    String uploadedUrl = uploadTo0x0(imageUrl);
                    if (uploadedUrl != null && !uploadedUrl.isBlank()) return uploadedUrl;
                    LOGGER.warn("[AiChatMod] 0x0.st upload failed, falling back to original URL.");
                } else if ("imgbb".equals(uploaderType)) {
                    String uploadedUrl = uploadToImgbb(imageUrl);
                    if (uploadedUrl != null && !uploadedUrl.isBlank()) return uploadedUrl;
                    LOGGER.warn("[AiChatMod] imgbb upload failed, falling back to original URL.");
                } else if ("custom".equals(uploaderType)) {
                    String uploaderUrl = config.imageUploaderUrl;
                    if (uploaderUrl != null && !uploaderUrl.isBlank()) {
                        String uploadedUrl = uploadImageToCustomHost(imageUrl, uploaderUrl);
                        if (uploadedUrl != null && !uploadedUrl.isBlank()) return uploadedUrl;
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
        String endpoint = base.contains("images/generations") ? base
                : normalizeBaseUrl(base) + "images/generations";

        String apiKey = config.imageApiKey;
        JsonObject body = new JsonObject();
        body.addProperty("model", config.imageModel);
        body.addProperty("prompt", prompt);
        body.addProperty("size", config.imageSize);
        body.addProperty("ratio", config.imageRatio);
        JsonObject extraBody = new JsonObject();
        extraBody.addProperty("response_format", "url");
        body.add("extra_body", extraBody);

        int retries = config.imageRetryCount;
        while (retries >= 0) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(config.imageTimeoutSeconds))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .version(HttpClient.Version.HTTP_1_1)
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return parseImageResponse(response.body());
                } else {
                    LOGGER.error("[AiChatMod] Image API HTTP {}: {}", response.statusCode(), response.body());
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

    // ── 图床上传 ────────────────────────────────────────────────────────────────

    private String uploadToCatbox(String imageUrl) {
        return uploadWithRetry(imageUrl, "----AiChatModCatbox" + UUID.randomUUID(),
                "fileupload", "fileToUpload", "https://catbox.moe/user/api.php");
    }

    private String uploadTo0x0(String imageUrl) {
        return uploadWithRetry(imageUrl, "----AiChatMod0x0" + UUID.randomUUID(),
                null, "file", "https://0x0.st");
    }

    private String uploadToImgbb(String imageUrl) {
        if (config.imageUploaderUrl == null || config.imageUploaderUrl.isBlank()) {
            LOGGER.error("[AiChatMod] imgbb upload requires imageUploaderUrl with API key.");
            return null;
        }
        return uploadWithRetry(imageUrl, "----AiChatModImgbb" + UUID.randomUUID(),
                null, "image", config.imageUploaderUrl);
    }

    private String uploadImageToCustomHost(String imageUrl, String uploaderUrl) {
        return uploadWithRetry(imageUrl, "----AiChatModCustom" + UUID.randomUUID(),
                null, "file", uploaderUrl);
    }

    private String uploadWithRetry(String imageUrl, String boundary,
                                   String reqType, String fileFieldName, String apiUrl) {
        int maxAttempts = 2;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // 下载图片
                HttpRequest downloadRequest = HttpRequest.newBuilder()
                        .uri(URI.create(imageUrl))
                        .timeout(Duration.ofSeconds(config.imageTimeoutSeconds))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .GET().build();
                HttpResponse<byte[]> downloadResponse = httpClient.send(downloadRequest,
                        HttpResponse.BodyHandlers.ofByteArray());
                if (downloadResponse.statusCode() < 200 || downloadResponse.statusCode() >= 300) {
                    LOGGER.error("[AiChatMod] Upload: failed to download image, HTTP {}", downloadResponse.statusCode());
                    return null;
                }
                byte[] imageData = downloadResponse.body();

                // 构建 multipart body
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                outputStream.write(("--" + boundary + "\r\n").getBytes());
                if (reqType != null) {
                    outputStream.write(("Content-Disposition: form-data; name=\"reqtype\"\r\n\r\n").getBytes());
                    outputStream.write((reqType + "\r\n").getBytes());
                }
                outputStream.write(("Content-Disposition: form-data; name=\"" + fileFieldName + "\"; filename=\"image.png\"\r\n").getBytes());
                outputStream.write("Content-Type: image/png\r\n\r\n".getBytes());
                outputStream.write(imageData);
                outputStream.write(("\r\n--" + boundary + "--\r\n").getBytes());
                byte[] multipartBody = outputStream.toByteArray();

                String targetUrl = apiUrl;
                HttpRequest uploadRequest = HttpRequest.newBuilder()
                        .uri(URI.create(targetUrl))
                        .timeout(Duration.ofSeconds(config.imageTimeoutSeconds))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody)).build();
                HttpResponse<String> uploadResponse = httpClient.send(uploadRequest,
                        HttpResponse.BodyHandlers.ofString());
                if (uploadResponse.statusCode() >= 200 && uploadResponse.statusCode() < 300) {
                    String responseBody = uploadResponse.body().trim();
                    if (responseBody.startsWith("http://") || responseBody.startsWith("https://")) {
                        return responseBody;
                    }
                    String extracted = extractUrlFromResponse(responseBody);
                    if (extracted != null) return extracted;
                    LOGGER.error("[AiChatMod] Upload returned invalid response: {}", responseBody);
                    return null;
                } else {
                    LOGGER.error("[AiChatMod] Upload failed: HTTP {} {}", uploadResponse.statusCode(), uploadResponse.body());
                    if (uploadResponse.statusCode() >= 400 && uploadResponse.statusCode() < 500) return null;
                }
            } catch (Exception e) {
                lastException = e;
                LOGGER.warn("[AiChatMod] Upload attempt {} failed: {}", attempt, e.getMessage());
            }
            if (attempt < maxAttempts) {
                try { Thread.sleep(2000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            }
        }
        if (lastException != null) LOGGER.error("[AiChatMod] Upload error after retries", lastException);
        return null;
    }

    private String extractUrlFromResponse(String responseBody) {
        try {
            JsonObject obj = JsonParser.parseString(responseBody).getAsJsonObject();
            if (obj.has("url") && !obj.get("url").isJsonNull()) return obj.get("url").getAsString();
            if (obj.has("data") && obj.get("data").isJsonObject()) {
                JsonObject data = obj.getAsJsonObject("data");
                if (data.has("url") && !data.get("url").isJsonNull()) return data.get("url").getAsString();
                if (data.has("link") && !data.get("link").isJsonNull()) return data.get("link").getAsString();
            }
            if (responseBody.trim().startsWith("http://") || responseBody.trim().startsWith("https://")) {
                return responseBody.trim();
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String s = baseUrl.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "/";
    }

    // ── AI 回复 ────────────────────────────────────────────────────────────────

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
            if (config == null || !config.enabled) { replyingFlag.set(false); return; }
            if (userMessage == null || userMessage.isBlank()) { replyingFlag.set(false); return; }
            if (!isInGame()) { replyingFlag.set(false); return; }

            List<ChatMessage> requestMessages = buildContext(userMessage);
            String systemPrompt = config.systemPrompt;

            LOGGER.info("[AiChatMod] Requesting AI ({}, model={}, type={})...",
                    config.provider, config.model, isTrigger ? "trigger" : "schedule");

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
            synchronized (context) { messages.addAll(context); }
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

    // ── 模式与过滤 ────────────────────────────────────────────────────────────

    private boolean matchesTriggerList(String text) {
        if (triggerPatterns == null) {
            LOGGER.warn("[AiChatMod] Trigger regex patterns array is null.");
            return false;
        }
        for (Pattern pattern : triggerPatterns) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private void compilePatterns() {
        try {
            // 编译触发词列表
            List<Pattern> patterns = new ArrayList<>();
            if (config.triggerRegexList != null) {
                for (String patternStr : config.triggerRegexList) {
                    try { patterns.add(Pattern.compile(patternStr)); }
                    catch (PatternSyntaxException e) {
                        LOGGER.error("[AiChatMod] Invalid trigger regex: '{}'", patternStr, e);
                    }
                }
            }
            triggerPatterns = patterns.toArray(new Pattern[0]);
            LOGGER.info("[AiChatMod] Trigger regex patterns compiled: {}", 
                    java.util.Arrays.toString(triggerPatterns));
        } catch (Exception e) {
            triggerPatterns = new Pattern[0];
            LOGGER.error("[AiChatMod] Failed to compile trigger regex patterns", e);
        }

        try {
            imageTriggerPattern = Pattern.compile(config.imageTriggerRegex);
            LOGGER.info("[AiChatMod] Image trigger regex compiled: '{}'", config.imageTriggerRegex);
        } catch (PatternSyntaxException e) {
            imageTriggerPattern = null;
            LOGGER.error("[AiChatMod] Invalid image trigger regex: '{}'", config.imageTriggerRegex, e);
        }

        try {
            imageUrlPattern = Pattern.compile(config.imageUrlRegex);
        } catch (PatternSyntaxException e) {
            imageUrlPattern = null;
            LOGGER.error("[AiChatMod] Invalid image URL regex: '{}'", config.imageUrlRegex, e);
        }

        List<Pattern> patterns = new ArrayList<>();
        if (config.blockedRegexPatterns != null) {
            for (String patternStr : config.blockedRegexPatterns) {
                try { patterns.add(Pattern.compile(patternStr)); }
                catch (PatternSyntaxException e) {
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

        for (String paragraph : normalized.split("\n")) {
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

    // ── 消息发送 ────────────────────────────────────────────────────────────────

    private void sendChatMessage(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getConnection() == null) {
            LOGGER.warn("[AiChatMod] Not connected to a world; cannot send AI chat message.");
            return;
        }
        // 净化非法聊天字符（§ 分节符、控制字符等），避免被服务器以
        // "Illegal characters in chat" 踢出（disconnect.endOfStream）
        text = sanitizeChatMessage(text);
        if (text.isEmpty()) {
            LOGGER.info("[AiChatMod] AI message empty after sanitizing; skip sending.");
            return;
        }
        if (text.length() > 256) text = text.substring(0, 256);
        client.getConnection().sendChat(text);
    }

    /**
     * 剥离 Java 版聊天中的非法字符：
     * - §（0xA7 分节符）：复制粘贴发送会被服务端踢出；
     * - 控制字符（< 0x20）：换行/制表符替换为空格，其余丢弃；
     * - DEL（0x7F）。
     * 与原版 SharedConstants.isAllowedChatCharacter 的校验规则对应。
     */
    static String sanitizeChatMessage(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp == 0xA7 || cp == 0x7F) {
                // 分节符 / DEL：直接丢弃
            } else if (cp < 0x20) {
                // 其余控制字符：换行/回车/制表符转为空格，避免词语粘连
                if (cp == '\n' || cp == '\r' || cp == '\t') sb.append(' ');
            } else {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return sb.toString().trim();
    }

    private void sendLocalChatMessage(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.player != null) {
            client.player.sendSystemMessage(Component.literal(message));
        }
    }

    private void cleanupRecentAiMessages() {
        long now = System.currentTimeMillis();
        recentAiMessages.entrySet().removeIf(e -> now - e.getValue() > 30_000);
        recentPlayerMessages.entrySet().removeIf(e -> now - e.getValue() > 5000);
    }
}