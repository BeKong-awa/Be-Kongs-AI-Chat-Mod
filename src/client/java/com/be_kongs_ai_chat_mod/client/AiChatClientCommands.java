package com.be_kongs_ai_chat_mod.client;

import com.be_kongs_ai_chat_mod.BeKongsAiChatMod;
import com.be_kongs_ai_chat_mod.chat.AiChatManager;
import com.be_kongs_ai_chat_mod.config.AiChatConfig;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class AiChatClientCommands {
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                    ClientCommands.literal("aichat")
                            .then(ClientCommands.literal("reload").executes(ctx -> {
                                AiChatConfig newConfig = AiChatConfig.load();
                                BeKongsAiChatMod.config = newConfig;
                                AiChatManager.getInstance().reloadConfig(newConfig);
                                feedback("AI Chat Mod config reloaded.");
                                return 1;
                            }))
                            .then(ClientCommands.literal("toggle").executes(ctx -> {
                                BeKongsAiChatMod.config.enabled = !BeKongsAiChatMod.config.enabled;
                                BeKongsAiChatMod.config.save();
                                feedback("AI Chat Mod " + (BeKongsAiChatMod.config.enabled ? "enabled" : "disabled"));
                                return 1;
                            }))
                            .then(ClientCommands.literal("status").executes(ctx -> {
                                AiChatConfig c = BeKongsAiChatMod.config;
                                feedback("AI Chat Mod status | enabled=" + c.enabled
                                        + ", provider=" + c.provider
                                        + ", model=" + c.model
                                        + ", trigger=" + c.triggerEnabled
                                        + ", triggerCooldown=" + c.triggerCooldownSeconds + "s"
                                        + ", schedule=" + c.scheduleEnabled + "(" + c.scheduleIntervalSeconds + "s)"
                                        + ", restriction=" + c.restrictionEnabled
                                        + ", context=" + c.contextEnabled + "(" + c.contextLength + ")");
                                return 1;
                            }))
                            .then(ClientCommands.literal("clearcontext").executes(ctx -> {
                                AiChatManager.getInstance().clearContext();
                                feedback("AI Chat Mod context cleared.");
                                return 1;
                            }))
            );
        });
    }

    private static void feedback(String msg) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.player != null) {
            // 使用 sendSystemMessage 替代 addMessage（在 26.1.2 中 addMessage 需要 4 个参数）
            client.player.sendSystemMessage(Component.literal("§a[AiChatMod] §f" + msg));
        }
    }
}