// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.world.level.GameRules;

/**
 * Sets a gamerule with the command name taken from {@link GameRules.Key#getId()}, so a band rename cannot silently
 * no-op it. The framework's {@code runCommand} is void and swallows a failed command, so a hardcoded name that has
 * drifted across bands (e.g. {@code doMobLoot} to {@code mob_drops}) would set nothing while the run stays green.
 */
@SuppressWarnings("UnstableApiUsage")
final class GameRuleFixture {
    private GameRuleFixture() {}

    static void set(TestServerContext server, GameRules.Key<GameRules.BooleanValue> rule, boolean value) {
        server.runCommand("gamerule " + rule.getId() + " " + value);
    }
}
