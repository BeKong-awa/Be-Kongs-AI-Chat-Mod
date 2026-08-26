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

/**
 * AI 聊天模组配置
 */
public class AiChatConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("be-kongs-ai-chat-mod");
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("be-kongs-ai-chat-mod.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ===== 全局开关 =====
    public boolean enabled = true;

    // ===== AI 提供商设置 =====
    public String provider = "agnes";
    public String apiKey = "";
    public String triggerApiKey = "";      // 触发回复专用密钥，留空则使用 apiKey
    public String scheduleApiKey = "";     // 自动回复专用密钥，留空则使用 apiKey
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
    /** 触发正则列表（匹配任意一个即触发） */
    public List<String> triggerRegexList = new ArrayList<>(List.of("(?i)^(ai|@ai)[，, ]"));
    public int triggerCooldownSeconds = 10;

    // ===== 自动回复（定时冷却） =====
    public boolean scheduleEnabled = true;
    public int scheduleIntervalSeconds = 30;

    // ===== 消息内容过滤 =====
    public boolean restrictionEnabled = true;
    public List<String> blockedRegexPatterns = new ArrayList<>(List.of("^\\s*/", "^\\s*#"));

    // ===== 玩家名单 =====
    /** 黑名单：列表中的玩家消息完全忽略。 */
    public List<String> blockedPlayers = new ArrayList<>();
    /** 白名单：当 whitelistMode=true 时，仅列表中的玩家会触发 AI 回复；空列表=允许所有人。 */
    public List<String> whitelistedPlayers = new ArrayList<>();
    /** 白名单模式：false=黑名单过滤；true=仅白名单玩家可触发（白名单为空时允许全部）。 */
    public boolean whitelistMode = false;

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

    // ===== 图片 URL 解析设置 =====
    /** 是否从聊天消息中自动提取图片 URL 并附带说明发送给 AI（让 AI 描述图片内容）。 */
    public boolean imageExtractEnabled = true;
    /** 合并模式：true=检测到图片 URL 时自动追加到正文发送给 AI；false=仅提取 URL 文本本身。 */
    public boolean imageExtractMergeMode = true;
    /** 自动识别的图片 URL 正则。 */
    public String imageUrlRegex = "(?i)https?://[^\\s<>\"']+\\.(?:png|jpg|jpeg|gif|webp|bmp|svg)[^\\s<>\"']*$";

    // ===== 特殊触发词（修改配置） =====
    /** 特殊触发词列表：匹配时执行对应的配置修改操作，不触发 AI 回复 */
    public List<ConfigTrigger> configTriggers = new ArrayList<>();

    // ===== 配置界面热键 =====
    /** 打开配置界面的热键（按键名，如 key.keyboard.c）；留空默认为 C 键。 */
    public String uiHotkey = "key.keyboard.c";

    // ===== 兼容多客户端聊天格式 =====
    /** 是否剥离聊天前缀（<玩家名> / [称号]玩家名 » 等）；部分服务器修改后格式不同可关闭。 */
    public boolean stripChatPrefix = true;
    /** 是否启用 GAME 事件过滤（忽略非玩家消息）；部分服务器插件会广播 GAME 消息可关闭。 */
    public boolean filterGameEvents = true;

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
        if (triggerRegexList == null || triggerRegexList.isEmpty()) triggerRegexList = new ArrayList<>(List.of("(?i)^(ai|@ai)[，, ]"));
        if (blockedRegexPatterns == null) blockedRegexPatterns = new ArrayList<>();
        if (blockedPlayers == null) blockedPlayers = new ArrayList<>();
        if (whitelistedPlayers == null) whitelistedPlayers = new ArrayList<>();

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

        if (imageUrlRegex == null || imageUrlRegex.isBlank())
            imageUrlRegex = "(?i)https?://[^\\s<>\"']+\\.(?:png|jpg|jpeg|gif|webp|bmp|svg)[^\\s<>\"']*$";

        if (uiHotkey == null || uiHotkey.isBlank()) uiHotkey = "key.keyboard.c";
        // 一次性迁移：旧版默认热键是 M 键，自动改为 C 键（仅当值仍是旧默认时）
        if ("key.keyboard.m".equals(uiHotkey)) uiHotkey = "key.keyboard.c";

        // 初始化默认的特殊触发词。
        // 迁移：移除旧版"切换"类触发词（已拆分为 开启/关闭 两条独立触发词）。
        if (configTriggers == null) configTriggers = new ArrayList<>();
        configTriggers.removeIf(t -> t != null && t.action != null && t.action.isDeprecatedToggle());
        // 采用"按动作补齐"策略：每个内置动作至少保留一条默认触发词，
        // 已存在该动作的自定义触发词时不重复添加。
        ensureDefaultTrigger(configTriggers, "关闭触发回复", "(?i)^[（(]?关闭触发回复[）)]?$", ConfigTriggerAction.DISABLE_TRIGGER);
        ensureDefaultTrigger(configTriggers, "开启触发回复", "(?i)^[（(]?开启触发回复[）)]?$", ConfigTriggerAction.ENABLE_TRIGGER);
        ensureDefaultTrigger(configTriggers, "清空上下文", "(?i)^[（(]?清空上下文[）)]?$", ConfigTriggerAction.CLEAR_CONTEXT);
        ensureDefaultTrigger(configTriggers, "重载配置", "(?i)^[（(]?重载配置[）)]?$", ConfigTriggerAction.RELOAD_CONFIG);
        ensureDefaultTrigger(configTriggers, "关闭模组", "(?i)^[（(]?关闭(?:模组|AI)[）)]?$", ConfigTriggerAction.DISABLE_ENABLED);
        ensureDefaultTrigger(configTriggers, "开启模组", "(?i)^[（(]?开启(?:模组|AI)[）)]?$", ConfigTriggerAction.ENABLE_ENABLED);
        ensureDefaultTrigger(configTriggers, "关闭自动回复", "(?i)^[（(]?关闭自动回复[）)]?$", ConfigTriggerAction.DISABLE_SCHEDULE);
        ensureDefaultTrigger(configTriggers, "开启自动回复", "(?i)^[（(]?开启自动回复[）)]?$", ConfigTriggerAction.ENABLE_SCHEDULE);
        ensureDefaultTrigger(configTriggers, "关闭文生图", "(?i)^[（(]?关闭(?:文生图|图片生成|画图)[）)]?$", ConfigTriggerAction.DISABLE_IMAGE_GENERATION);
        ensureDefaultTrigger(configTriggers, "开启文生图", "(?i)^[（(]?开启(?:文生图|图片生成|画图)[）)]?$", ConfigTriggerAction.ENABLE_IMAGE_GENERATION);
        ensureDefaultTrigger(configTriggers, "关闭图片识别", "(?i)^[（(]?关闭(?:图片识别|识图|看图)[）)]?$", ConfigTriggerAction.DISABLE_IMAGE_EXTRACT);
        ensureDefaultTrigger(configTriggers, "开启图片识别", "(?i)^[（(]?开启(?:图片识别|识图|看图)[）)]?$", ConfigTriggerAction.ENABLE_IMAGE_EXTRACT);
    }

    /** 若列表中尚无任何使用该动作的触发词，则追加一条默认触发词。 */
    private static void ensureDefaultTrigger(List<ConfigTrigger> list, String description,
                                             String regex, ConfigTriggerAction action) {
        for (ConfigTrigger t : list) {
            if (t != null && t.action == action) return; // 该动作已存在，跳过
        }
        list.add(new ConfigTrigger(description, regex, action));
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * 特殊触发词动作类型
     */
    public enum ConfigTriggerAction {
        DISABLE_TRIGGER,          // 关闭触发回复
        ENABLE_TRIGGER,           // 开启触发回复
        CLEAR_CONTEXT,            // 清空上下文
        RELOAD_CONFIG,            // 重载配置
        TOGGLE_ENABLED,           // 切换模组总开关（兼容保留）
        DISABLE_ENABLED,          // 关闭模组
        ENABLE_ENABLED,           // 开启模组
        DISABLE_SCHEDULE,         // 关闭自动回复（定时回复）
        ENABLE_SCHEDULE,          // 开启自动回复（定时回复）
        DISABLE_IMAGE_GENERATION, // 关闭文生图
        ENABLE_IMAGE_GENERATION,  // 开启文生图
        DISABLE_IMAGE_EXTRACT,    // 关闭图片识别（Vision 识图）
        ENABLE_IMAGE_EXTRACT,     // 开启图片识别（Vision 识图）
        // ── 旧版"切换"动作：仅为兼容已存在的配置文件而保留，默认触发词不再使用 ──
        TOGGLE_SCHEDULE,          // 已废弃：请改用 ENABLE_SCHEDULE / DISABLE_SCHEDULE
        TOGGLE_IMAGE_GENERATION,  // 已废弃：请改用 ENABLE/DISABLE_IMAGE_GENERATION
        TOGGLE_IMAGE_EXTRACT,     // 已废弃：请改用 ENABLE/DISABLE_IMAGE_EXTRACT
        ;

        /** 是否为已废弃的旧版"切换"动作（加载时自动迁移移除，UI 中不展示）。 */
        public boolean isDeprecatedToggle() {
            return this == TOGGLE_SCHEDULE
                    || this == TOGGLE_IMAGE_GENERATION
                    || this == TOGGLE_IMAGE_EXTRACT;
        }
    }

    /**
     * 特殊触发词配置
     */
    public static class ConfigTrigger {
        public String description;  // 描述（用于UI显示）
        public String regex;        // 触发正则
        public ConfigTriggerAction action;  // 触发时执行的动作

        public ConfigTrigger() {
            this.description = "";
            this.regex = "";
            this.action = ConfigTriggerAction.DISABLE_TRIGGER;
        }

        public ConfigTrigger(String description, String regex, ConfigTriggerAction action) {
            this.description = description;
            this.regex = regex;
            this.action = action;
        }
    }
}