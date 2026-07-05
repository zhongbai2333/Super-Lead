package com.zhongbai233.super_lead.lead;

import com.mojang.logging.LogUtils;
import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.integration.ae2.AE2LeadMaterials;
import com.zhongbai233.super_lead.lead.integration.mekanism.MekanismLeadMaterials;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.TriState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

@EventBusSubscriber(modid = Super_lead.MODID)
/**
 * Server/gameplay event hub for rope lifecycle maintenance.
 *
 * <p>
 * This class wires NeoForge events into the rope systems: placement cleanup,
 * transfer ticks, redstone updates, entity interaction hooks and data component
 * registration. Keep one-off event glue here; move substantial state machines
 * into package-private services so event handlers stay readable.
 */
public final class SuperLeadEvents {
    private static final Logger LOG = LogUtils.getLogger();

    /** Toggle via /superlead debug packets */
    public static volatile boolean debugPackets;

    /**
     * Last client tick a custom UseConnectionAction was sent.
     * Read by {@code SuppressUseItemOnPacketMixin} to suppress the vanilla packet.
     */
    public static volatile long lastActionPacketTick = -1;

    public static boolean wasActionPacketSentThisTick(long tick) {
        return tick == lastActionPacketTick;
    }

    private SuperLeadEvents() {
    }

    private static void consumeBlockInteraction(PlayerInteractEvent.RightClickBlock event, InteractionResult result) {
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        event.setCanceled(true);
        event.setCancellationResult(result);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();

        if (tryUsePresetBinder(event.getEntity(), event.getHand(), event.getLevel(),
                event.getEntity().isShiftKeyDown())) {
            consumeBlockInteraction(event, InteractionResult.SUCCESS);
            return;
        }

        if (tryUseZoneSelectionTool(event)) {
            return;
        }

        if (ZiplineController.isChain(stack) && tryStartZipline(event)) {
            return;
        }

        // Sneak-gated rope-targeting actions: cut, upgrade, extract toggle, remove
        // attachment.
        if (event.getEntity().isShiftKeyDown()) {
            if (stack.is(Items.SHEARS) && tryUseConnectionAction(event)) {
                return;
            }
            if (isSeedItem(stack) && tryBoostRopePerch(event)) {
                consumeBlockInteraction(event, InteractionResult.SUCCESS);
                return;
            }
            if (tryUseConnectionAction(event)) {
                return;
            }
        } else {
            if (tryOpenRopeAttachmentAeTerminal(event.getEntity(), event.getLevel(), stack)) {
                consumeBlockInteraction(event, InteractionResult.SUCCESS);
                return;
            }
            if (tryEditRopeAttachmentSign(event.getEntity(), event.getLevel(), stack)) {
                consumeBlockInteraction(event, InteractionResult.SUCCESS);
                return;
            }
            if (tryRopeAttachmentSignDye(event.getEntity(), event.getLevel(), stack)) {
                consumeBlockInteraction(event, InteractionResult.SUCCESS);
                return;
            }
            if (tryRopeAttachmentSignGlow(event.getEntity(), event.getLevel(), stack)) {
                consumeBlockInteraction(event, InteractionResult.SUCCESS);
                return;
            } // Non-sneak: attach decoration to upgraded ropes. Kept separate from the
              // upgrade
              // path so a player carrying e.g. redstone dust can both upgrade (sneak) and
              // decorate
              // (no sneak) without conflict.
            if (tryAddRopeAttachment(event.getEntity(), event.getHand(), event.getLevel())) {
                consumeBlockInteraction(event, InteractionResult.SUCCESS);
                return;
            }
        }

        if (tryUpgradeHeldLead(event.getEntity(), event.getHand())) {
            consumeBlockInteraction(event, InteractionResult.SUCCESS);
            return;
        }

        if (!SuperLeadItems.isSuperLead(stack) || event.getLevel().getBlockState(event.getPos()).isAir()) {
            return;
        }

        LeadAnchor anchor = createAnchor(event);
        InteractionResult result = handleLeadUse(event.getLevel(), event.getEntity(), stack, anchor);

        consumeBlockInteraction(event, result);
    }

