package com.be_kongs_ai_chat_mod.ui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 配置选项条目基类（26.x 纯文本 UI）。
 * 不支持复杂 Widget，仅支持文本显示和点击交互。
 */
abstract class OptionEntry {
    private final String key;
    private final Component label;
    protected boolean focused;

    OptionEntry(String key, String label) {
        this.key = key;
        this.label = Component.literal(label);
    }

    abstract Component getMessage();
    abstract void onClick(double mouseX, double mouseY, int button);
    abstract boolean keyPressed(int keyCode, int scanCode, int modifiers);
    abstract boolean charTyped(char chr, int modifiers);

    public String getKey() { return key; }
    public Component getLabel() { return label; }
    public boolean isFocused() { return focused; }
    public void setFocused(boolean f) { this.focused = f; }
}
