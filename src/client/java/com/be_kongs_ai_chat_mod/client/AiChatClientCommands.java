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
                                        + ", context=" + c.contextEnabled + "(" + c.contextLength + ")"
                                        + ", whitelist=" + c.whitelistMode
                                        + ", imageExtract=" + c.imageExtractEnabled
                                );
                                return 1;
                            }))
                            .then(ClientCommands.literal("clearcontext").executes(ctx -> {
                                AiChatManager.getInstance().clearContext();
                                feedback("AI Chat Mod context cleared.");
                                return 1;
                            }))
                            .then(ClientCommands.literal("openconfig").executes(ctx -> {
                                Minecraft client = Minecraft.getInstance();
                                if (client.player != null) {
                                    // 聊天界面提交命令后会关闭自己（把当前界面置空），
                                    // 这里同步 setScreen 会被立即覆盖，导致界面一闪而过。
                                    // 延迟到下一个客户端任务再打开配置界面。
                                    client.execute(() -> client.setScreen(
                                            new com.be_kongs_ai_chat_mod.ui.ConfigScreen(
                                                    client.screen, BeKongsAiChatMod.config)));
                                    feedback("AI Chat Mod config UI opened.");
                                }
                                return 1;
                            }))
            );
        });
    }

    private static void feedback(String msg) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.player != null) {
            client.player.sendSystemMessage(Component.literal("§a[AiChatMod] §f" + msg));
        }
    }
}
