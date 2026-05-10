package com.zhongbai233.super_lead.tuning.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

public final class SuperLeadKeybindings {
    public static final KeyMapping OPEN_CONFIG = new KeyMapping(
            "key.super_lead.open_config",
            InputConstants.UNKNOWN.getValue(),
            KeyMapping.Category.MISC
    );

    private SuperLeadKeybindings() {}
}
