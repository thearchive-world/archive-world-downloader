// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.filledMap;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.PlayerSinkImpl;
import world.thearchive.wdl.core.MapManifest;
import world.thearchive.wdl.testsupport.HeadlessLevel;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The headless guard for the player capture's detachment: the finish-time inventory capture must hand the map-id remap
 * a copy, not the stacks the player is holding. Below 1.15 vanilla {@code ItemStack.save} puts the live stack's own
 * {@code tag} compound into its output, so a capture that does not copy lets the remap rewrite the held item's map id,
 * which blanks a carried map on the live client until the server resends the slot.
 */
class PlayerCaptureAliasTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    private static final class HeadlessPlayer extends Player {
        HeadlessPlayer() {
            super(HeadlessLevel.get(), new GameProfile(UUID.randomUUID(), "wdl-test"));
        }

        @Override
        public boolean isSpectator() {
            return false;
        }

        @Override
        public boolean isCreative() {
            return false;
        }
    }

    @Test
    void remappingTheCapturedInventoryLeavesTheHeldStackAlone() {
        ItemStack live = filledMap(1988);
        HeadlessPlayer player = new HeadlessPlayer();
        player.inventory.items.set(0, live);
        CompoundTag raw = new PlayerSinkImpl().capturePlayer(player);
        MapArchive archive = new MapArchive(MapManifest.empty(), sessionId -> null, (archiveId, dataTag) -> {});

        archive.remap(raw, "Inventory");

        assertNotEquals(1988, firstInventoryMapId(raw), "the captured inventory carries the archive id");
        assertEquals(1988, live.getOrCreateTag().getInt("map"),
                "the held stack keeps the server's map id, so a carried map still renders");
    }

    private static int firstInventoryMapId(CompoundTag raw) {
        return raw.getList("Inventory", 10).getCompound(0).getCompound("tag").getInt("map");
    }
}
