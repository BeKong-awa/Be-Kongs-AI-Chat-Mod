package com.be_kongs_ai_chat_mod.ui;

import net.minecraft.network.chat.Component;

/** 分区标题条目：仅展示，不响应点击/编辑。 */
class SectionLabel extends OptionEntry {
    SectionLabel(String key, String label) {
        super(key, label);
    }

    @Override
    public Component getMessage() {
        return getLabel();
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) { }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) { return false; }
    @Override
    public boolean charTyped(char chr, int modifiers) { return false; }
}
