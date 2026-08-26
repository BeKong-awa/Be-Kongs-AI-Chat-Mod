package com.be_kongs_ai_chat_mod.ui;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 文本列表选项（可编辑的字符串列表）。
 * ConfigScreen 采用集中式编辑（editingValue），本类只保存值与回写逻辑。
 */
class TextListOption extends OptionEntry {
    private final List<String> values;
    private final Consumer<List<String>> setter;

    TextListOption(String key, String label, List<String> values, Consumer<List<String>> setter) {
        super(key, label);
        this.values = values != null ? new ArrayList<>(values) : new ArrayList<>();
        this.setter = setter;
    }

    @Override
    public Component getMessage() {
        if (values.isEmpty()) return Component.literal(getLabel().getString() + " (空)");
        return Component.literal(getLabel().getString() + " [" + values.size() + "] 条目");
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        // 输入由 ConfigScreen 集中处理
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        return false;
    }

    public List<String> getValues() { return values; }

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
