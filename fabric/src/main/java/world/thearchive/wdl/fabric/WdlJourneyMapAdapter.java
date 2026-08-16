// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.ModContainer;
import org.jspecify.annotations.Nullable;

/**
 * Fabric journeymap-entrypoint adapter that yields the JourneyMap plugin matching the installed generation. Two
 * JourneyMap generations serve this Minecraft, and both read the single journeymap entrypoint eagerly through
 * getEntrypoints, which instantiates every listed entry and aborts the whole scan, JourneyMap's own plugins included,
 * if one throws. No static entrypoint form survives that: a bare class link-loads the wrong-generation plugin's absent
 * interface, a method reference demands a single-method interface, and a field reference is checked against its
 * declared type. A custom language adapter is the one path the loader routes per entry with the requested plugin
 * interface as the type argument, so this constructs only the matching-generation plugin and returns null, never
 * throwing, for anything else. Forge discovers the plugins by annotation and needs none of this.
 */
public final class WdlJourneyMapAdapter implements LanguageAdapter {
    private static final String V2_PLUGIN = "world.thearchive.wdl.compat.journeymap.v2.WdlJourneyMapPlugin";
    private static final String LEGACY_PLUGIN = "world.thearchive.wdl.compat.journeymap.WdlJourneyMapPlugin";

    @Override
    public <T> @Nullable T create(ModContainer mod, String value, Class<T> type) {
        String pluginClass = pluginFor(type.getName());
        if (pluginClass == null) {
            return null;
        }
        try {
            ClassLoader loader = type.getClassLoader();
            Class<?> resolved = Class.forName(pluginClass, true, loader != null ? loader : getClass().getClassLoader());
            if (!type.isAssignableFrom(resolved)) {
                return null;
            }
            return type.cast(resolved.getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException | LinkageError e) {
            // Returning null rather than throwing is deliberate: a throw aborts JourneyMap's entire eager
            // entrypoint scan and drops every plugin, so a wdl-side failure stays a missing wdl overlay.
            return null;
        }
    }

    private static @Nullable String pluginFor(String pluginInterface) {
        return switch (pluginInterface) {
            case "journeymap.api.v2.common.IJourneyMapPlugin" -> V2_PLUGIN;
            case "journeymap.client.api.IClientPlugin" -> LEGACY_PLUGIN;
            default -> null;
        };
    }
}
