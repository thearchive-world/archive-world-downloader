// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.neoforge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ServiceLoader;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.adapter.VersionAdapter;
import world.thearchive.wdl.adapter.impl.VersionAdapterImpl;

/**
 * Per-loader ServiceLoader-resolution + entrypoint smoke test.
 *
 * <p>Runs on a production-faithful FML load: MDG NeoForge-mode's {@code unitTest} block boots FMLLoader and loads this
 * module as a mod (the same module setup the client uses), so the {@code net.neoforged.*} bridge classes load and the
 * {@code META-INF/services} provider metadata is present. This exercises the loader-agnostic core, the source-merge,
 * and ServiceLoader against a second loader.
 *
 * <p>The resolution test uses the exact classloader {@code Wdl.initialize} uses ({@code Wdl.class.getClassLoader()}),
 * asserting the discovered SPI binds to its implementation:
 * <ul>
 * <li>{@link VersionAdapter} &rarr; {@link VersionAdapterImpl} (provided by :common, source-merged in)</li>
 * </ul>
 * {@code WdlNeoForge} constructs the {@code PlatformBridge} directly rather than discovering it via
 * {@link ServiceLoader}, so it is not exercised headless.
 *
 * <p>This proves the mod loads under FML and that the {@link VersionAdapter} SPI resolves on the real NeoForge
 * classpath, plus the {@code @Mod} entrypoint declaration. It does not exercise the client-only
 * {@code @Mod(dist=CLIENT)} constructor or live event firing ({@code RegisterKeyMappingsEvent} /
 * {@code ClientTickEvent.Post} / disconnect). The harness runs server-dist and these need a running client; those are
 * not exercised headless.
 */
class NeoForgeServiceResolutionTest {
    @Test
    void versionAdapterResolvesOnNeoForge() {
        VersionAdapter adapter = ServiceLoader.load(VersionAdapter.class, Wdl.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No VersionAdapter service resolved on NeoForge"));
        assertInstanceOf(VersionAdapterImpl.class, adapter,
                "VersionAdapter must resolve to the 1.21.11 band adapter");
    }

    @Test
    void entrypointIsAnnotatedClientMod() {
        Mod mod = WdlNeoForge.class.getAnnotation(Mod.class);
        assertNotNull(mod, "WdlNeoForge must carry @Mod");
        assertEquals("wdl", mod.value(), "@Mod id must match the mods.toml modId + ServiceLoader wiring");
        assertArrayEquals(new Dist[] { Dist.CLIENT }, mod.dist(), "client-only entrypoint");
    }
}
