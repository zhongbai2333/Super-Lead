package com.zhongbai233.super_lead.tuning.gui;

import com.zhongbai233.super_lead.tuning.DoubleTuningType;
import com.zhongbai233.super_lead.tuning.TuningKey;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

final class DoubleTuningSlider extends AbstractSliderButton {
    private final TuningKey<Double> key;
    private final DoubleTuningType type;
    private final boolean log;

    DoubleTuningSlider(int x, int y, int width, int height,
            TuningKey<Double> key, DoubleTuningType type, boolean log, double initial) {
        super(x, y, width, height, Component.empty(), initial);
        this.key = key;
        this.type = type;
        this.log = log;
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        this.setMessage(Component.empty());
    }

    @Override
    protected void applyValue() {
        double next = sliderToValue(this.value, type.min(), type.max(), log);
        next = Math.round(next * 1.0e6D) / 1.0e6D;
        key.setLocalFromString(Double.toString(next));
    }

    private static double sliderToValue(double slider, double min, double max, boolean log) {
        if (log) {
            double lo = Math.log(min);
            double hi = Math.log(max);
            return Math.exp(lo + (hi - lo) * slider);
        }
        return min + (max - min) * slider;
    }
}