    private static boolean tryUseZoneSelectionTool(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide())
            return false;
        if (!event.getEntity().isShiftKeyDown())
            return false;
        if (!event.getItemStack().is(Items.SHEARS))
            return false;
        if (!ClientInteractionBridge.tryHandleZoneBlockClick(
                event.getEntity(), event.getHand(), event.getPos())) {
            return false;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        return true;
    }

    private static boolean tryStartZipline(PlayerInteractEvent.RightClickBlock event) {
        if (!ZiplineController.isChain(event.getItemStack()))
            return false;
        if (!event.getLevel().isClientSide())
            return false;
        if (!ClientInteractionBridge.trySendStartZipline(event.getHand()))
            return false;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        return true;
    }

    private static boolean tryStartZiplineItem(PlayerInteractEvent.RightClickItem event) {
        if (!event.getLevel().isClientSide())
            return false;
        return ClientInteractionBridge.trySendStartZipline(event.getHand());
    }

    private static void tryStartZiplineEmpty(PlayerInteractEvent.RightClickEmpty event) {
        if (!event.getLevel().isClientSide())
            return;
        ClientInteractionBridge.trySendStartZipline(event.getHand());
    }

    private static boolean tryUseConnectionAction(PlayerInteractEvent.RightClickBlock event) {
        boolean isClient = event.getLevel().isClientSide();
        if (debugPackets) {
            LOG.info("[super_lead DEBUG] tryUseConnectionAction ENTER side={} item={} shift={}",
                    isClient ? "CLIENT" : "SERVER",
                    event.getItemStack().getDisplayName().getString(),
                    event.getEntity().isShiftKeyDown());
        }
        if (!isClient) {
            if (debugPackets)
                LOG.info("[super_lead DEBUG] tryUseConnectionAction EXIT reason=SERVER_SKIP");
            return false;
        }
        if (!SuperLeadNetwork.canModifyRopes(event.getEntity())) {
            if (debugPackets)
                LOG.info("[super_lead DEBUG] tryUseConnectionAction EXIT reason=CANT_MODIFY");
            return false;
        }
        ItemStack stack = event.getItemStack();
        LeadConnectionAction action = LeadConnectionAction.fromStack(stack).orElse(null);
        if (action == null) {
            if (debugPackets)
                LOG.info("[super_lead DEBUG] tryUseConnectionAction EXIT reason=NO_ACTION");
            return false;
        }
        if (!sendClientUseAction(event.getEntity(), event.getHand(), action)) {
            if (debugPackets)
                LOG.info("[super_lead DEBUG] tryUseConnectionAction EXIT reason=SEND_FAILED action={}", action.name());
            return false;
        }
        if (debugPackets)
            LOG.info("[super_lead DEBUG] tryUseConnectionAction SENT custom packet action={}", action.name());
        consumeBlockInteraction(event, InteractionResult.CONSUME);
        if (debugPackets)
            LOG.info("[super_lead DEBUG] tryUseConnectionAction EXIT reason=SUCCESS eventCanceled");
        return true;
    }

