// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

/**
 * The mod's shared keybind category (the "Archive World Downloader" controls heading), passed to every wdl
 * {@code KeyMapping} on both loaders so its mappings group under one heading. The value is the
 * key.categories.wdl.downloader translation key the vanilla controls screen resolves for the label.
 */
public final class WdlKeyBinds {
    public static final String CATEGORY = "key.categories.wdl.downloader";

    private WdlKeyBinds() {}
}
