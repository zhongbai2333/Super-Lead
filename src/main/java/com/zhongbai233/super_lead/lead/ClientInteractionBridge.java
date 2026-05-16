package com.zhongbai233.super_lead.lead;

import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;

/**
 * Reflection boundary for client-only interaction helpers.
 *
 * <p>
 * Common event classes are loaded on a dedicated server, so they must not carry
 * constant-pool references to classes under {@code lead.client} or
 * {@code preset.client}. Keep all such names as strings here.
 */
public final class ClientInteractionBridge {
    private static final String CLIENT_EVENTS = "com.zhongbai233.super_lead.lead.client.SuperLeadClientEvents";
    private static final String ZONE_SELECTION = "com.zhongbai233.super_lead.preset.client.ZoneSelectionClient";
    private static final String PRESET_BINDER = "com.zhongbai233.super_lead.preset.client.PresetBinderClient";

    private ClientInteractionBridge() {
    }

    public static boolean tryHandleZoneBlockClick(Player player, InteractionHand hand, BlockPos pos) {
        return invokeBoolean(ZONE_SELECTION, "tryHandleBlockClick",
                new Class<?>[] { Player.class, InteractionHand.class, BlockPos.class }, player, hand, pos);
    }

    public static boolean trySendStartZipline(InteractionHand hand) {
        return invokeBoolean(CLIENT_EVENTS, "trySendStartZipline",
                new Class<?>[] { InteractionHand.class }, hand);
    }

    public static boolean trySendUseConnectionAction(InteractionHand hand, LeadConnectionAction action) {
        return invokeBoolean(CLIENT_EVENTS, "trySendUseConnectionAction",
                new Class<?>[] { InteractionHand.class, LeadConnectionAction.class }, hand, action);
    }

    public static boolean trySendRemoveRopeAttachment() {
        return invokeBoolean(CLIENT_EVENTS, "trySendRemoveRopeAttachment", new Class<?>[0]);
    }

    public static boolean trySendToggleRopeAttachmentForm() {
        return invokeBoolean(CLIENT_EVENTS, "trySendToggleRopeAttachmentForm", new Class<?>[0]);
    }

    public static boolean trySendAddRopeAttachment(InteractionHand hand) {
        return invokeBoolean(CLIENT_EVENTS, "trySendAddRopeAttachment",
                new Class<?>[] { InteractionHand.class }, hand);
    }

    public static boolean tryOpenRopeAttachmentSignEditor() {
        return invokeBoolean(CLIENT_EVENTS, "tryOpenRopeAttachmentSignEditor", new Class<?>[0]);
    }

    public static boolean tryOpenRopeAttachmentAeTerminal() {
        return invokeBoolean(CLIENT_EVENTS, "tryOpenRopeAttachmentAeTerminal", new Class<?>[0]);
    }

    public static boolean trySendSignAttachmentDye(DyeColor color) {
        return invokeBoolean(CLIENT_EVENTS, "trySendSignAttachmentDye",
                new Class<?>[] { DyeColor.class }, color);
    }

    public static boolean trySendSignAttachmentGlow() {
        return invokeBoolean(CLIENT_EVENTS, "trySendSignAttachmentGlow", new Class<?>[0]);
    }

    public static void sendPresetBinderToggleRope(InteractionHand hand) {
        invokeVoid(PRESET_BINDER, "sendToggleRope", new Class<?>[] { InteractionHand.class }, hand);
    }

    public static void openOrEditPresetBinder(ItemStack stack, InteractionHand hand) {
        invokeVoid(PRESET_BINDER, "openOrEdit",
                new Class<?>[] { ItemStack.class, InteractionHand.class }, stack, hand);
    }

    private static boolean invokeBoolean(String className, String method, Class<?>[] parameterTypes, Object... args) {
        Object result = invoke(className, method, parameterTypes, args);
        return result instanceof Boolean value && value;
    }

    private static void invokeVoid(String className, String method, Class<?>[] parameterTypes, Object... args) {
        invoke(className, method, parameterTypes, args);
    }

    private static Object invoke(String className, String method, Class<?>[] parameterTypes, Object... args) {
        try {
            Class<?> type = Class.forName(className);
            Method m = type.getMethod(method, parameterTypes);
            return m.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to invoke client interaction bridge: " + className + "#" + method, e);
        }
    }
}
