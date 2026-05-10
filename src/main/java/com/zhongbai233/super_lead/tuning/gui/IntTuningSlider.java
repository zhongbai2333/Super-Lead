package com.zhongbai233.super_lead.tuning.gui;

import com.zhongbai233.super_lead.tuning.IntTuningType;
import com.zhongbai233.super_lead.tuning.TuningKey;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

final class IntTuningSlider extends AbstractSliderButton {
    private final TuningKey<Integer> key;
    private final IntTuningType type;

    IntTuningSlider(int x, int y, int width, int height,
            TuningKey<Integer> key, IntTuningType type, double initial) {
        super(x, y, width, height, Component.empty(), initial);
        this.key = key;
        this.type = type;
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        this.setMessage(Component.empty());
    }

    @Override
    protected void applyValue() {
        int span = type.max() - type.min();
        int next = type.min() + (int) Math.round(this.value * span);
        key.setLocalFromString(Integer.toString(next));
    }
}
