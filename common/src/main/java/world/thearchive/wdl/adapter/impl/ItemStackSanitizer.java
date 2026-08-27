// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Repairs a client-held ItemStack so Minecraft's disk codec accepts it. A component the network codec carries can be
 * one the disk codec rejects, which costs the entity or the item that carries it. Vanilla's own codec decides which
 * component is at fault.
 */
public final class ItemStackSanitizer {
    private static final int MAX_CONTAINER_SLOTS = 256;

    private ItemStackSanitizer() {}

    /** A save-clean stack: the same instance when already savable, else a repaired copy, else {@code EMPTY}. */
    public static ItemStack sanitizeForSave(ItemStack stack, RegistryOps<Tag> ops) {
        if (stack.isEmpty() || savable(stack, ops)) {
            return stack;
        }
        ItemStack copy = stack.copy();
        sanitizeInPlace(copy, ops);
        return savable(copy, ops) ? copy : ItemStack.EMPTY;
    }

    /**
     * Best-effort repair of {@code stack} in place: fixes whichever components the codec rejects, with no re-verify and
     * no floor to {@code EMPTY} if a repair leaves the stack unsavable. The caller must own {@code stack}.
     */
    public static void sanitizeInPlace(ItemStack stack, RegistryOps<Tag> ops) {
        // ItemStack.EMPTY is a shared singleton, so nothing below may write to it.
        if (stack.isEmpty() || savable(stack, ops)) {
            return;
        }
        if (stack.getCount() > Item.ABSOLUTE_MAX_STACK_SIZE) {
            // count is a top-level codec field, not a component, so the loop below cannot reach it
            stack.setCount(Item.ABSOLUTE_MAX_STACK_SIZE);
        }
        recurseContainer(stack, ops);
        List<DataComponentType<?>> failing = new ArrayList<>();
        for (TypedDataComponent<?> component : stack.getComponents()) {
            if (component.type().isTransient()) {
                continue; // a transient component has no disk codec, so an error here is a false positive
            }
            if (component.encodeValue(ops).error().isPresent()) {
                failing.add(component.type());
            }
        }
        for (DataComponentType<?> type : failing) {
            if (type == DataComponents.ENCHANTMENTS) {
                repairEnchantments(stack, DataComponents.ENCHANTMENTS);
            } else if (type == DataComponents.STORED_ENCHANTMENTS) {
                repairEnchantments(stack, DataComponents.STORED_ENCHANTMENTS);
            } else {
                dropOverride(stack, type);
            }
        }
    }

    private static boolean savable(ItemStack stack, RegistryOps<Tag> ops) {
        return ItemStack.CODEC.encodeStart(ops, stack).error().isEmpty();
    }

    private static void repairEnchantments(ItemStack stack, DataComponentType<ItemEnchantments> type) {
        ItemEnchantments current = stack.get(type);
        if (current == null) {
            return;
        }
        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(current);
        mutable.removeIf(holder -> current.getLevel(holder) <= 0);
        ItemEnchantments repaired = mutable.toImmutable();
        // Present and empty is not the same item to vanilla as absent, so keep whichever shape the prototype declares.
        if (repaired.isEmpty() && stack.getItem().components().get(type) == null) {
            stack.remove(type);
        } else {
            stack.set(type, repaired);
        }
    }

    private static <T> void dropOverride(ItemStack stack, DataComponentType<T> type) {
        // Not a plain remove(): vanilla reads an absent component differently from one sitting at its prototype
        // default, so a sword whose DAMAGE is removed stops being damageable at all. MAX_STACK_SIZE is carved out
        // because a stackable default beside a damageable override is a stack vanilla refuses to build.
        T prototypeDefault = stack.getItem().components().get(type);
        if (prototypeDefault == null || type == DataComponents.MAX_STACK_SIZE) {
            stack.remove(type);
        } else {
            stack.set(type, prototypeDefault);
        }
    }

    private static void recurseContainer(ItemStack stack, RegistryOps<Tag> ops) {
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) {
            return;
        }
        NonNullList<ItemStack> items = NonNullList.withSize(MAX_CONTAINER_SLOTS, ItemStack.EMPTY);
        contents.copyInto(items);
        boolean changed = false;
        for (int slot = 0; slot < items.size(); slot++) {
            ItemStack nested = items.get(slot);
            if (!nested.isEmpty()) {
                ItemStack clean = sanitizeForSave(nested, ops);
                if (clean != nested) {
                    items.set(slot, clean);
                    changed = true;
                }
            }
        }
        if (changed) {
            stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        }
    }
}
