package com.be_kongs_ai_chat_mod;

import com.be_kongs_ai_chat_mod.chat.AiChatManager;
import com.be_kongs_ai_chat_mod.client.AiChatClientCommands;
import com.be_kongs_ai_chat_mod.config.AiChatConfig;
import com.be_kongs_ai_chat_mod.ui.ConfigScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.regex.Pattern;

public class BeKongsAiChatMod implements ClientModInitializer {
    public static final String MOD_ID = "be-kongs-ai-chat-mod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static AiChatConfig config;

    // 配置界面热键状态（边沿检测，避免按住时反复打开）
    private static boolean hotkeyWasDown = false;

    private static final Field GAMEPROFILE_ID_FIELD;
    private static final Field GAMEPROFILE_NAME_FIELD;

    static {
        Field idField = null;
        Field nameField = null;
        try {
            Class<?> gpClass = Class.forName("com.mojang.authlib.GameProfile");
            idField = gpClass.getDeclaredField("id");
            idField.setAccessible(true);
            nameField = gpClass.getDeclaredField("name");
            nameField.setAccessible(true);
        } catch (Exception e) {
            LOGGER.error("[AiChatMod] Failed to initialize GameProfile reflection", e);
        }
        GAMEPROFILE_ID_FIELD = idField;
        GAMEPROFILE_NAME_FIELD = nameField;
    }

    public static String getGameProfileName(Object profile) {
        if (profile == null || GAMEPROFILE_NAME_FIELD == null) return null;
        try {
            return (String) GAMEPROFILE_NAME_FIELD.get(profile);
        } catch (Exception e) {
            return null;
        }
    }

    public static UUID getGameProfileId(Object profile) {
        if (profile == null || GAMEPROFILE_ID_FIELD == null) return null;
        try {
            return (UUID) GAMEPROFILE_ID_FIELD.get(profile);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onInitializeClient() {
        config = AiChatConfig.load();
        AiChatManager.init(config);
        AiChatClientCommands.register();

        // CHAT 事件：sender 不为 null，可直接比对 UUID 过滤自身
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) return;

            UUID senderId = getGameProfileId(sender);
            UUID playerId = client.player.getUUID();
            if (senderId != null && senderId.equals(playerId)) return;

            if (message != null) {
                AiChatManager.getInstance().onPlayerChatMessage(message.getString(), sender);
            }
        });

        // GAME 事件：无 sender，通过文本前缀检测是否为自身消息
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (message == null) return;
            String text = message.getString();
            if (text == null || text.isBlank()) return;

            if (isSelfMessage(text)) return;

            AiChatManager.getInstance().onPlayerChatMessage(text, null);
        });

        // 配置界面热键：每个客户端 tick 轮询按键状态（边沿检测）。
        // 说明：本版本 Fabric API 的 KeyBindingHelper 不在编译类路径上，
        // 因此不使用 KeyMapping 注册，改用 InputConstants 直接轮询；
        // 按键可在配置 uiHotkey（按键名，如 key.keyboard.c）中修改。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (config == null) return;
            int keyCode = resolveHotkeyCode(config.uiHotkey);
            boolean down = keyCode > 0
                    && client.getWindow() != null
                    && InputConstants.isKeyDown(client.getWindow(), keyCode);
            if (down && !hotkeyWasDown && client.player != null) {
                if (client.screen instanceof ConfigScreen) {
                    // 已打开 -> 再按一次关闭（返回上一界面）
                    client.screen.onClose();
                } else if (client.screen == null) {
                    client.setScreen(new ConfigScreen(null, config));
                }
            }
            hotkeyWasDown = down;
        });

        LOGGER.info("[AiChatMod] Client initialized. version={}", MOD_ID);
    }

    /** 解析热键按键名（如 key.keyboard.m）为 GLFW 键码；无效时返回 -1。 */
    private static int resolveHotkeyCode(String keyName) {
        if (keyName == null || keyName.isBlank()) return -1;
        try {
            InputConstants.Key key = InputConstants.getKey(keyName);
            if (key == null || key == InputConstants.UNKNOWN) return -1;
            return key.getValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean isSelfMessage(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return false;
        String selfName = client.player.getName().getString();
        if (selfName == null || selfName.isBlank()) return false;

        // 支持 <名前> [名前] 「名前」等多种括号格式
        String bracketed = "^\\s*[<\\[「『（]\\s*" + Pattern.quote(selfName) + "\\s*[>\\]」』）]"
                + "|^\\s*" + Pattern.quote(selfName) + "\\s*[:：]";
        if (Pattern.compile(bracketed).matcher(text).find()) return true;

        return false;
    }
}
