package com.zhongbai233.super_lead.lead.cargo;

import com.zhongbai233.super_lead.lead.SuperLeadItems;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class CargoManifestMenu extends AbstractContainerMenu {
    public static final int FILTER_SLOT_COUNT = CargoManifestData.FILTER_SLOT_COUNT;
    public static final int FILTER_X = 12;
    public static final int FILTER_Y = 36;
    public static final int PLAYER_INV_X = 12;
    public static final int PLAYER_INV_Y = 106;
    private static final int DATA_WHITELIST = 0;
    private static final int DATA_MATCH_NBT = 1;

    private final Inventory playerInventory;
    private final InteractionHand hand;
    private final boolean advanced;
    private final SimpleContainer filterContainer;
    private final List<String> tags = new ArrayList<>();
    private boolean whitelist;
    private boolean matchNbt;

    private final ContainerData optionData = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_WHITELIST -> whitelist ? 1 : 0;
                case DATA_MATCH_NBT -> matchNbt ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            if (index == DATA_WHITELIST) {
                whitelist = value != 0;
            } else if (index == DATA_MATCH_NBT) {
                matchNbt = value != 0;
            }
        }

        @Override
        public int getCount() {
            return 2;
        }
    };

    public CargoManifestMenu(int id, Inventory playerInventory, InteractionHand hand, boolean advanced) {
        this(id, playerInventory, hand, advanced,
                CargoManifestData.whitelist(playerInventory.player.getItemInHand(hand)),
                CargoManifestData.matchNbt(playerInventory.player.getItemInHand(hand)),
                CargoManifestData.tags(playerInventory.player.getItemInHand(hand)));
        NonNullList<ItemStack> items = CargoManifestData.readItems(playerInventory.player.getItemInHand(hand));
        for (int i = 0; i < FILTER_SLOT_COUNT; i++) {
            filterContainer.setItem(i, items.get(i));
        }
    }

    private CargoManifestMenu(int id, Inventory playerInventory, InteractionHand hand, boolean advanced,
            boolean whitelist, boolean matchNbt, List<String> tags) {
        super(SuperLeadMenus.CARGO_MANIFEST.get(), id);
        this.playerInventory = playerInventory;
        this.hand = hand;
        this.advanced = advanced;
        this.whitelist = whitelist;
        this.matchNbt = matchNbt;
        this.tags.addAll(tags);
        this.filterContainer = new SimpleContainer(FILTER_SLOT_COUNT) {
            @Override
            public void setChanged() {
                super.setChanged();
                saveToStack();
            }
        };

        addManifestSlots();
    addStandardInventorySlots(playerInventory, PLAYER_INV_X, PLAYER_INV_Y);
        addDataSlots(optionData);
    }

    public static CargoManifestMenu fromNetwork(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        InteractionHand hand = buffer.readBoolean() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        boolean advanced = buffer.readBoolean();
        boolean whitelist = buffer.readBoolean();
        boolean matchNbt = buffer.readBoolean();
        int tagCount = Math.min(buffer.readVarInt(), CargoManifestData.TAG_LIMIT);
        List<String> tags = new ArrayList<>(tagCount);
        for (int i = 0; i < tagCount; i++) {
            String normalized = CargoManifestData.normalizeTagInput(buffer.readUtf(CargoManifestData.TAG_MAX_LENGTH));
            if (!normalized.isEmpty() && !tags.contains(normalized)) {
                tags.add(normalized);
            }
        }
        return new CargoManifestMenu(id, inventory, hand, advanced, whitelist, matchNbt, tags);
    }

    public static void writeOpeningData(RegistryFriendlyByteBuf buffer, InteractionHand hand, boolean advanced,
            ItemStack stack) {
        buffer.writeBoolean(hand == InteractionHand.OFF_HAND);
        buffer.writeBoolean(advanced);
        buffer.writeBoolean(CargoManifestData.whitelist(stack));
        buffer.writeBoolean(CargoManifestData.matchNbt(stack));
        List<String> tags = CargoManifestData.tags(stack);
        buffer.writeVarInt(tags.size());
        for (String tag : tags) {
            buffer.writeUtf(tag, CargoManifestData.TAG_MAX_LENGTH);
        }
    }

    public boolean advanced() {
        return advanced;
    }

    public boolean whitelist() {
        return whitelist;
    }

    public boolean matchNbt() {
        return matchNbt;
    }

    public List<String> tags() {
        return List.copyOf(tags);
    }

    public ItemStack sanitizeGhostSample(ItemStack stack) {
        return stack.isEmpty() || CargoManifestData.isManifestStack(stack)
                ? ItemStack.EMPTY
                : stack.copyWithCount(1);
    }

    public void setGhostSlotFromExternal(int slotId, ItemStack stack) {
        if (slotId < 0 || slotId >= FILTER_SLOT_COUNT) {
            return;
        }
        setGhostSlot(slotId, stack);
    }

    public void setOptions(boolean whitelist, boolean matchNbt) {
        this.whitelist = whitelist;
        this.matchNbt = matchNbt;
        saveToStack();
        broadcastChanges();
    }

    public void addTag(String rawTag) {
        if (!advanced) {
            return;
        }
        String tag = CargoManifestData.normalizeTagInput(rawTag);
        if (tag.isEmpty() || tags.contains(tag) || tags.size() >= CargoManifestData.TAG_LIMIT) {
            return;
        }
        tags.add(tag);
        saveToStack();
        broadcastChanges();
    }

    public void removeTag(String rawTag) {
        if (!advanced) {
            return;
        }
        String tag = CargoManifestData.normalizeTagInput(rawTag);
        if (tag.isEmpty()) {
            return;
        }
        if (tags.remove(tag)) {
            saveToStack();
            broadcastChanges();
        }
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player player) {
        if (slotId >= 0 && slotId < FILTER_SLOT_COUNT) {
            handleGhostSlotClick(slotId, button, input, player);
            return;
        }
        super.clicked(slotId, button, input, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        if (index < FILTER_SLOT_COUNT) {
            setGhostSlot(index, ItemStack.EMPTY);
            return ItemStack.EMPTY;
        }
        int target = firstEmptyFilterSlot();
        if (target >= 0) {
            setGhostSlot(target, stack);
            return stack.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        ItemStack stack = player.getItemInHand(hand);
        if (advanced) {
            return stack.is(SuperLeadItems.ADVANCED_CARGO_MANIFEST.asItem());
        }
        return stack.is(SuperLeadItems.BASIC_CARGO_MANIFEST.asItem());
    }

    @Override
    public void removed(Player player) {
        saveToStack();
        super.removed(player);
    }

    private void addManifestSlots() {
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 9; col++) {
                int index = row * 9 + col;
                addSlot(new GhostSlot(filterContainer, index, FILTER_X + col * 18, FILTER_Y + row * 18));
            }
        }
    }

    private void handleGhostSlotClick(int slotId, int button, ContainerInput input, Player player) {
        if (input == ContainerInput.PICKUP) {
            ItemStack carried = getCarried();
            if (carried.isEmpty() || button == 1) {
                setGhostSlot(slotId, ItemStack.EMPTY);
            } else {
                setGhostSlot(slotId, carried);
            }
        } else if (input == ContainerInput.SWAP) {
            if (button >= 0 && button < Inventory.getSelectionSize()) {
                setGhostSlot(slotId, player.getInventory().getItem(button));
            }
        } else if (input == ContainerInput.QUICK_MOVE || input == ContainerInput.THROW) {
            setGhostSlot(slotId, ItemStack.EMPTY);
        }
    }

    private void setGhostSlot(int slotId, ItemStack stack) {
        ItemStack sample = sanitizeGhostSample(stack);
        filterContainer.setItem(slotId, sample);
        broadcastChanges();
    }

    public int firstEmptyFilterSlot() {
        for (int i = 0; i < FILTER_SLOT_COUNT; i++) {
            if (filterContainer.getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private void saveToStack() {
        if (playerInventory.player.level().isClientSide()) {
            return;
        }
        ItemStack stack = playerInventory.player.getItemInHand(hand);
        if (!stillValid(playerInventory.player)) {
            return;
        }
        CargoManifestData.writeItems(stack, filterContainer);
        CargoManifestData.setOptions(stack, whitelist, matchNbt);
        if (advanced) {
            CargoManifestData.setTags(stack, tags);
        }
        playerInventory.setChanged();
    }

    private static final class GhostSlot extends Slot {
        private GhostSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return 1;
        }

        @Override
        public boolean isFake() {
            return true;
        }
    }
}