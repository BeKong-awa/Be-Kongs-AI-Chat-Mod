package com.be_kongs_ai_chat_mod.ui;

import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

/** 双精度浮点数输入选项。 */
class DoubleOption extends OptionEntry {
    private double value;
    private StringBuilder input = new StringBuilder();
    private final Consumer<Double> setter;

    DoubleOption(String key, String label, double value, Consumer<Double> setter) {
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
        if (keyCode == 257) { focused = false; try { value = Double.parseDouble(input.toString().trim()); setter.accept(value); } catch (NumberFormatException ignored) {} return true; }
        if (keyCode == 256) { focused = false; return true; }
        if (keyCode == 259) { if (input.length() > 0) input.deleteCharAt(input.length() - 1); return true; }
        if (keyCode == 261) { input.setLength(0); return true; }
        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (!focused) return false;
        if (Character.isDigit(chr) || chr == '.' || chr == '-') input.append(chr);
        return true;
    }

    public double getValue() { return value; }
    public void setValue(double v) {
        this.value = v;
        this.input.setLength(0);
        this.input.append(v);
        if (setter != null) setter.accept(v);
    }
}
