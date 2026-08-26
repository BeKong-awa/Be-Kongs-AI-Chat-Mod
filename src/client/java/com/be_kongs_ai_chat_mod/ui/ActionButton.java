package com.be_kongs_ai_chat_mod.ui;

import net.minecraft.network.chat.Component;

/**
 * 纯动作按钮条目：左键点击执行一个 Runnable（如"添加触发词""删除触发词"）。
 */
class ActionButton extends OptionEntry {
    private final Runnable action;

    ActionButton(String key, String label, Runnable action) {
        super(key, label);
        this.action = action;
    }

    @Override
    public Component getMessage() {
        return getLabel();
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        run();
    }

    void run() {
        if (action != null) action.run();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) { return false; }
    @Override
    public boolean charTyped(char chr, int modifiers) { return false; }
}
