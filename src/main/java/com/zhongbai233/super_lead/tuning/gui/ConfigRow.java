package com.zhongbai233.super_lead.tuning.gui;

import com.zhongbai233.super_lead.tuning.TuningKey;
import net.minecraft.client.gui.components.AbstractWidget;

record ConfigRow(AbstractWidget widget, AbstractWidget reset, TuningKey<?> key, int baseY) {}
