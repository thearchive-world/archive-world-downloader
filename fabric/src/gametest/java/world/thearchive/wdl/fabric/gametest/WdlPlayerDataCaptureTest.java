// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Player-data axis: the local player's inventory and position reach level.dat's {@code Player} slot. The given item
 * arrives at the client over the container-set-slot sync, and the capture assembles the live client player into
 * level.dat at finish, so a present item proves the whole client-player-to-disk path.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlPlayerDataCaptureTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            server.runCommand("give @a minecraft:diamond 7");
            context.waitFor(client -> client.player != null
                    && client.player.getInventory().contains(stack -> stack.is(Items.DIAMOND)));

            Path saveRoot = CaptureDriver.capture(context,
                    new DownloadTarget("wdl-player", "wdl-player", DownloadMode.NEW), WdlConfig.DEFAULTS, 30);

            CompoundTag player = CaptureReadback.levelData(saveRoot).getCompound("Player");
            Check.that(!player.isEmpty(), "level.dat has no Player data");
            Check.that(player.contains("Pos", Tag.TAG_LIST), "the captured Player has no Pos in level.dat");
            List<CompoundTag> inventory = player.getList("Inventory", Tag.TAG_COMPOUND).stream()
                    .map(t -> (CompoundTag) t).collect(Collectors.toList());
            boolean hasDiamond = inventory.stream()
                    .anyMatch(item -> item.getString("id").equals("minecraft:diamond"));
            Check.that(hasDiamond, "the given diamond is absent from the captured Player Inventory: "
                    + inventory.stream().map(item -> (item.contains("id") ? item.getString("id") : "?"))
                            .collect(Collectors.toList()));
        }
    }
}
