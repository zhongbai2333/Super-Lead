package com.zhongbai233.super_lead.lead.cargo;

import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.SuperLeadItems;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * Data-component accessors and validation for cargo manifest filter items/tags.
 */
public final class CargoManifestData {
    public static final int FILTER_SLOT_COUNT = 18;
    public static final int TAG_LIMIT = 32;
    public static final int TAG_MAX_LENGTH = 128;

    private static final String ROOT_KEY = Super_lead.MODID + ".cargo_manifest";
    private static final String WHITELIST_KEY = "Whitelist";
    private static final String MATCH_NBT_KEY = "MatchNbt";
    private static final String TAGS_KEY = "Tags";

    private CargoManifestData() {
    }

    public static boolean isManifestStack(ItemStack stack) {
        return stack.is(SuperLeadItems.BASIC_CARGO_MANIFEST.asItem())
                || stack.is(SuperLeadItems.ADVANCED_CARGO_MANIFEST.asItem());
    }

    public static boolean isAdvanced(ItemStack stack) {
        return stack.is(SuperLeadItems.ADVANCED_CARGO_MANIFEST.asItem());
    }

    public static NonNullList<ItemStack> readItems(ItemStack stack) {
        NonNullList<ItemStack> items = NonNullList.withSize(FILTER_SLOT_COUNT, ItemStack.EMPTY);
        ItemContainerContents contents = stack.getOrDefault(SuperLeadDataComponents.CARGO_MANIFEST_ITEMS.get(),
                ItemContainerContents.EMPTY);
        contents.copyInto(items);
        sanitizeItems(items);
        return items;
    }

    public static void writeItems(ItemStack stack, Container container) {
        NonNullList<ItemStack> items = NonNullList.withSize(FILTER_SLOT_COUNT, ItemStack.EMPTY);
        int count = Math.min(FILTER_SLOT_COUNT, container.getContainerSize());
        for (int i = 0; i < count; i++) {
            ItemStack item = container.getItem(i);
            items.set(i, sanitizeItem(item));
        }
        stack.set(SuperLeadDataComponents.CARGO_MANIFEST_ITEMS.get(), ItemContainerContents.fromItems(items));
    }

    public static boolean whitelist(ItemStack stack) {
        return root(stack).getBooleanOr(WHITELIST_KEY, true);
    }

    public static boolean matchNbt(ItemStack stack) {
        return root(stack).getBooleanOr(MATCH_NBT_KEY, false);
    }

    public static void setOptions(ItemStack stack, boolean whitelist, boolean matchNbt) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag root = tag.getCompoundOrEmpty(ROOT_KEY);
            root.putBoolean(WHITELIST_KEY, whitelist);
            root.putBoolean(MATCH_NBT_KEY, matchNbt);
            tag.put(ROOT_KEY, root);
        });
    }

    public static List<String> tags(ItemStack stack) {
        List<String> out = new ArrayList<>();
        ListTag list = root(stack).getListOrEmpty(TAGS_KEY);
        for (int i = 0; i < list.size() && out.size() < TAG_LIMIT; i++) {
            String normalized = normalizeTagInput(list.getStringOr(i, ""));
            if (!normalized.isEmpty() && !out.contains(normalized)) {
                out.add(normalized);
            }
        }
        return out;
    }

    public static void setTags(ItemStack stack, List<String> tags) {
        Set<String> unique = new LinkedHashSet<>();
        for (String tag : tags) {
            String normalized = normalizeTagInput(tag);
            if (!normalized.isEmpty()) {
                unique.add(normalized);
            }
            if (unique.size() >= TAG_LIMIT) {
                break;
            }
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, data -> {
            CompoundTag root = data.getCompoundOrEmpty(ROOT_KEY);
            ListTag list = new ListTag();
            for (String tag : unique) {
                list.add(StringTag.valueOf(tag));
            }
            root.put(TAGS_KEY, list);
            data.put(ROOT_KEY, root);
        });
    }

    public static int nonEmptyItemCount(ItemStack stack) {
        int count = 0;
        for (ItemStack item : readItems(stack)) {
            if (!item.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    public static boolean matches(ItemStack manifest, ItemStack candidate) {
        if (candidate.isEmpty()) {
            return false;
        }
        boolean matchNbt = matchNbt(manifest);
        boolean matched = false;
        for (ItemStack filter : readItems(manifest)) {
            if (filter.isEmpty()) {
                continue;
            }
            if (matchNbt ? ItemStack.isSameItemSameComponents(filter, candidate)
                    : ItemStack.isSameItem(filter, candidate)) {
                matched = true;
                break;
            }
        }
        if (!matched && isAdvanced(manifest)) {
            for (String tag : tags(manifest)) {
                Identifier id = Identifier.tryParse(tag);
                if (id != null && candidate.is(TagKey.create(Registries.ITEM, id))) {
                    matched = true;
                    break;
                }
            }
        }
        return whitelist(manifest) == matched;
    }

    public static String normalizeTagInput(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.startsWith("#")) {
            value = value.substring(1).trim();
        }
        if (value.isEmpty() || value.length() > TAG_MAX_LENGTH) {
            return "";
        }
        Identifier id = Identifier.tryParse(value);
        return id == null ? "" : id.toString();
    }

    private static CompoundTag root(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return new CompoundTag();
        }
        return data.copyTag().getCompoundOrEmpty(ROOT_KEY);
    }

    private static void sanitizeItems(NonNullList<ItemStack> items) {
        for (int i = 0; i < items.size(); i++) {
            items.set(i, sanitizeItem(items.get(i)));
        }
    }

    private static ItemStack sanitizeItem(ItemStack stack) {
        if (stack.isEmpty() || isManifestStack(stack)) {
            return ItemStack.EMPTY;
        }
        return stack.copyWithCount(1);
    }
}