// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

final class ItemTreeWalk {
    private static final String COMPONENTS = "components";
    private static final String CONTAINER = "minecraft:container";
    private static final String BUNDLE_CONTENTS = "minecraft:bundle_contents";
    private static final String SULFUR_CUBE_CONTENT = "minecraft:sulfur_cube_content";
    private static final String ITEM = "item";

    private ItemTreeWalk() {}

    static void walkList(ListTag items, Consumer<CompoundTag> onComponents) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof CompoundTag item) {
                walkItem(item, onComponents);
            }
        }
    }

    static void walkItem(CompoundTag item, Consumer<CompoundTag> onComponents) {
        if (!(item.get(COMPONENTS) instanceof CompoundTag components)) {
            return;
        }
        onComponents.accept(components);
        if (components.get(CONTAINER) instanceof ListTag container) {
            for (int i = 0; i < container.size(); i++) {
                if (container.get(i) instanceof CompoundTag slot && slot.get(ITEM) instanceof CompoundTag nested) {
                    walkItem(nested, onComponents);
                }
            }
        }
        if (components.get(BUNDLE_CONTENTS) instanceof ListTag bundle) {
            walkList(bundle, onComponents);
        }
        if (components.get(SULFUR_CUBE_CONTENT) instanceof CompoundTag sulfur) {
            walkItem(sulfur, onComponents);
        }
    }
}
