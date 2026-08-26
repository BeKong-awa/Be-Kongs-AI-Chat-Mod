package com.be_kongs_ai_chat_mod.ui;

import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

/** 布尔开关选项（ON/OFF toggle）。 */
class ToggleOption extends OptionEntry {
    private boolean value;
    private final Consumer<Boolean> setter;

    ToggleOption(String key, String label, boolean value, Consumer<Boolean> setter) {
        super(key, label);
        this.value = value;
        this.setter = setter;
    }

    @Override
    public Component getMessage() {
        return Component.literal("[" + (value ? "ON " : "OFF ") + "] " + getLabel().getString());
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        value = !value;
        setter.accept(value);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) { return false; }
    @Override
    public boolean charTyped(char chr, int modifiers) { return false; }

    public boolean isOn() { return value; }
    public void setValue(boolean v) {
        value = v;
        if (setter != null) setter.accept(v);
    }
}
