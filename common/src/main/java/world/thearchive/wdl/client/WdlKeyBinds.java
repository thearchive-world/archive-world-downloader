// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;

/**
 * The mod's shared keybind category (the "Archive World Downloader" controls heading), registered once and reused by
 * every wdl keybind on both loaders. One shared Category instance is required rather than merely a shared id: the
 * vanilla controls list groups consecutive mappings by category reference identity, so two distinct records carrying
 * the same id would each open their own duplicate heading. The heading label resolves the key.category.wdl.downloader
 * translation key, which this id derives.
 */
public final class WdlKeyBinds {
    // Fabric has no category-registration event, so the vanilla register is the only cross-loader path and this
    // one shared instance uses it uniformly. NeoForge deprecates it in favor of RegisterKeyMappingsEvent's
    // registerCategory, but the vanilla call still fills the sort order its controls screen reads, so the
    // NeoForge-only deprecation is suppressed here.
    @SuppressWarnings("deprecation")
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category
            .register(ResourceLocation.fromNamespaceAndPath("wdl", "downloader"));

    private WdlKeyBinds() {}
}
