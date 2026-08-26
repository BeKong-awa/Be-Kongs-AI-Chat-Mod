package com.be_kongs_ai_chat_mod.ui;

import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

/** 整数输入选项。 */
class IntOption extends OptionEntry {
    private int value;
    private StringBuilder input = new StringBuilder();
    private final Consumer<Integer> setter;

    IntOption(String key, String label, int value, Consumer<Integer> setter) {
        super(key, label);
        this.value = value;
        this.setter = setter;
        input.append(value);
    }

    @Override
    public Component getMessage() {
        return Component.literal(getLabel().getString() + " = " + value);
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        focused = !focused;
        if (focused) input.setLength(0);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused) return false;
        if (keyCode == 257) { focused = false; try { value = Integer.parseInt(input.toString().trim()); setter.accept(value); } catch (NumberFormatException ignored) {} return true; }
        if (keyCode == 256) { focused = false; return true; }
        if (keyCode == 259) { if (input.length() > 0) input.deleteCharAt(input.length() - 1); return true; }
        if (keyCode == 261) { input.setLength(0); return true; }
        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (!focused) return false;
        if (Character.isDigit(chr) || chr == '-') input.append(chr);
        return true;
    }

    public int getValue() { return value; }
    public void setValue(int v) {
        this.value = v;
        this.input.setLength(0);
        this.input.append(v);
        if (setter != null) setter.accept(v);
    }
}
