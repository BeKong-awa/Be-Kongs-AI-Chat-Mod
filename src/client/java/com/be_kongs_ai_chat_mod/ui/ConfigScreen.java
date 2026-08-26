package com.be_kongs_ai_chat_mod.ui;

import com.be_kongs_ai_chat_mod.BeKongsAiChatMod;
import com.be_kongs_ai_chat_mod.chat.AiChatManager;
import com.be_kongs_ai_chat_mod.config.AiChatConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏内 AI Chat Mod 配置 UI（纯文本 Screen，适配 26.x）。
 *
 * 26.x 关键点：
 * - 渲染回调是 extractRenderState(GuiGraphicsExtractor, mouseX, mouseY, partialTick)，
 *   GuiGraphics / render(GuiGraphics,...) 已不存在。
 * - 输入事件为 record 风格：KeyEvent.key()、CharacterEvent.codepointAsString()、
 *   MouseButtonEvent.button()/x()/y()；mouseClicked(MouseButtonEvent, boolean doubleClick)。
 *
 * 操作方式：数字键 1-5 切换页签，左键点击开关/进入编辑，Enter 确认，
 * ESC 取消编辑或关闭界面，滚轮滚动列表，0 键恢复默认配置。
 */
public class ConfigScreen extends Screen {
    private static final Logger LOG = LoggerFactory.getLogger("be-kongs-ai-chat-mod");

    private final Screen previousScreen;
    private final AiChatConfig config;
    private int activeTab = 0;
    private final List<OptionEntry> currentEntries = new ArrayList<>();

    // 当前正在编辑的条目索引（-1 表示未编辑）
    private int editingEntryIndex = -1;
    // 编辑时的临时值
    private String editingValue = "";
    // 条目列表滚动偏移
    private int scrollOffset = 0;
    // 首次渲染诊断标记
    private boolean renderLogged = false;

    // Tab 索引
    private static final int TAB_GENERAL = 0;
    private static final int TAB_TRIGGER = 1;
    private static final int TAB_IMAGE   = 2;
    private static final int TAB_PLAYERS = 3;
    private static final int TAB_CHAT    = 4;
    private static final int TAB_COUNT   = 5;

    private static final String[] TAB_LABELS = {
            "1.常规", "2.触发", "3.图像", "4.玩家", "5.聊天"
    };

    // ── 布局常量 ──
    private static final int TITLE_Y = 8;
    private static final int TAB_Y = 26;
    private static final int TAB_HEIGHT = 20;
    private static final int ENTRIES_START_Y = 56;
    private static final int ENTRY_HEIGHT = 16;

    public ConfigScreen(Screen previous, AiChatConfig config) {
        super(Component.literal("AI Chat Mod - 配置"));
        this.previousScreen = previous;
        this.config = config;
    }

    @Override
    protected void init() {
        rebuildOptions();
        LOG.info("[AiChatMod] Config screen initialized ({}x{}).", width, height);
    }

    @Override
    public void removed() {
        // 确保编辑状态被清理
        editingEntryIndex = -1;
        editingValue = "";
    }

    @Override
    public void onClose() {
        // 返回打开本界面之前的界面（通常是 null = 回到游戏）
        minecraft.setScreen(previousScreen);
    }

    // ── 渲染（26.x：extractRenderState 取代旧的 render）──────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
        if (!renderLogged) {
            renderLogged = true;
            LOG.info("[AiChatMod] Config screen first render pass.");
        }

        // 半透明深色背景，保证文字可读
        extractor.fill(0, 0, width, height, 0xD0101018);

        // 标题
        extractor.centeredText(font, getTitle().getString(), width / 2, TITLE_Y, 0xFFFFFFFF);

        // Tab 栏（横向）
        int tabWidth = Math.max(1, width / TAB_COUNT);
        for (int i = 0; i < TAB_COUNT; i++) {
            int x1 = i * tabWidth;
            int x2 = (i == TAB_COUNT - 1) ? width : x1 + tabWidth;
            boolean active = i == activeTab;
            boolean hover = mouseX >= x1 && mouseX < x2 && mouseY >= TAB_Y && mouseY < TAB_Y + TAB_HEIGHT;
            int bg = active ? 0xE02A5A2A : (hover ? 0xC0404050 : 0xC0282830);
            extractor.fill(x1 + 1, TAB_Y, x2 - 1, TAB_Y + TAB_HEIGHT, bg);
            extractor.centeredText(font, TAB_LABELS[i], (x1 + x2) / 2, TAB_Y + 6,
                    active ? 0xFF55FF55 : 0xFFC0C0C0);
        }

