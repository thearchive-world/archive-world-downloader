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

class NeoForgeServiceResolutionTest {
    @Test
    void versionAdapterResolvesOnNeoForge() {
        VersionAdapter adapter = ServiceLoader.load(VersionAdapter.class, Wdl.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No VersionAdapter service resolved on NeoForge"));
        assertInstanceOf(VersionAdapterImpl.class, adapter, "VersionAdapter must resolve to the 1.21.11 band adapter");
    }

    @Test
    void entrypointIsAnnotatedClientMod() {
        Mod mod = WdlNeoForge.class.getAnnotation(Mod.class);
        assertNotNull(mod, "WdlNeoForge must carry @Mod");
        assertEquals("wdl", mod.value(), "@Mod id must match the mods.toml modId + ServiceLoader wiring");
        assertArrayEquals(new Dist[] { Dist.CLIENT }, mod.dist(), "client-only entrypoint");
    }
}
