package com.zhongbai233.super_lead.lead.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.phys.Vec3;

/**
 * 接管原版 EntityRenderer 中的 leash 提交：用我们的 RopeSimulation 替换那条插值曲线。
 * 每个 LeashState 实例对应一个 sim（原版 LeashState 在每个 entity 上是同一个实例复用）。
 * 长时间没被访问的 sim 会被清掉，避免泄漏。
 */
public final class VanillaLeashHook {
    private VanillaLeashHook() {}

    public static void submit(SubmitNodeCollector collector, PoseStack poseStack, EntityRenderState.LeashState state) {
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null || state.start == null || state.end == null) {
                collector.submitLeash(poseStack, state);
                return;
            }

            Vec3 a = state.start;
            Vec3 b = state.end;

            RopeSimulation sim = RopeSimulation.visualLeash(a, b);

            float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            Vec3 cameraPos = mc.gameRenderer.getMainCamera().position();
            LeashBuilder.submit(
                    collector, cameraPos, sim, partialTick,
                    state.startBlockLight, state.endBlockLight,
                    state.startSkyLight, state.endSkyLight,
                    false);
        } catch (Throwable t) {
            // 物理路径出错时回退到原版，保证不黑屏
            collector.submitLeash(poseStack, state);
        }
    }
}
