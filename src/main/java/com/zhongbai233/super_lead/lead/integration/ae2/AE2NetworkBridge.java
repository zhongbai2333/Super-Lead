package com.zhongbai233.super_lead.lead.integration.ae2;

import appeng.api.config.Settings;
import appeng.api.config.ShowPatternProviders;
import appeng.api.config.SortDir;
import appeng.api.config.SortOrder;
import appeng.api.config.ViewItems;
import appeng.api.ids.AEComponents;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.inventories.ISegmentedInventory;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.storage.ILinkStatus;
import appeng.api.storage.IPatternAccessTermMenuHost;
import appeng.api.storage.ITerminalHost;
import appeng.api.storage.MEStorage;
import appeng.api.storage.SupplierStorage;
import appeng.api.util.IConfigManager;
import appeng.helpers.IPatternTerminalLogicHost;
import appeng.helpers.IPatternTerminalMenuHost;
import appeng.menu.implementations.PatternAccessTermMenu;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.me.items.CraftingTermMenu;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.parts.encoding.PatternEncodingLogic;
import appeng.parts.reporting.CraftingTerminalPart;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import com.zhongbai233.super_lead.lead.LeadAnchor;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.RopeAttachment;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Runtime bridge between Super Lead AE-network ropes and AE2's official grid API.
 *
 * <p>
 * The bridge intentionally stays outside core rope logic so Super Lead can still
 * load without AE2. Callers must guard access with {@code ModList#isLoaded("ae2")}
 * before touching this class.
 */
public final class AE2NetworkBridge {
    private static final String PATTERN_ENCODING_DATA_KEY = "super_lead_ae2_pattern_encoding";
    private static final Map<ResourceKey<Level>, Map<UUID, BridgeLink>> LINKS = new HashMap<>();
    private static boolean menuLocatorsRegistered;
    private static final IGridNodeListener<BridgeNodeOwner> NODE_LISTENER = new IGridNodeListener<>() {
        @Override
        public void onSaveChanges(BridgeNodeOwner nodeOwner, IGridNode node) {
            // Transient bridge nodes have no persistent AE node state of their own.
        }
    };

    private AE2NetworkBridge() {
    }

    public static synchronized void registerMenuLocators() {
        if (menuLocatorsRegistered) {
            return;
        }
        appeng.menu.locator.MenuLocators.register(RopeAeTerminalLocator.class,
                RopeAeTerminalLocator::writeToPacket,
                RopeAeTerminalLocator::readFromPacket);
        menuLocatorsRegistered = true;
    }

    public static boolean isTerminalItem(ItemStack stack) {
        return terminalKind(stack) != null;
    }

    public static boolean openTerminal(ServerLevel level, ServerPlayer player, LeadConnection connection,
            RopeAttachment attachment) {
        TerminalKind terminal = terminalKind(attachment.stack());
        if (connection.kind() != LeadKind.AE_NETWORK || terminal == null) {
            return false;
        }
        if (terminalNode(level.dimension(), connection.id()) == null) {
            reconcile(level, currentAeConnections(level));
        }
        if (terminalNode(level.dimension(), connection.id()) == null) {
            return false;
        }
        registerMenuLocators();
        return appeng.menu.MenuOpener.open(terminal.menuType(), player,
                new RopeAeTerminalLocator(connection.id(), attachment.id()));
    }

    public static ItemStack dropStoredContents(ServerLevel level, ItemStack stack, Vec3 point) {
        ItemStack updated = stack.copyWithCount(1);
        TerminalKind terminal = terminalKind(updated);
        if (terminal == TerminalKind.CRAFTING) {
            ItemContainerContents contents = updated.get(AEComponents.CRAFTING_INV);
            if (contents != null) {
                contents.nonEmptyItemCopyStream().forEach(item -> dropStoredItem(level, point, item));
                updated.remove(AEComponents.CRAFTING_INV);
            }
        } else if (terminal == TerminalKind.PATTERN_ENCODING) {
            CustomData customData = updated.get(DataComponents.CUSTOM_DATA);
            if (customData != null && customData.contains(PATTERN_ENCODING_DATA_KEY)) {
                CompoundTag root = customData.copyTag();
                CompoundTag data = root.getCompoundOrEmpty(PATTERN_ENCODING_DATA_KEY);
                PatternEncodingDropHost host = new PatternEncodingDropHost(level);
                host.getLogic().readFromNBT(TagValueInput.create(ProblemReporter.DISCARDING,
                        level.registryAccess(), data));
                dropInventory(level, point, host.getLogic().getBlankPatternInv());
                dropInventory(level, point, host.getLogic().getEncodedPatternInv());
                root.remove(PATTERN_ENCODING_DATA_KEY);
                if (root.isEmpty()) {
                    updated.remove(DataComponents.CUSTOM_DATA);
                } else {
                    updated.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
                }
            }
        }
        return updated;
    }

    private static void dropInventory(ServerLevel level, Vec3 point, InternalInventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            dropStoredItem(level, point, inventory.getStackInSlot(slot));
        }
    }

    private static void dropStoredItem(ServerLevel level, Vec3 point, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemEntity entity = new ItemEntity(level, point.x, point.y, point.z, stack.copy());
        entity.setDefaultPickUpDelay();
        level.addFreshEntity(entity);
    }

    private static TerminalKind terminalKind(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        net.minecraft.resources.Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!id.getNamespace().equals("ae2")) {
            return null;
        }
        return switch (id.getPath()) {
            case "terminal" -> TerminalKind.TERMINAL;
            case "crafting_terminal" -> TerminalKind.CRAFTING;
            case "pattern_encoding_terminal" -> TerminalKind.PATTERN_ENCODING;
            case "pattern_access_terminal" -> TerminalKind.PATTERN_ACCESS;
            default -> null;
        };
    }

    public static void reconcile(ServerLevel level, List<LeadConnection> aeConnections) {
        ResourceKey<Level> dimension = level.dimension();
        Map<UUID, BridgeLink> active = LINKS.computeIfAbsent(dimension, ignored -> new HashMap<>());
        Set<UUID> desired = new HashSet<>();

        for (LeadConnection connection : aeConnections) {
            desired.add(connection.id());
            BridgeLink link = active.get(connection.id());
            if (link != null && !link.matches(level, connection)) {
                link.destroy();
                active.remove(connection.id());
                link = null;
            }
            if (link == null) {
                BridgeLink created = BridgeLink.tryCreate(level, connection);
                if (created != null) {
                    active.put(connection.id(), created);
                }
            }
        }

        active.entrySet().removeIf(entry -> {
            if (desired.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().destroy();
            return true;
        });

        if (active.isEmpty()) {
            LINKS.remove(dimension);
        }
    }

    private static List<LeadConnection> currentAeConnections(ServerLevel level) {
        java.util.ArrayList<LeadConnection> aeConnections = new java.util.ArrayList<>();
        for (LeadConnection connection : com.zhongbai233.super_lead.lead.SuperLeadSavedData.get(level).connections()) {
            if (connection.kind() == LeadKind.AE_NETWORK) {
                aeConnections.add(connection);
            }
        }
        return aeConnections;
    }

    private static IGridNode exposedNode(ServerLevel level, LeadAnchor anchor) {
        if (!level.isLoaded(anchor.pos())) {
            return null;
        }
        return GridHelper.getExposedNode(level, anchor.pos(), anchor.face());
    }

    private static IGridNode terminalNode(ResourceKey<Level> dimension, UUID connectionId) {
        Map<UUID, BridgeLink> active = LINKS.get(dimension);
        if (active == null) {
            return null;
        }
        BridgeLink link = active.get(connectionId);
        return link == null ? null : link.terminalNode();
    }

    private static MEStorage storageFor(Level level, UUID connectionId) {
        if (level == null || level.isClientSide()) {
            return null;
        }
        IGridNode node = terminalNode(level.dimension(), connectionId);
        if (node == null) {
            return null;
        }
        try {
            return node.getGrid().getStorageService().getInventory();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private enum TerminalKind {
        TERMINAL(MEStorageMenu.TYPE),
        CRAFTING(CraftingTermMenu.TYPE),
        PATTERN_ENCODING(PatternEncodingTermMenu.TYPE),
        PATTERN_ACCESS(PatternAccessTermMenu.TYPE);

        private final MenuType<?> menuType;

        TerminalKind(MenuType<?> menuType) {
            this.menuType = menuType;
        }

        MenuType<?> menuType() {
            return menuType;
        }
    }

    private static final class BridgeLink {
        private final UUID connectionId;
        private final LeadAnchor from;
        private final LeadAnchor to;
        private final boolean dense;
        private final IGridNode externalFrom;
        private final IGridNode externalTo;
        private final IManagedGridNode bridgeFrom;
        private final IManagedGridNode bridgeTo;
        private final IGridConnection externalFromConnection;
        private final IGridConnection bridgeConnection;
        private final IGridConnection externalToConnection;

        private BridgeLink(UUID connectionId, LeadAnchor from, LeadAnchor to, boolean dense,
                IGridNode externalFrom, IGridNode externalTo,
                IManagedGridNode bridgeFrom, IManagedGridNode bridgeTo,
                IGridConnection externalFromConnection, IGridConnection bridgeConnection,
                IGridConnection externalToConnection) {
            this.connectionId = connectionId;
            this.from = from;
            this.to = to;
            this.dense = dense;
            this.externalFrom = externalFrom;
            this.externalTo = externalTo;
            this.bridgeFrom = bridgeFrom;
            this.bridgeTo = bridgeTo;
            this.externalFromConnection = externalFromConnection;
            this.bridgeConnection = bridgeConnection;
            this.externalToConnection = externalToConnection;
        }

        static BridgeLink tryCreate(ServerLevel level, LeadConnection connection) {
            IGridNode externalFrom = exposedNode(level, connection.from());
            IGridNode externalTo = exposedNode(level, connection.to());
            if (externalFrom == null && externalTo == null) {
                return null;
            }

            boolean dense = connection.tier() > 0;
            IManagedGridNode bridgeFrom = null;
            IManagedGridNode bridgeTo = null;
            IGridConnection externalFromConnection = null;
            IGridConnection bridgeConnection = null;
            IGridConnection externalToConnection = null;
            try {
                bridgeFrom = createBridgeNode(level, connection, connection.from(), "from", dense);
                bridgeTo = createBridgeNode(level, connection, connection.to(), "to", dense);
                IGridNode fromNode = bridgeFrom.getNode();
                IGridNode toNode = bridgeTo.getNode();
                if (fromNode == null || toNode == null) {
                    destroyQuietly(bridgeTo);
                    destroyQuietly(bridgeFrom);
                    return null;
                }

                if (externalFrom != null) {
                    externalFromConnection = GridHelper.createConnection(externalFrom, fromNode);
                }
                bridgeConnection = GridHelper.createConnection(fromNode, toNode);
                if (externalTo != null && externalTo != externalFrom) {
                    externalToConnection = GridHelper.createConnection(toNode, externalTo);
                }
                return new BridgeLink(connection.id(), connection.from(), connection.to(), dense,
                        externalFrom, externalTo, bridgeFrom, bridgeTo,
                        externalFromConnection, bridgeConnection, externalToConnection);
            } catch (RuntimeException e) {
                destroyQuietly(externalToConnection);
                destroyQuietly(bridgeConnection);
                destroyQuietly(externalFromConnection);
                destroyQuietly(bridgeTo);
                destroyQuietly(bridgeFrom);
                return null;
            }
        }

        boolean matches(ServerLevel level, LeadConnection connection) {
            if (!connection.id().equals(connectionId)
                    || !connection.from().equals(from)
                    || !connection.to().equals(to)
                    || (connection.tier() > 0) != dense
                    || bridgeFrom.getNode() == null
                    || bridgeTo.getNode() == null) {
                return false;
            }
            return exposedNode(level, connection.from()) == externalFrom
                    && exposedNode(level, connection.to()) == externalTo;
        }

        void destroy() {
            destroyQuietly(externalToConnection);
            destroyQuietly(bridgeConnection);
            destroyQuietly(externalFromConnection);
            destroyQuietly(bridgeTo);
            destroyQuietly(bridgeFrom);
        }

        IGridNode terminalNode() {
            IGridNode node = bridgeFrom.getNode();
            return node != null ? node : bridgeTo.getNode();
        }

    }

    private record RopeAeTerminalLocator(UUID connectionId, UUID attachmentId)
            implements appeng.menu.locator.ItemMenuHostLocator {
        @Override
        public <T> T locate(Player player, Class<T> hostInterface) {
            ItemStack stack = locateItem(player);
            TerminalKind terminal = terminalKind(stack);
            if (terminal == null) {
                return null;
            }
            Object host = switch (terminal) {
                case TERMINAL -> new RopeTerminalHost(stack.getItem(), player, this);
                case CRAFTING -> new RopeCraftingTerminalHost(stack.getItem(), player, this);
                case PATTERN_ENCODING -> new RopePatternEncodingTerminalHost(stack.getItem(), player, this);
                case PATTERN_ACCESS -> new RopePatternAccessTerminalHost(stack.getItem(), player, this);
            };
            return hostInterface.isInstance(host) ? hostInterface.cast(host) : null;
        }

        @Override
        public BlockHitResult hitResult() {
            return null;
        }

        @Override
        public ItemStack locateItem(Player player) {
            if (player == null || player.level() == null) {
                return ItemStack.EMPTY;
            }
            java.util.Optional<LeadConnection> opt = SuperLeadNetwork.findConnectionById(player.level(), connectionId);
            if (opt.isEmpty() || opt.get().kind() != LeadKind.AE_NETWORK) {
                return ItemStack.EMPTY;
            }
            for (RopeAttachment attachment : opt.get().attachments()) {
                if (attachment.id().equals(attachmentId)) {
                    return attachment.stack();
                }
            }
            return ItemStack.EMPTY;
        }

        void writeToPacket(FriendlyByteBuf buffer) {
            buffer.writeUUID(connectionId);
            buffer.writeUUID(attachmentId);
        }

        static RopeAeTerminalLocator readFromPacket(FriendlyByteBuf buffer) {
            return new RopeAeTerminalLocator(buffer.readUUID(), buffer.readUUID());
        }
    }

    private abstract static class RopeMenuHost extends ItemMenuHost<Item> implements IActionHost {
        protected final UUID connectionId;
        protected final UUID attachmentId;
        private final IConfigManager configManager;

        private RopeMenuHost(Item item, Player player, RopeAeTerminalLocator locator, boolean patternAccessSettings) {
            super(item, player, locator);
            this.connectionId = locator.connectionId();
            this.attachmentId = locator.attachmentId();
            var builder = IConfigManager.builder(() -> {
            })
                    .registerSetting(Settings.SORT_BY, SortOrder.NAME)
                    .registerSetting(Settings.VIEW_MODE, ViewItems.ALL)
                    .registerSetting(Settings.SORT_DIRECTION, SortDir.ASCENDING);
            if (patternAccessSettings) {
                builder.registerSetting(Settings.TERMINAL_SHOW_PATTERN_PROVIDERS, ShowPatternProviders.VISIBLE);
            }
            this.configManager = builder.build();
        }

        @Override
        public IGridNode getActionableNode() {
            return terminalNode(getPlayer().level().dimension(), connectionId);
        }

        public ILinkStatus getLinkStatus() {
            IGridNode node = getActionableNode();
            if (node == null) {
                return ILinkStatus.ofDisconnected(Component.literal("Rope terminal is not connected to an AE network"));
            }
            if (node.isOnline()) {
                return ILinkStatus.ofConnected();
            }
            if (!node.isPowered()) {
                return ILinkStatus.ofDisconnected(Component.literal("AE network is out of power"));
            }
            if (!node.meetsChannelRequirements()) {
                return ILinkStatus.ofDisconnected(Component.literal("AE network has no free channel"));
            }
            return ILinkStatus.ofDisconnected();
        }

        public IConfigManager getConfigManager() {
            return configManager;
        }

        public void returnToMainMenu(Player player, appeng.menu.ISubMenu subMenu) {
            appeng.menu.locator.ItemMenuHostLocator locator = getLocator();
            TerminalKind terminal = terminalKind(getItemStack());
            if (locator != null && terminal != null) {
                appeng.menu.MenuOpener.open(terminal.menuType(), player, locator, true);
            }
        }

        public ItemStack getMainMenuIcon() {
            return getItemStack().copyWithCount(1);
        }

        protected void persistAttachmentStack(java.util.function.UnaryOperator<ItemStack> updater) {
            if (!(getPlayer().level() instanceof ServerLevel serverLevel)) {
                return;
            }
            SuperLeadNetwork.updateAttachmentStack(serverLevel, connectionId, attachmentId, updater, false);
        }
    }

    private static class RopeTerminalHost extends RopeMenuHost implements ITerminalHost {
        private final MEStorage storage;

        private RopeTerminalHost(Item item, Player player, RopeAeTerminalLocator locator) {
            super(item, player, locator, false);
            this.storage = new SupplierStorage(() -> storageFor(getPlayer().level(), connectionId));
        }

        @Override
        public MEStorage getInventory() {
            return storage;
        }
    }

    private static final class RopeCraftingTerminalHost extends RopeTerminalHost
            implements ISegmentedInventory, InternalInventoryHost {
        private final AppEngInternalInventory craftingGrid;

        private RopeCraftingTerminalHost(Item item, Player player, RopeAeTerminalLocator locator) {
            super(item, player, locator);
            this.craftingGrid = new AppEngInternalInventory(this, 9);
            this.craftingGrid.fromItemContainerContents(
                    getItemStack().getOrDefault(AEComponents.CRAFTING_INV, ItemContainerContents.EMPTY));
        }

        @Override
        public InternalInventory getSubInventory(net.minecraft.resources.Identifier id) {
            if (id.equals(CraftingTerminalPart.INV_CRAFTING)) {
                return craftingGrid;
            }
            return null;
        }

        @Override
        public void saveChangedInventory(AppEngInternalInventory inv) {
            persistAttachmentStack(stack -> {
                ItemStack updated = stack.copyWithCount(1);
                updated.set(AEComponents.CRAFTING_INV, craftingGrid.toItemContainerContents());
                return updated;
            });
        }
    }

    private static final class RopePatternEncodingTerminalHost extends RopeTerminalHost
            implements InternalInventoryHost, IPatternTerminalLogicHost, IPatternTerminalMenuHost {
        private final PatternEncodingLogic patternEncodingLogic;

        private RopePatternEncodingTerminalHost(Item item, Player player, RopeAeTerminalLocator locator) {
            super(item, player, locator);
            this.patternEncodingLogic = new PatternEncodingLogic(this);
            readPatternEncodingState();
        }

        @Override
        public PatternEncodingLogic getLogic() {
            return patternEncodingLogic;
        }

        @Override
        public Level getLevel() {
            return getPlayer().level();
        }

        @Override
        public void markForSave() {
            savePatternEncodingState();
        }

        @Override
        public void saveChangedInventory(AppEngInternalInventory inv) {
            savePatternEncodingState();
        }

        private void readPatternEncodingState() {
            CompoundTag root = getItemStack().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            if (!root.contains(PATTERN_ENCODING_DATA_KEY)) {
                return;
            }
            CompoundTag data = root.getCompoundOrEmpty(PATTERN_ENCODING_DATA_KEY);
            patternEncodingLogic.readFromNBT(TagValueInput.create(ProblemReporter.DISCARDING,
                    getPlayer().level().registryAccess(), data));
        }

        private void savePatternEncodingState() {
            if (!(getPlayer().level() instanceof ServerLevel)) {
                return;
            }
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                    getPlayer().level().registryAccess());
            patternEncodingLogic.writeToNBT(output);
            CompoundTag encoded = output.buildResult();
            persistAttachmentStack(stack -> {
                ItemStack updated = stack.copyWithCount(1);
                CompoundTag root = updated.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                root.put(PATTERN_ENCODING_DATA_KEY, encoded);
                updated.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
                return updated;
            });
        }
    }

    private static final class RopePatternAccessTerminalHost extends RopeMenuHost
            implements IPatternAccessTermMenuHost {
        private RopePatternAccessTerminalHost(Item item, Player player, RopeAeTerminalLocator locator) {
            super(item, player, locator, true);
        }

        @Override
        public IGridNode getGridNode() {
            return getActionableNode();
        }
    }

    private static final class PatternEncodingDropHost implements IPatternTerminalLogicHost {
        private final Level level;
        private final PatternEncodingLogic logic;

        private PatternEncodingDropHost(Level level) {
            this.level = level;
            this.logic = new PatternEncodingLogic(this);
        }

        @Override
        public PatternEncodingLogic getLogic() {
            return logic;
        }

        @Override
        public Level getLevel() {
            return level;
        }

        @Override
        public void markForSave() {
        }
    }

    private static IManagedGridNode createBridgeNode(ServerLevel level, LeadConnection connection,
            LeadAnchor anchor, String side, boolean dense) {
        IManagedGridNode node = GridHelper.createManagedNode(
                new BridgeNodeOwner(connection.id(), anchor.pos(), anchor.face(), side, dense), NODE_LISTENER)
                .setIdlePowerUsage(0.0D)
                .setVisualRepresentation(AE2LeadMaterials.fluixBlockIcon());
        if (dense) {
            node.setFlags(GridFlags.DENSE_CAPACITY);
        } else {
            node.setFlags(GridFlags.PREFERRED);
        }
        node.create(level, anchor.pos());
        return node;
    }

    private static void destroyQuietly(IGridConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.destroy();
        } catch (RuntimeException ignored) {
        }
    }

    private static void destroyQuietly(IManagedGridNode node) {
        if (node == null) {
            return;
        }
        try {
            node.destroy();
        } catch (RuntimeException ignored) {
        }
    }

    private record BridgeNodeOwner(UUID connectionId, BlockPos pos, Direction face, String side, boolean dense) {
    }
}