        // 选项条目
        int visible = visibleEntryCount();
        int end = Math.min(currentEntries.size(), scrollOffset + visible);
        for (int i = scrollOffset; i < end; i++) {
            int y = ENTRIES_START_Y + (i - scrollOffset) * ENTRY_HEIGHT;
            OptionEntry entry = currentEntries.get(i);
            boolean hover = mouseY >= y && mouseY < y + ENTRY_HEIGHT && mouseX >= 8 && mouseX <= width - 8;
            if (hover || i == editingEntryIndex) {
                extractor.fill(8, y, width - 8, y + ENTRY_HEIGHT, 0x80405060);
            }
            int labelColor = 0xFFC8C8C8;
            if (entry instanceof SectionLabel) labelColor = 0xFFFFCC55;      // 分区标题：金色
            else if (entry instanceof ActionButton) labelColor = 0xFF8FE08F; // 动作按钮：绿色
            extractor.text(font, entry.getLabel().getString(), 12, y + 4, labelColor);

            String valueText;
            int valueColor;
            if (i == editingEntryIndex) {
                valueText = "> " + editingValue + "_";
                valueColor = 0xFFFFFF55;
            } else if (entry instanceof ToggleOption toggle) {
                valueText = toggle.isOn() ? "ON" : "OFF";
                valueColor = toggle.isOn() ? 0xFF55FF55 : 0xFFFF5555;
            } else if (entry instanceof TriggerActionOption actionOpt) {
                valueText = actionOpt.getValueName();
                valueColor = 0xFF55FFFF;
            } else {
                valueText = getEntryValueAsString(entry);
                valueColor = 0xFFFFFFFF;
                if (valueText.length() > 48) {
                    valueText = valueText.substring(0, 48) + "...";
                }
            }
            int valueWidth = font.width(valueText);
            extractor.text(font, valueText, width - 12 - valueWidth, y + 4, valueColor);
        }

        // 滚动指示
        if (currentEntries.size() > visible) {
            extractor.text(font, (scrollOffset + 1) + "-" + end + "/" + currentEntries.size(),
                    width - 58, ENTRIES_START_Y - 12, 0xFF909090);
        }

