package com.zhongbai233.super_lead.mixin;

import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.SignalGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SignalGetter.class)
public interface SignalGetterMixin {
    @Inject(method = "getSignal", at = @At("RETURN"), cancellable = true)
    private void superLead$addLeadSignal(BlockPos pos, Direction direction, CallbackInfoReturnable<Integer> cir) {
        int leadSignal = SuperLeadNetwork.leadSignal((SignalGetter) (Object) this, pos, direction);
        if (leadSignal > cir.getReturnValue()) {
            cir.setReturnValue(leadSignal);
        }
    }

    @Inject(method = "getDirectSignal", at = @At("RETURN"), cancellable = true)
    private void superLead$addLeadDirectSignal(BlockPos pos, Direction direction,
            CallbackInfoReturnable<Integer> cir) {
        int leadSignal = SuperLeadNetwork.leadDirectSignal((SignalGetter) (Object) this, pos, direction);
        if (leadSignal > cir.getReturnValue()) {
            cir.setReturnValue(leadSignal);
        }
    }

    @Inject(method = "hasNeighborSignal", at = @At("RETURN"), cancellable = true)
    private void superLead$hasLeadNeighborSignal(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()
                && SuperLeadNetwork.hasLeadNeighborSignal((SignalGetter) (Object) this, pos)) {
            cir.setReturnValue(true);
        }
    }
}
