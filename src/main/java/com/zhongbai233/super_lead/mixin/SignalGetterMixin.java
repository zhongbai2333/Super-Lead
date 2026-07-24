package com.zhongbai233.super_lead.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.SignalGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SignalGetter.class)
public interface SignalGetterMixin {
    @ModifyReturnValue(method = "getSignal", at = @At("RETURN"))
    private int superLead$addLeadSignal(int original, BlockPos pos, Direction direction) {
        int leadSignal = SuperLeadNetwork.leadSignal((SignalGetter) (Object) this, pos, direction);
        return Math.max(original, leadSignal);
    }

    @ModifyReturnValue(method = "getDirectSignal", at = @At("RETURN"))
    private int superLead$addLeadDirectSignal(int original, BlockPos pos, Direction direction) {
        int leadSignal = SuperLeadNetwork.leadDirectSignal((SignalGetter) (Object) this, pos, direction);
        return Math.max(original, leadSignal);
    }

    @ModifyReturnValue(method = "hasNeighborSignal", at = @At("RETURN"))
    private boolean superLead$hasLeadNeighborSignal(boolean original, BlockPos pos) {
        return original || SuperLeadNetwork.hasLeadNeighborSignal((SignalGetter) (Object) this, pos);
    }
}
