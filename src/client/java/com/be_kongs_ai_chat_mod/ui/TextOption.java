package com.be_kongs_ai_chat_mod.ui;

import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

/** 文本输入选项（String）。 */
class TextOption extends OptionEntry {
    private String value;
    private final Consumer<String> setter;

    TextOption(String key, String label, String value, Consumer<String> setter) {
        super(key, label);
        this.value = value != null ? value : "";
        this.setter = setter;
    }

    @Override
    public Component getMessage() {
        return Component.literal(getLabel().getString() + (focused ? " = [" + value + "]" : ""));
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        focused = !focused;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused) return false;
        if (keyCode == 257) { focused = false; setter.accept(value); return true; }
        if (keyCode == 256) { focused = false; return true; }
        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (!focused) return false;
        value += chr;
        return true;
    }

    public String getValue() { return value; }
    public void setValue(String v) {
        value = v != null ? v : "";
        if (setter != null) setter.accept(value);
    }
}