        // 底部操作提示
        extractor.centeredText(font,
                "1-5:切页 | 左键:切换/编辑/循环动作 | Enter:确认 | ESC:取消/关闭 | 滚轮:滚动 | 0:恢复默认",
                width / 2, height - 14, 0xFF808080);
    }

    private int visibleEntryCount() {
        return Math.max(1, (height - ENTRIES_START_Y - 20) / ENTRY_HEIGHT);
    }

    private int maxScrollOffset() {
        return Math.max(0, currentEntries.size() - visibleEntryCount());
    }

    // ── 输入处理（26.x 事件对象）──────────────────────────────────────────────

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key(); // GLFW key code

        if (editingEntryIndex >= 0) {
            // 编辑状态优先处理（避免 ESC 被父类直接关闭整个界面）
            if (keyCode == 256) { // ESC — 取消编辑
                editingEntryIndex = -1;
                editingValue = "";
                return true;
            } else if (keyCode == 257) { // Enter — 保存编辑内容
                saveEditingEntry();
                return true;
            } else if (keyCode == 259) { // Backspace — 删除一个字符（按码点）
                if (!editingValue.isEmpty()) {
                    int last = editingValue.codePointBefore(editingValue.length());
                    editingValue = editingValue.substring(0, editingValue.length() - Character.charCount(last));
                }
                return true;
            }
            // 其余按键（方向键等）在编辑状态下忽略
            return true;
        }

        if (super.keyPressed(event)) {
            return true;
        }

        if (keyCode >= 49 && keyCode <= 53) { // 1-5 键切换 Tab
            setActiveTab(keyCode - 49);
            return true;
        }
        if (keyCode == 48) { // 0 键 — 恢复默认配置
            onReset();
            return true;
        }
        // ESC 由 Screen 默认处理（shouldCloseOnEsc -> onClose -> 返回上一界面）
        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (editingEntryIndex >= 0) {
            // 26.x codepoint API；允许所有合法聊天字符（含中文等）
            if (event.isAllowedChatCharacter()) {
                editingValue += event.codepointAsString();
                return true;
            }
            return false;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() != 0) {
            return false;
        }
        double mouseX = event.x();
        double mouseY = event.y();

        // Tab 栏点击
        if (mouseY >= TAB_Y && mouseY < TAB_Y + TAB_HEIGHT) {
            int tabWidth = Math.max(1, width / TAB_COUNT);
            int tabIndex = (int) mouseX / tabWidth;
            if (tabIndex >= 0 && tabIndex < TAB_COUNT) {
                setActiveTab(tabIndex);
                return true;
            }
        }

        // 选项条目点击
        if (mouseY >= ENTRIES_START_Y) {
            int row = (int) ((mouseY - ENTRIES_START_Y) / ENTRY_HEIGHT);
            int index = scrollOffset + row;
            if (row >= 0 && row < visibleEntryCount() && index >= 0 && index < currentEntries.size()) {
                OptionEntry entry = currentEntries.get(index);
                if (entry instanceof ToggleOption toggle) {
                    // 开关类：点击即切换并保存
                    toggle.setValue(!toggle.isOn());
                    persistConfig();
                    return true;
                }
                if (entry instanceof TriggerActionOption actionOpt) {
                    // 动作类：点击循环切换动作并保存
                    actionOpt.cycle();
                    persistConfig();
                    return true;
                }
                if (entry instanceof ActionButton btn) {
                    // 动作按钮：执行（添加/删除触发词等）并保存
                    btn.run();
                    persistConfig();
                    return true;
                }
                if (entry instanceof SectionLabel) {
                    return true; // 分区标题不可交互
                }
                if (editingEntryIndex == index) {
                    // 再次点击正在编辑的条目 -> 保存
                    saveEditingEntry();
                } else {
                    editingEntryIndex = index;
                    editingValue = getEntryValueAsString(entry);
                }
                return true;
            }
        }

        // 点击空白处：结束编辑并保存
        if (editingEntryIndex >= 0) {
            saveEditingEntry();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        if (scrollY != 0) {
            scrollOffset = clamp(scrollOffset - (int) Math.signum(scrollY), 0, maxScrollOffset());
            return true;
        }
        return false;
    }

    // ── 编辑保存 ──────────────────────────────────────────────────────────────

    private void saveEditingEntry() {
        if (editingEntryIndex < 0 || editingEntryIndex >= currentEntries.size()) {
            editingEntryIndex = -1;
            editingValue = "";
            return;
        }
        OptionEntry entry = currentEntries.get(editingEntryIndex);
        if (entry instanceof TextOption textOption) {
            textOption.setValue(editingValue);
        } else if (entry instanceof IntOption intOption) {
            try {
                intOption.setValue(Integer.parseInt(editingValue.trim()));
            } catch (NumberFormatException ignored) {
                // 忽略无效输入，保留原值
            }
        } else if (entry instanceof DoubleOption doubleOption) {
            try {
                doubleOption.setValue(Double.parseDouble(editingValue.trim()));
            } catch (NumberFormatException ignored) {
                // 忽略无效输入，保留原值
            }
        } else if (entry instanceof TextListOption textListOption) {
            // 列表类：分号或换行分隔
            textListOption.setValue(splitListInput(editingValue));
            textListOption.commit();
        } else if (entry instanceof PlayerListOption playerList) {
            playerList.setValue(splitListInput(editingValue));
            playerList.commit();
        }
        editingEntryIndex = -1;
        editingValue = "";
        persistConfig();
    }

    private static List<String> splitListInput(String raw) {
        List<String> list = new ArrayList<>();
        for (String part : raw.split("[\n;]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list;
    }

    private void persistConfig() {
        config.validate();
        config.save();
        BeKongsAiChatMod.config = config;
        AiChatManager.getInstance().reloadConfig(config);
        // validate() 可能补齐/迁移触发词列表，重建条目保持 UI 与配置同步
        rebuildOptions();
    }

    private String getEntryValueAsString(OptionEntry entry) {
        if (entry instanceof ToggleOption toggle) {
            return toggle.isOn() ? "true" : "false";
        } else if (entry instanceof TextOption textOption) {
            return textOption.getValue();
        } else if (entry instanceof IntOption intOption) {
            return String.valueOf(intOption.getValue());
        } else if (entry instanceof DoubleOption doubleOption) {
            return String.valueOf(doubleOption.getValue());
        } else if (entry instanceof TextListOption textListOption) {
            return String.join("; ", textListOption.getValues());
        } else if (entry instanceof PlayerListOption playerList) {
            return String.join("; ", playerList.getValues());
        } else if (entry instanceof TriggerActionOption actionOpt) {
            return actionOpt.getValueName();
        } else if (entry instanceof ActionButton) {
            return "▶";
        }
        return "";
    }

    // ── 各 Tab 条目构建 ─────────────────────────────────────────────────────────

    private List<OptionEntry> buildGeneralEntries() {
        List<OptionEntry> e = new ArrayList<>();
        e.add(new ToggleOption("enabled",             "模组总开关",              config.enabled,              v -> config.enabled = v));
        e.add(new TextOption("provider",              "AI 提供商",               config.provider,             v -> config.provider = v));
        e.add(new TextOption("apiKey",                "API 密钥 (通用)",         config.apiKey,               v -> config.apiKey = v));
        e.add(new TextOption("triggerApiKey",         "触发回复密钥(留空=通用)", config.triggerApiKey,        v -> config.triggerApiKey = v));
        e.add(new TextOption("scheduleApiKey",        "自动回复密钥(留空=通用)", config.scheduleApiKey,       v -> config.scheduleApiKey = v));
        e.add(new TextOption("baseUrl",               "API基础地址(留空=默认)",  config.baseUrl,              v -> config.baseUrl = v));
        e.add(new TextOption("model",                 "模型名称",                config.model,                v -> config.model = v));
        e.add(new IntOption("maxTokens",              "最大Token数",             config.maxTokens,            v -> config.maxTokens = clamp(v, 1, 8192)));
        e.add(new DoubleOption("temperature",         "温度(0-2)",               config.temperature,          v -> config.temperature = clampD(v, 0.0, 2.0)));
        e.add(new IntOption("maxReplyMessages",       "单次最多回复条数",        config.maxReplyMessages,     v -> config.maxReplyMessages = clamp(v, 1, 10)));
        e.add(new IntOption("maxCharsPerMessage",     "每条回复最大字符",        config.maxCharsPerMessage,   v -> config.maxCharsPerMessage = clamp(v, 20, 256)));
        e.add(new ToggleOption("contextEnabled",      "启用上下文记忆",          config.contextEnabled,       v -> config.contextEnabled = v));
        e.add(new IntOption("contextLength",          "上下文长度",              config.contextLength,        v -> config.contextLength = clamp(v, 1, 100)));
        e.add(new TextOption("systemPrompt",          "系统提示词",              config.systemPrompt,         v -> config.systemPrompt = v));
        e.add(new IntOption("timeoutSeconds",         "请求超时(秒)",            config.timeoutSeconds,       v -> config.timeoutSeconds = clamp(v, 5, 600)));
        e.add(new IntOption("retryCount",             "失败重试次数",            config.retryCount,           v -> config.retryCount = clamp(v, 0, 5)));
        e.add(new ToggleOption("debugLog",            "调试日志(文件)",          config.debugLog,             v -> config.debugLog = v));
        e.add(new TextOption("uiHotkey",              "配置界面热键(按键名)",     config.uiHotkey,             v -> config.uiHotkey = v));
        return e;
    }

    private List<OptionEntry> buildTriggerEntries() {
        List<OptionEntry> e = new ArrayList<>();
        e.add(new ToggleOption("triggerEnabled",          "启用触发回复",         config.triggerEnabled,         v -> config.triggerEnabled = v));
        e.add(new TextListOption("triggerRegexList",      "触发正则列表(分号分隔)", config.triggerRegexList,      v -> config.triggerRegexList = new ArrayList<>(v)));
        e.add(new IntOption("triggerCooldownSeconds",     "触发冷却(秒)",         config.triggerCooldownSeconds, v -> config.triggerCooldownSeconds = clamp(v, 1, 3600)));
        e.add(new ToggleOption("scheduleEnabled",         "启用自动回复",         config.scheduleEnabled,        v -> config.scheduleEnabled = v));
        e.add(new IntOption("scheduleIntervalSeconds",    "自动回复间隔(秒)",     config.scheduleIntervalSeconds,v -> config.scheduleIntervalSeconds = clamp(v, 5, 3600)));
        addConfigTriggerEntries(e);
        return e;
    }

    /** 特殊触发词（configTriggers）编辑区：每条触发词 = 描述/正则(可编辑) + 动作(点击循环) + 删除按钮。 */
    private void addConfigTriggerEntries(List<OptionEntry> e) {
        e.add(new SectionLabel("triggers.header", "── 特殊触发词（聊天短语改配置，不触发AI回复）──"));

        List<AiChatConfig.ConfigTrigger> triggers = config.configTriggers;
        for (int i = 0; i < triggers.size(); i++) {
            // 按引用捕获，写回不受列表重排影响
            final AiChatConfig.ConfigTrigger t = triggers.get(i);
            String n = String.valueOf(i + 1);
            e.add(new TextOption("triggers." + i + ".desc",
                    "词" + n + "·描述", t.description, v -> t.description = v));
            e.add(new TextOption("triggers." + i + ".regex",
                    "词" + n + "·正则", t.regex, v -> t.regex = v));
            e.add(new TriggerActionOption("triggers." + i + ".action",
                    "词" + n + "·动作(点击循环)", t));
            e.add(new ActionButton("triggers." + i + ".delete",
                    "词" + n + "·✕删除此触发词", () -> triggers.remove(t)));
        }

        e.add(new ActionButton("triggers.add", "＋ 添加特殊触发词", () ->
                triggers.add(new AiChatConfig.ConfigTrigger(
                        "新触发词", "(?i)^新触发词$", AiChatConfig.ConfigTriggerAction.DISABLE_TRIGGER))));
        e.add(new SectionLabel("triggers.hint",
                "动作说明: DISABLE/ENABLE_TRIGGER=触发回复, *_SCHEDULE=自动回复, *_IMAGE_GENERATION=文生图, *_IMAGE_EXTRACT=识图"));
    }

    private List<OptionEntry> buildImageEntries() {
        List<OptionEntry> e = new ArrayList<>();
        e.add(new ToggleOption("imageGenerationEnabled",  "启用文生图",           config.imageGenerationEnabled, v -> config.imageGenerationEnabled = v));
        e.add(new TextOption("imageTriggerRegex",         "文生图触发正则",       config.imageTriggerRegex,      v -> config.imageTriggerRegex = v));
        e.add(new ToggleOption("imageExtractEnabled",     "识别图片URL(视觉)",    config.imageExtractEnabled,    v -> config.imageExtractEnabled = v));
        e.add(new ToggleOption("imageExtractMergeMode",   "合并模式(URL附正文)",  config.imageExtractMergeMode,  v -> config.imageExtractMergeMode = v));
        e.add(new TextOption("imageUrlRegex",             "图片URL正则",          config.imageUrlRegex,          v -> config.imageUrlRegex = v));
        e.add(new TextOption("imageProvider",             "图像提供商",           config.imageProvider,          v -> config.imageProvider = v));
        e.add(new TextOption("imageApiKey",               "图像API密钥",          config.imageApiKey,            v -> config.imageApiKey = v));
        e.add(new TextOption("imageBaseUrl",              "图像API地址",          config.imageBaseUrl,           v -> config.imageBaseUrl = v));
        e.add(new TextOption("imageModel",                "图像模型",             config.imageModel,             v -> config.imageModel = v));
        e.add(new TextOption("imageSize",                 "图像尺寸",             config.imageSize,              v -> config.imageSize = v));
        e.add(new TextOption("imageRatio",                "宽高比",               config.imageRatio,             v -> config.imageRatio = v));
        e.add(new IntOption("imageTimeoutSeconds",        "图像超时(秒)",         config.imageTimeoutSeconds,    v -> config.imageTimeoutSeconds = clamp(v, 30, 3600)));
        e.add(new IntOption("imageRetryCount",            "图像重试次数",         config.imageRetryCount,        v -> config.imageRetryCount = clamp(v, 0, 5)));
        e.add(new IntOption("imageCooldownSeconds",       "图像冷却(秒)",         config.imageCooldownSeconds,   v -> config.imageCooldownSeconds = clamp(v, 5, 3600)));
        e.add(new TextOption("imageUploaderType",         "图床类型(catbox/0x0/imgbb/custom/none)", config.imageUploaderType, v -> config.imageUploaderType = v));
        e.add(new TextOption("imageUploaderUrl",          "图床API地址",          config.imageUploaderUrl,       v -> config.imageUploaderUrl = v));
        return e;
    }

    private List<OptionEntry> buildPlayerEntries() {
        List<OptionEntry> e = new ArrayList<>();
        e.add(new ToggleOption("whitelistMode",       "白名单模式(仅白名单玩家触发)", config.whitelistMode,      v -> config.whitelistMode = v));
        e.add(new PlayerListOption("blockedPlayers",      "黑名单(分号分隔)",     config.blockedPlayers,         v -> config.blockedPlayers = new ArrayList<>(v)));
        e.add(new PlayerListOption("whitelistedPlayers",  "白名单(分号分隔)",     config.whitelistedPlayers,     v -> config.whitelistedPlayers = new ArrayList<>(v)));
        return e;
    }

    private List<OptionEntry> buildChatEntries() {
        List<OptionEntry> e = new ArrayList<>();
        e.add(new ToggleOption("chatLogEnabled",          "启用聊天栏日志",        config.chatLogEnabled,         v -> config.chatLogEnabled = v));
        e.add(new ToggleOption("chatLogTrigger",          "触发回复日志",          config.chatLogTrigger,         v -> config.chatLogTrigger = v));
        e.add(new ToggleOption("chatLogSchedule",         "自动回复日志",          config.chatLogSchedule,        v -> config.chatLogSchedule = v));
        e.add(new ToggleOption("chatLogError",            "错误日志",              config.chatLogError,           v -> config.chatLogError = v));
        e.add(new ToggleOption("chatLogBlocked",          "拦截日志",              config.chatLogBlocked,         v -> config.chatLogBlocked = v));
        e.add(new ToggleOption("chatLogReload",           "重载日志",              config.chatLogReload,          v -> config.chatLogReload = v));
        e.add(new ToggleOption("chatLogClearContext",     "清空上下文日志",        config.chatLogClearContext,    v -> config.chatLogClearContext = v));
        e.add(new ToggleOption("chatLogDebug",            "调试日志",              config.chatLogDebug,           v -> config.chatLogDebug = v));
        e.add(new ToggleOption("restrictionEnabled",      "启用消息过滤",          config.restrictionEnabled,     v -> config.restrictionEnabled = v));
        e.add(new TextListOption("blockedRegexPatterns",  "拦截消息正则列表(分号分隔)", config.blockedRegexPatterns, v -> config.blockedRegexPatterns = new ArrayList<>(v)));
        e.add(new ToggleOption("stripChatPrefix",         "剥离聊天前缀",          config.stripChatPrefix,        v -> config.stripChatPrefix = v));
        e.add(new ToggleOption("filterGameEvents",        "过滤GAME事件非玩家消息", config.filterGameEvents,       v -> config.filterGameEvents = v));
        return e;
    }

    // ── 保存 / 重置 ─────────────────────────────────────────────────────────────

    private void onReset() {
        AiChatConfig defaults = new AiChatConfig();
        defaults.validate();
        copyDefaults(defaults);
        rebuildOptions();
        persistConfig();
    }

    private void copyDefaults(AiChatConfig d) {
        config.enabled                 = d.enabled;
        config.provider                = d.provider;
        config.apiKey                  = d.apiKey;
        config.triggerApiKey           = d.triggerApiKey;
        config.scheduleApiKey          = d.scheduleApiKey;
        config.baseUrl                 = d.baseUrl;
        config.model                   = d.model;
        config.temperature             = d.temperature;
        config.maxTokens               = d.maxTokens;
        config.maxReplyMessages        = d.maxReplyMessages;
        config.maxCharsPerMessage      = d.maxCharsPerMessage;
        config.contextEnabled          = d.contextEnabled;
        config.contextLength           = d.contextLength;
        config.triggerEnabled          = d.triggerEnabled;
        config.triggerRegexList        = new ArrayList<>(d.triggerRegexList);
        config.triggerCooldownSeconds  = d.triggerCooldownSeconds;
        config.scheduleEnabled         = d.scheduleEnabled;
        config.scheduleIntervalSeconds = d.scheduleIntervalSeconds;
        config.restrictionEnabled      = d.restrictionEnabled;
        config.blockedRegexPatterns    = new ArrayList<>(d.blockedRegexPatterns);
        config.blockedPlayers          = new ArrayList<>(d.blockedPlayers);
        config.whitelistedPlayers      = new ArrayList<>(d.whitelistedPlayers);
        config.whitelistMode           = d.whitelistMode;
        config.systemPrompt            = d.systemPrompt;
        config.timeoutSeconds          = d.timeoutSeconds;
        config.retryCount              = d.retryCount;
        config.debugLog                = d.debugLog;
        config.chatLogEnabled          = d.chatLogEnabled;
        config.chatLogTrigger          = d.chatLogTrigger;
        config.chatLogSchedule         = d.chatLogSchedule;
        config.chatLogError            = d.chatLogError;
        config.chatLogBlocked          = d.chatLogBlocked;
        config.chatLogReload           = d.chatLogReload;
        config.chatLogClearContext     = d.chatLogClearContext;
        config.chatLogDebug            = d.chatLogDebug;
        config.imageGenerationEnabled  = d.imageGenerationEnabled;
        config.imageProvider           = d.imageProvider;
        config.imageApiKey             = d.imageApiKey;
        config.imageBaseUrl            = d.imageBaseUrl;
        config.imageModel              = d.imageModel;
        config.imageSize               = d.imageSize;
        config.imageRatio              = d.imageRatio;
        config.imageTimeoutSeconds     = d.imageTimeoutSeconds;
        config.imageRetryCount         = d.imageRetryCount;
        config.imageCooldownSeconds    = d.imageCooldownSeconds;
        config.imageTriggerRegex       = d.imageTriggerRegex;
        config.imageUploaderType       = d.imageUploaderType;
        config.imageUploaderUrl        = d.imageUploaderUrl;
        config.imageExtractEnabled     = d.imageExtractEnabled;
        config.imageExtractMergeMode   = d.imageExtractMergeMode;
        config.imageUrlRegex           = d.imageUrlRegex;
        config.stripChatPrefix         = d.stripChatPrefix;
        config.filterGameEvents        = d.filterGameEvents;
        config.uiHotkey                = d.uiHotkey;
        // 触发词列表：整体替换为默认集（深拷贝一层对象，避免与默认实例共享引用）
        config.configTriggers = new ArrayList<>();
        for (AiChatConfig.ConfigTrigger t : d.configTriggers) {
            config.configTriggers.add(new AiChatConfig.ConfigTrigger(t.description, t.regex, t.action));
        }
    }

    private void setActiveTab(int idx) {
        if (idx < 0 || idx >= TAB_COUNT) return;
        activeTab = idx;
        editingEntryIndex = -1; // 切换 Tab 时退出编辑状态
        editingValue = "";
        scrollOffset = 0;
        rebuildOptions();
    }

    private void rebuildOptions() {
        currentEntries.clear();
        switch (activeTab) {
            case TAB_GENERAL -> currentEntries.addAll(buildGeneralEntries());
            case TAB_TRIGGER -> currentEntries.addAll(buildTriggerEntries());
            case TAB_IMAGE   -> currentEntries.addAll(buildImageEntries());
            case TAB_PLAYERS -> currentEntries.addAll(buildPlayerEntries());
            case TAB_CHAT    -> currentEntries.addAll(buildChatEntries());
        }
        scrollOffset = clamp(scrollOffset, 0, maxScrollOffset());
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clampD(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
