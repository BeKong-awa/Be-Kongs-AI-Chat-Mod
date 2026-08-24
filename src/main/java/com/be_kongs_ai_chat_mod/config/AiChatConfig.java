package com.be_kongs_ai_chat_mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class AiChatConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("be-kongs-ai-chat-mod");
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("be-kongs-ai-chat-mod.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ===== 全局开关 =====
    public boolean enabled = true;

    // ===== AI 提供商设置 =====
    public String provider = "agnes";
    public String apiKey = "";
    public String triggerApiKey = "";      // 触发回复使用
    public String scheduleApiKey = "";     // 自动回复使用
    public String baseUrl = "";
    public String model = "agnes-2.5-flash";
    public double temperature = 0.7;
    public int maxTokens = 1024;

    // ===== 回复条数与长度限制 =====
    public int maxReplyMessages = 3;
    public int maxCharsPerMessage = 100;

    // ===== 上下文记忆 =====
    public boolean contextEnabled = true;
    public int contextLength = 20;

    // ===== 触发回复 =====
    public boolean triggerEnabled = true;
    public String triggerRegex = "(?i)^(ai|@ai)[，, ]";
    public int triggerCooldownSeconds = 10;

    // ===== 自动回复（定时冷却） =====
    public boolean scheduleEnabled = true;
    public int scheduleIntervalSeconds = 30;

    // ===== 消息内容过滤 =====
    public boolean restrictionEnabled = true;
    public List<String> blockedRegexPatterns = new ArrayList<>(List.of("^\\s*/", "^\\s*#"));

    // ===== 玩家黑名单 =====
    public List<String> blockedPlayers = new ArrayList<>();

    // ===== 系统提示词 =====
    public String systemPrompt = "你是 Minecraft 聊天中的 AI 助手。回答要简洁，不要使用命令，不要发送以 / 开头的内容。";

    // ===== 网络与重试 =====
    public int timeoutSeconds = 30;
    public int retryCount = 2;
    public boolean debugLog = true;

    // ===== 聊天栏日志 =====
    public boolean chatLogEnabled = true;
    public boolean chatLogTrigger = true;
    public boolean chatLogSchedule = true;
    public boolean chatLogError = true;
    public boolean chatLogBlocked = true;
    public boolean chatLogReload = true;
    public boolean chatLogClearContext = true;
    public boolean chatLogDebug = false;

    // ===== 文生图功能 =====
    public boolean imageGenerationEnabled = true;
    public String imageProvider = "agnes";
    public String imageApiKey = "";
    public String imageBaseUrl = "";
    public String imageModel = "agnes-image-2.1-flash";
    public String imageSize = "2K";
    public String imageRatio = "1:1";
    public int imageTimeoutSeconds = 120;
    public int imageRetryCount = 1;
    public int imageCooldownSeconds = 30;
    public String imageTriggerRegex = "(?i)^(?:画|生成图片|img)[:： ]?(.*)$";

    // ===== 图床上传设置 =====
    public String imageUploaderType = "none";
    public String imageUploaderUrl = "";

    public static AiChatConfig load() {
        AiChatConfig loaded = null;
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                loaded = GSON.fromJson(reader, AiChatConfig.class);
            } catch (Exception e) {
                LOGGER.error("[AiChatMod] Failed to read config file, using defaults.", e);
            }
        }
        if (loaded == null) {
            loaded = new AiChatConfig();
        }
        loaded.validate();
        loaded.save();
        return loaded;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (Exception e) {
            LOGGER.error("[AiChatMod] Failed to save config file.", e);
        }
    }

    public void validate() {
        if (provider == null || provider.isBlank()) provider = "custom";
        provider = provider.toLowerCase();

        if (apiKey == null) apiKey = "";
        if (triggerApiKey == null || triggerApiKey.isBlank()) triggerApiKey = apiKey;
        if (scheduleApiKey == null || scheduleApiKey.isBlank()) scheduleApiKey = apiKey;
        if (baseUrl == null) baseUrl = "";
        if (model == null || model.isBlank()) model = "gpt-3.5-turbo";
        if (systemPrompt == null) systemPrompt = "";
        if (triggerRegex == null || triggerRegex.isBlank()) triggerRegex = "(?i)^(ai|@ai)[，, ]";
        if (blockedRegexPatterns == null) blockedRegexPatterns = new ArrayList<>();
        if (blockedPlayers == null) blockedPlayers = new ArrayList<>();

        temperature = clamp(temperature, 0.0, 2.0);
        maxTokens = clamp(maxTokens, 1, 4096);
        maxReplyMessages = clamp(maxReplyMessages, 1, 10);
        maxCharsPerMessage = clamp(maxCharsPerMessage, 1, 256);
        contextLength = clamp(contextLength, 0, 100);
        scheduleIntervalSeconds = clamp(scheduleIntervalSeconds, 5, 3600);
        triggerCooldownSeconds = clamp(triggerCooldownSeconds, 1, 3600);
        timeoutSeconds = clamp(timeoutSeconds, 5, 120);
        retryCount = clamp(retryCount, 0, 5);

        if (imageProvider == null || imageProvider.isBlank()) imageProvider = "agnes";
        imageProvider = imageProvider.toLowerCase();
        if (imageApiKey == null) imageApiKey = "";
        if (imageBaseUrl == null) imageBaseUrl = "";
        if (imageModel == null || imageModel.isBlank()) imageModel = "agnes-image-2.1-flash";
        if (imageSize == null || imageSize.isBlank()) imageSize = "2K";
        if (imageRatio == null || imageRatio.isBlank()) imageRatio = "1:1";
        if (imageTriggerRegex == null || imageTriggerRegex.isBlank())
            imageTriggerRegex = "(?i)^(?:画|生成图片|img)[:： ]?(.*)$";
        imageTimeoutSeconds = clamp(imageTimeoutSeconds, 30, 3600);
        imageRetryCount = clamp(imageRetryCount, 0, 3);
        imageCooldownSeconds = clamp(imageCooldownSeconds, 5, 3600);

        if (imageUploaderType == null) imageUploaderType = "none";
        imageUploaderType = imageUploaderType.toLowerCase();
        if (!imageUploaderType.equals("none") && !imageUploaderType.equals("catbox")
                && !imageUploaderType.equals("0x0") && !imageUploaderType.equals("imgbb")
                && !imageUploaderType.equals("custom")) {
            imageUploaderType = "none";
        }
        if (imageUploaderUrl == null) imageUploaderUrl = "";

        if (imageApiKey.isEmpty()) imageApiKey = apiKey;
        if (imageBaseUrl.isEmpty()) imageBaseUrl = baseUrl;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}