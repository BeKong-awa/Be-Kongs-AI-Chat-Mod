package com.be_kongs_ai_chat_mod.ui;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 玩家名单选项（可添加/删除的字符串列表）。 */
class PlayerListOption extends OptionEntry {
    private final List<String> values;
    private StringBuilder input = new StringBuilder();
    private final String key;
    private final Consumer<List<String>> setter;

    PlayerListOption(String key, String label, List<String> values, Consumer<List<String>> setter) {
        super(key, label);
        this.key = key;
        this.values = values != null ? new ArrayList<>(values) : new ArrayList<>();
        this.setter = setter;
    }

    @Override
    public Component getMessage() {
        if (values.isEmpty()) return Component.literal(getLabel().getString() + " (空)");
        return Component.literal(getLabel().getString() + " [" + values.size() + "]");
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        focused = !focused;
        if (focused) input.setLength(0);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused) return false;
        if (keyCode == 257) { focused = false; String val = input.toString().trim(); if (!val.isEmpty() && !values.contains(val)) values.add(val); return true; }
        if (keyCode == 256) { focused = false; return true; }
        if (keyCode == 259) { if (input.length() > 0) input.deleteCharAt(input.length() - 1); return true; }
        if (keyCode == 261) { input.setLength(0); return true; }
        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (!focused) return false;
        if (Character.isLetterOrDigit(chr) || chr == ' ' || chr == '_') input.append(chr);
        return true;
    }

    public List<String> getValues() { return values; }
    public String getKey() { return key; }

    public void setValue(List<String> newValues) {
        this.values.clear();
        if (newValues != null) {
            this.values.addAll(newValues);
        }
    }

    /** 将当前值回写到配置。 */
    public void commit() {
        if (setter != null) {
            setter.accept(new ArrayList<>(values));
        }
    }
}
