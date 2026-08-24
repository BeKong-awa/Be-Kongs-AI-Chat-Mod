package com.be_kongs_ai_chat_mod;

import com.be_kongs_ai_chat_mod.chat.AiChatManager;
import com.be_kongs_ai_chat_mod.client.AiChatClientCommands;
import com.be_kongs_ai_chat_mod.config.AiChatConfig;
import net.fabricmc.api.ClientModInitializer;
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

        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) return;

            UUID senderId = getGameProfileId(sender);
            UUID playerId = client.player.getUUID();
            if (senderId != null && senderId.equals(playerId)) {
                return;
            }

            if (message != null) {
                AiChatManager.getInstance().onPlayerChatMessage(message.getString(), sender);
            }
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (message == null) return;
            String text = message.getString();
            if (text == null || text.isBlank()) return;

            if (isSelfMessage(text)) return;

            AiChatManager.getInstance().onPlayerChatMessage(text, null);
        });

        LOGGER.info("[AiChatMod] Client initialized.");
    }

    private static boolean isSelfMessage(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return false;
        String selfName = client.player.getName().getString();
        if (selfName == null || selfName.isBlank()) return false;

        String bracketed = "^\\s*[<\\[]\\s*" + Pattern.quote(selfName) + "\\s*[>\\]]";
        if (Pattern.compile(bracketed).matcher(text).find()) return true;

        String colon = "^\\s*" + Pattern.quote(selfName) + "\\s*[:：]";
        if (Pattern.compile(colon).matcher(text).find()) return true;

        return false;
    }
}