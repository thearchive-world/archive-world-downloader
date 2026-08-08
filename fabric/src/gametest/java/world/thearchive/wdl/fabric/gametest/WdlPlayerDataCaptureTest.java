// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Player-data axis: the local player's inventory and position reach {@code players/data/<uuid>.dat}, which at 26.x is
 * the captured player's home while level.dat only stamps its {@code singleplayer_uuid}. The given item arrives at the
 * client over the container-set-slot sync, and the capture assembles the live client player to disk at finish, so a
 * present item proves the whole client-player-to-disk path.
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

            CompoundTag player = CaptureReadback.capturedPlayer(saveRoot);
            Check.that(!player.isEmpty(), "players/data/<uuid>.dat has no captured player data");
            Check.that(player.getList("Pos").isPresent(), "the captured player has no Pos in players/data/<uuid>.dat");
            List<CompoundTag> inventory = player.getListOrEmpty("Inventory").compoundStream().toList();
            boolean hasDiamond = inventory.stream()
                    .anyMatch(item -> item.getString("id").orElse("").equals("minecraft:diamond"));
            Check.that(hasDiamond, "the given diamond is absent from the captured Player Inventory: "
                    + inventory.stream().map(item -> item.getString("id").orElse("?")).toList());
        }
    }
}
