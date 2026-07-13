package com.zhongbai233.super_lead.mixin;

import com.zhongbai233.super_lead.lead.client.chunk.StaticRopeChunkLifecycle;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bridges actual client world mutations to static-rope mesh invalidation. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelBlockUpdateMixin {

    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void superLead$onBlockStateChanged(
            BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        if (oldState != newState) {
            StaticRopeChunkLifecycle.onClientBlockChanged((ClientLevel) (Object) this, pos);
        }
    }
}