package com.zhongbai233.super_lead.mixin;

import com.mojang.logging.LogUtils;
import com.zhongbai233.super_lead.lead.SuperLeadEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-side Mixin that intercepts all outgoing packets on the client
 * {@link Connection} and drops the vanilla {@link ServerboundUseItemOnPacket}
 * when a custom {@code UseConnectionAction} was already sent during the same
 * tick.
 *
 * <h3>Compatibility notes</h3>
 * <ul>
 *   <li>Only cancels {@code ServerboundUseItemOnPacket}; all other packets
 *       pass through at full speed with a single {@code instanceof} check.</li>
 *   <li>Wrapped in try-catch — any unexpected threading issue degrades to
 *       "let the packet through" rather than crashing.</li>
 *   <li>The {@code volatile} {@code lastActionPacketTick} field is written on
 *       the render thread and read here (potentially on the network thread);
 *       stale reads are harmless — they only cause a single tick's vanilla
 *       packet to leak through.</li>
 * </ul>
 */
@Mixin(Connection.class)
public abstract class SuppressUseItemOnPacketMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0) // optional — degrade gracefully if signature changes
    private void filterSend(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ServerboundUseItemOnPacket)) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null
                    && SuperLeadEvents.wasActionPacketSentThisTick(mc.level.getGameTime())) {
                if (SuperLeadEvents.debugPackets) {
                    LogUtils.getLogger().info(
                            "[super_lead DEBUG] Mixin DROP vanilla ServerboundUseItemOnPacket tick={}",
                            mc.level.getGameTime());
                }
                ci.cancel();
            }
        } catch (RuntimeException ignored) {
            // Never crash the game because of a packet filter.
            // If anything goes wrong, let the vanilla packet through.
        }
    }
}
