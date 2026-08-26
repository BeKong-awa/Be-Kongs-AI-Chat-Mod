package com.be_kongs_ai_chat_mod.ui;

import com.be_kongs_ai_chat_mod.config.AiChatConfig;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 特殊触发词的"动作"选项：左键点击在可用动作间循环切换。
 * 直接作用于传入的 ConfigTrigger 对象（按引用写回，列表重排也不受影响）。
 */
class TriggerActionOption extends OptionEntry {
    private final AiChatConfig.ConfigTrigger trigger;

    TriggerActionOption(String key, String label, AiChatConfig.ConfigTrigger trigger) {
        super(key, label);
        this.trigger = trigger;
    }

    @Override
    public Component getMessage() {
        return Component.literal(getValueName());
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        cycle();
    }

    /** 在"面向用户"的动作集合内循环切换（跳过已废弃的旧版切换动作）。 */
    void cycle() {
        List<AiChatConfig.ConfigTriggerAction> opts = userFacingActions();
        if (opts.isEmpty()) return;
        int idx = opts.indexOf(trigger.action);
        if (idx < 0) idx = 0;
        trigger.action = opts.get((idx + 1) % opts.size());
    }

    String getValueName() {
        return trigger.action != null ? trigger.action.name() : "";
    }

    static List<AiChatConfig.ConfigTriggerAction> userFacingActions() {
        List<AiChatConfig.ConfigTriggerAction> list = new ArrayList<>();
        for (AiChatConfig.ConfigTriggerAction a : AiChatConfig.ConfigTriggerAction.values()) {
            if (!a.isDeprecatedToggle()) list.add(a);
        }
        return list;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) { return false; }
    @Override
    public boolean charTyped(char chr, int modifiers) { return false; }
}