    /**
     * Sends a custom UseConnectionAction packet to the server and records the
     * tick so {@code SuppressUseItemOnPacketMixin} can drop the vanilla
     * ServerboundUseItemOnPacket.
     *
     * @return true when a packet was sent (or already sent this tick)
     */
    private static boolean sendClientUseAction(Player player, InteractionHand hand, LeadConnectionAction action) {
        long tick = player.level().getGameTime();
        if (tick == lastActionPacketTick) {
            if (debugPackets)
                LOG.info("[super_lead DEBUG] sendClientUseAction SKIP duplicate tick={}", tick);
            return true;
        }
        boolean sent = ClientInteractionBridge.trySendUseConnectionAction(hand, action);
        if (sent) {
            lastActionPacketTick = tick;
        }
        return sent;
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        ItemStack stack = event.getItemStack();
        boolean shift = event.getEntity().isShiftKeyDown();

        if (!shift && tryOpenRopeAttachmentAeTerminal(event.getEntity(), event.getLevel(), stack)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        if (tryUsePresetBinder(event.getEntity(), event.getHand(), event.getLevel(), shift)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        if (ZiplineController.isChain(stack) && tryStartZiplineItem(event)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        // Seed rope boost also works when right-clicking in the air.
        if (shift && isSeedItem(stack) && tryBoostRopePerchItem(event)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        // Connection actions (shears, redstone, energy, item, fluid) all require sneak
        // now so
        // they don't conflict with attachment placement.
        LeadConnectionAction action = LeadConnectionAction.fromStack(stack).orElse(null);
        if (action != null && shift && event.getLevel().isClientSide()) {
            if (sendClientUseAction(event.getEntity(), event.getHand(), action)) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.CONSUME);
                return;
            }
        }

        if (tryUpgradeHeldLead(event.getEntity(), event.getHand())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        if (!shift && tryAddRopeAttachment(event.getEntity(), event.getHand(), event.getLevel())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        if (!shift && tryRopeAttachmentSignDye(event.getEntity(), event.getLevel(), stack)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        if (!shift && tryRopeAttachmentSignGlow(event.getEntity(), event.getLevel(), stack)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        if (!shift && tryEditRopeAttachmentSign(event.getEntity(), event.getLevel(), stack)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    public static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        if (!event.getLevel().isClientSide())
            return;
        ItemStack stack = event.getItemStack();
        boolean shift = event.getEntity().isShiftKeyDown();
        if (ZiplineController.isChain(stack)) {
            tryStartZiplineEmpty(event);
            return;
        }
        if (tryUsePresetBinder(event.getEntity(), event.getHand(), event.getLevel(), shift)) {
            return;
        }
        if (tryOpenRopeAttachmentAeTerminal(event.getEntity(), event.getLevel(), stack)) {
            return;
        }
        if (tryRopeAttachmentSignDye(event.getEntity(), event.getLevel(), stack)) {
            return;
        }
        if (tryRopeAttachmentSignGlow(event.getEntity(), event.getLevel(), stack)) {
            return;
        }
        if (tryEditRopeAttachmentSign(event.getEntity(), event.getLevel(), stack)) {
            return;
        }
        if (com.zhongbai233.super_lead.lead.RopeAttachmentItems.isAttachable(stack)) {
            tryAddRopeAttachment(event.getEntity(), event.getHand(), event.getLevel());
        }
    }

    @SubscribeEvent
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        if (!event.getLevel().isClientSide())
            return;
        if (!SuperLeadNetwork.canModifyRopes(event.getEntity()))
            return;
        ItemStack stack = event.getItemStack();
        if (event.getEntity().isShiftKeyDown() && stack.is(Items.SHEARS)) {
            // Shears + Shift + Left-click -> remove rope attachment.
            ClientInteractionBridge.trySendRemoveRopeAttachment();
            return;
        }
        // Plain left-click on a rope attachment with both block- and item-display forms
        // toggles between the two. The packet path filters on BlockItem, so misses are
        // harmless.
        ClientInteractionBridge.trySendToggleRopeAttachmentForm();
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getLevel().isClientSide())
            return;
        if (!SuperLeadNetwork.canModifyRopes(event.getEntity()))
            return;
        ItemStack stack = event.getItemStack();
        if (event.getEntity().isShiftKeyDown() && stack.is(Items.SHEARS)) {
            // Shears + Shift + Left-click on block -> remove rope attachment.
            if (ClientInteractionBridge.trySendRemoveRopeAttachment()) {
                event.setCanceled(true);
            }
        }
    }

    /**
     * Attempt to add the held attachable item to a rope under the player's
     * crosshair.
     * Client-only entry point; server is driven by the resulting packet.
     */
    private static boolean tryAddRopeAttachment(Player player, InteractionHand hand, Level level) {
        if (!level.isClientSide())
            return false;
        if (!SuperLeadNetwork.canModifyRopes(player))
            return false;
        ItemStack stack = player.getItemInHand(hand);
        if (!com.zhongbai233.super_lead.lead.RopeAttachmentItems.isAttachable(stack))
            return false;
        boolean sent = ClientInteractionBridge.trySendAddRopeAttachment(hand);
        if (sent) {
            lastActionPacketTick = level.getGameTime();
        }
        return sent;
    }

    private static boolean tryUsePresetBinder(Player player, InteractionHand hand, Level level, boolean shift) {
        if (!level.isClientSide())
            return false;
        ItemStack stack = player.getItemInHand(hand);
        if (!SuperLeadItems.isPresetBinder(stack))
            return false;
        if (shift) {
            ClientInteractionBridge.sendPresetBinderToggleRope(hand);
        } else {
            ClientInteractionBridge.openOrEditPresetBinder(stack, hand);
        }
        return true;
    }

    /**
     * Attempt to edit the sign text of the rope attachment under the crosshair.
     * Client-only.
     */
    private static boolean tryEditRopeAttachmentSign(Player player, Level level, ItemStack heldStack) {
        if (!level.isClientSide())
            return false;
        if (!SuperLeadNetwork.canModifyRopes(player))
            return false;
        // Holding an attachable item keeps the placement action first; use empty hand
        // or a non-attachable item to edit an existing sign.
        if (com.zhongbai233.super_lead.lead.RopeAttachmentItems.isAttachable(heldStack))
            return false;
        // Dye and glow ink require a dedicated action, not the editor.
        if (net.minecraft.world.item.DyeColor.getColor(heldStack) != null)
            return false;
        if (heldStack.is(Items.GLOW_INK_SAC))
            return false;
        return ClientInteractionBridge.tryOpenRopeAttachmentSignEditor();
    }

    /** Attempt to open an AE2 terminal attachment. Client-only. */
    private static boolean tryOpenRopeAttachmentAeTerminal(Player player, Level level, ItemStack heldStack) {
        if (!level.isClientSide())
            return false;
        // AE terminal panels are interactive UI surfaces. If the crosshair is on one,
        // opening the panel should win over vanilla item use such as block placement.
        return ClientInteractionBridge.tryOpenRopeAttachmentAeTerminal();
    }

    /** Attempt to dye a sign attachment. Client-only. */
    private static boolean tryRopeAttachmentSignDye(Player player, Level level, ItemStack heldStack) {
        if (!level.isClientSide())
            return false;
        if (!SuperLeadNetwork.canModifyRopes(player))
            return false;
        net.minecraft.world.item.DyeColor color = net.minecraft.world.item.DyeColor.getColor(heldStack);
        if (color == null)
            return false;
        return ClientInteractionBridge.trySendSignAttachmentDye(color);
    }

    /** Attempt to apply glow ink to a sign attachment. Client-only. */
    private static boolean tryRopeAttachmentSignGlow(Player player, Level level, ItemStack heldStack) {
        if (!level.isClientSide())
            return false;
        if (!SuperLeadNetwork.canModifyRopes(player))
            return false;
        if (!heldStack.is(Items.GLOW_INK_SAC))
            return false;
        return ClientInteractionBridge.trySendSignAttachmentGlow();
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof LeashFenceKnotEntity knot)) {
            return;
        }

        ItemStack stack = event.getItemStack();
        LeadAnchor anchor = new LeadAnchor(knot.getPos(), Direction.UP);
        if (!SuperLeadNetwork.canModifyRopes(event.getEntity())) {
            if (SuperLeadItems.isSuperLead(stack)) {
                InteractionResult result = handleLeadUse(event.getLevel(), event.getEntity(), stack, anchor);
                event.setCanceled(true);
                event.setCancellationResult(result);
                return;
            }
            if (stack.is(Items.SHEARS) && SuperLeadNetwork.hasConnectionAttachedTo(event.getLevel(), anchor)) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.FAIL);
            }
            return;
        }

        if (SuperLeadItems.isSuperLead(stack)) {
            InteractionResult result = handleLeadUse(event.getLevel(), event.getEntity(), stack, anchor);
            event.setCanceled(true);
            event.setCancellationResult(result);
            return;
        }

        if (!stack.is(Items.SHEARS)) {
            return;
        }

        boolean handled = event.getLevel().isClientSide()
                ? SuperLeadNetwork.hasConnectionAttachedTo(event.getLevel(), anchor)
                : SuperLeadNetwork.cutAttachedTo(event.getLevel(), anchor, event.getEntity()) > 0;
        if (handled) {
            if (!event.getLevel().isClientSide() && !event.getEntity().isCreative()) {
                stack.hurtAndBreak(1, event.getEntity(), event.getHand());
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    private static InteractionResult handleLeadUse(Level level, Player player, ItemStack stack, LeadAnchor anchor) {
        InteractionResult result = InteractionResult.SUCCESS;
        LeadKind stackKind = SuperLeadItemData.kind(stack);
        var pending = SuperLeadNetwork.pendingAnchor(player);
        if (pending.isPresent()) {
            LeadAnchor first = pending.get();
            LeadKind kind = SuperLeadNetwork.pendingKind(player).orElse(stackKind);
            int lengthUnits = SuperLeadNetwork.pendingLengthUnits(player);
            if (stackKind != kind) {
                refundPendingLengthExtensions(level, player, kind, lengthUnits);
                SuperLeadNetwork.clearPendingAnchor(player);
                clearLeashingState(stack);
                return InteractionResult.FAIL;
            }
            if (first.equals(anchor)) {
                if (lengthUnits < SuperLeadNetwork.MAX_LENGTH_UNITS
                        && canSpendPendingLengthExtension(level, player, stack)
                        && SuperLeadNetwork.extendPendingLength(player)) {
                    if (!level.isClientSide() && !player.isCreative()) {
                        stack.shrink(1);
                    }
                    markLeashingState(stack);
                    return InteractionResult.SUCCESS;
                }
                refundPendingLengthExtensions(level, player, kind, lengthUnits);
                SuperLeadNetwork.clearPendingAnchor(player);
                clearLeashingState(stack);
            } else if (first.attachmentPoint(level)
                    .distanceTo(
                            anchor.attachmentPoint(level)) > SuperLeadNetwork.maxLeashDistanceForUnits(lengthUnits)) {
                refundPendingLengthExtensions(level, player, kind, lengthUnits);
                SuperLeadNetwork.clearPendingAnchor(player);
                clearLeashingState(stack);
                result = InteractionResult.FAIL;
            } else if (!SuperLeadNetwork.canCreateRopePlacement(level, player, first, anchor)) {
                refundPendingLengthExtensions(level, player, kind, lengthUnits);
                SuperLeadNetwork.clearPendingAnchor(player);
                clearLeashingState(stack);
                result = InteractionResult.FAIL;
            } else {
                LeadConnection created = SuperLeadNetwork.connect(level, first, anchor, kind, player, lengthUnits);
                SuperLeadNetwork.clearPendingAnchor(player);
                clearLeashingState(stack);
                if (created == null) {
                    refundPendingLengthExtensions(level, player, kind, lengthUnits);
                    result = InteractionResult.FAIL;
                } else if (!level.isClientSide() && !player.isCreative()) {
                    stack.shrink(1);
                }
            }
        } else {
            if (!SuperLeadNetwork.canStartRopePlacement(level, player, anchor)) {
                SuperLeadNetwork.clearPendingAnchor(player);
                clearLeashingState(stack);
                return InteractionResult.FAIL;
            }
            SuperLeadNetwork.setPendingAnchor(player, anchor, stackKind);
            markLeashingState(stack);
        }
        return result;
    }

    private static boolean canSpendPendingLengthExtension(Level level, Player player, ItemStack stack) {
        return level.isClientSide() || player.isCreative() || stack.getCount() > 1;
    }

    private static void refundPendingLengthExtensions(Level level, Player player, LeadKind kind, int lengthUnits) {
        int extra = Math.max(0, lengthUnits - LeadConnection.MIN_LENGTH_UNITS);
        if (extra <= 0 || level.isClientSide() || player.isCreative()) {
            return;
        }
        ItemStack refund = SuperLeadItems.stack(kind);
        refund.setCount(extra);
        if (!player.addItem(refund)) {
            player.drop(refund, false);
        }
    }

    private static void refundAndClearPendingLength(Player player) {
        if (SuperLeadNetwork.pendingAnchor(player).isEmpty()) {
            return;
        }
        int lengthUnits = SuperLeadNetwork.pendingLengthUnits(player);
        LeadKind kind = SuperLeadNetwork.pendingKind(player).orElse(LeadKind.NORMAL);
        refundPendingLengthExtensions(player.level(), player, kind, lengthUnits);
        SuperLeadNetwork.clearPendingAnchor(player);
        clearLeashingState(player.getMainHandItem());
        clearLeashingState(player.getOffhandItem());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        for (Player player : event.getLevel().players()) {
            SuperLeadNetwork.pendingAnchor(player).ifPresent(anchor -> {
                int lengthUnits = SuperLeadNetwork.pendingLengthUnits(player);
                if (anchor.attachmentPoint(event.getLevel())
                        .distanceTo(player.getRopeHoldPosition(1.0F)) > SuperLeadNetwork
                                .maxLeashDistanceForUnits(lengthUnits)) {
                    refundPendingLengthExtensions(event.getLevel(), player,
                            SuperLeadNetwork.pendingKind(player).orElse(LeadKind.NORMAL), lengthUnits);
                    SuperLeadNetwork.clearPendingAnchor(player);
                    clearLeashingState(player.getMainHandItem());
                    clearLeashingState(player.getOffhandItem());
                }
            });
        }
        if (!event.getLevel().isClientSide() && event.getLevel().getGameTime() % 20L == 0L) {
            SuperLeadNetwork.pruneInvalid(event.getLevel());
        }
        LeadServerTickDispatcher.tick(event.getLevel());
    }

    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel().isClientSide() || !(event.getLevel() instanceof Level level))
            return;
        ServerRopeCurve.invalidateTerrain();
        SuperLeadNetwork.pruneInvalid(level);
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            RopeTripController.stopEverywhere(player);
            ZiplineController.stopEverywhere(player);
            SuperLeadPayloads.sendToPlayer(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            refundAndClearPendingLength(player);
            RopeTripController.stopEverywhere(player);
            ZiplineController.stopEverywhere(player);
            SuperLeadPayloads.sendToPlayer(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            refundAndClearPendingLength(player);
            RopeTripController.stopEverywhere(player);
            ZiplineController.stopEverywhere(player);
        }
    }

    @SubscribeEvent
    public static void onChunkSent(ChunkWatchEvent.Sent event) {
        SuperLeadPayloads.sendChunkToPlayer(event.getLevel(), event.getPlayer(), event.getPos());
    }

    @SubscribeEvent
    public static void onChunkUnwatch(ChunkWatchEvent.UnWatch event) {
        SuperLeadPayloads.unloadChunkForPlayer(event.getPlayer(), event.getPos());
    }

    private static void markLeashingState(ItemStack stack) {
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
    }

    private static void clearLeashingState(ItemStack stack) {
        stack.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
    }

    private static boolean tryUpgradeHeldLead(Player player, InteractionHand usedHand) {
        if (!SuperLeadNetwork.canModifyRopes(player)) {
            return false;
        }
        ItemStack used = player.getItemInHand(usedHand);
        InteractionHand otherHand = usedHand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND
                : InteractionHand.MAIN_HAND;
        ItemStack other = player.getItemInHand(otherHand);

        LeadKind usedMaterialKind = upgradeKindFromMaterial(used);
        if (usedMaterialKind != null && SuperLeadItems.isSuperLead(other)
                && SuperLeadItemData.kind(other) != usedMaterialKind) {
            upgradeOneLead(player, otherHand, other, used, usedMaterialKind);
            return true;
        }

        LeadKind otherMaterialKind = upgradeKindFromMaterial(other);
        if (SuperLeadItems.isSuperLead(used) && otherMaterialKind != null
                && SuperLeadItemData.kind(used) != otherMaterialKind) {
            upgradeOneLead(player, usedHand, used, other, otherMaterialKind);
            return true;
        }
        return false;
    }

    private static LeadKind upgradeKindFromMaterial(ItemStack stack) {
        if (stack.is(Items.REDSTONE_BLOCK)) {
            return LeadKind.REDSTONE;
        }
        if (stack.is(Items.IRON_BLOCK)) {
            return LeadKind.ENERGY;
        }
        if (stack.is(Items.HOPPER)) {
            return LeadKind.ITEM;
        }
        if (stack.is(Items.CAULDRON)) {
            return LeadKind.FLUID;
        }
        if (MekanismLeadMaterials.isSteelBlock(stack)) {
            return LeadKind.PRESSURIZED;
        }
        if (MekanismLeadMaterials.isMekanismLoaded() && stack.is(Items.COPPER_BLOCK)) {
            return LeadKind.THERMAL;
        }
        if (AE2LeadMaterials.isFluixBlock(stack)) {
            return LeadKind.AE_NETWORK;
        }
        return null;
    }

    private static void upgradeOneLead(Player player, InteractionHand leadHand, ItemStack leadStack,
            ItemStack materialStack, LeadKind kind) {
        if (player.level().isClientSide()) {
            return;
        }

        ItemStack upgraded = SuperLeadItems.stack(kind);
        if (!player.isCreative()) {
            leadStack.shrink(1);
            materialStack.shrink(1);
        }

        if (leadStack.isEmpty()) {
            player.setItemInHand(leadHand, upgraded);
        } else if (!player.addItem(upgraded)) {
            player.drop(upgraded, false);
        }
    }

    private static LeadAnchor createAnchor(PlayerInteractEvent.RightClickBlock event) {
        Direction face = LeadAnchor.knotFace(event.getLevel().getBlockState(event.getPos()),
                event.getHitVec().getDirection());
        return new LeadAnchor(event.getPos().immutable(), face);
    }

    private static boolean isSeedItem(ItemStack stack) {
        return stack.is(Items.WHEAT_SEEDS) || stack.is(Items.MELON_SEEDS)
                || stack.is(Items.PUMPKIN_SEEDS) || stack.is(Items.BEETROOT_SEEDS)
                || stack.is(Items.TORCHFLOWER_SEEDS) || stack.is(Items.PITCHER_POD);
    }

    private static boolean tryBoostRopePerch(PlayerInteractEvent.RightClickBlock event) {
        return tryBoostRopePerch(event.getLevel(), event.getEntity(), event.getItemStack());
    }

    private static boolean tryBoostRopePerchItem(PlayerInteractEvent.RightClickItem event) {
        return tryBoostRopePerch(event.getLevel(), event.getEntity(), event.getItemStack());
    }

    private static boolean tryBoostRopePerch(Level level, Player player, ItemStack stack) {
        if (!isSeedItem(stack))
            return false;
        if (!level.isClientSide())
            return false;
        if (!SuperLeadNetwork.canModifyRopes(player))
            return false;
        boolean sent = ClientInteractionBridge.trySendBoostRopePerch();
        if (sent) {
            lastActionPacketTick = level.getGameTime();
        }
        return sent;
    }
}
