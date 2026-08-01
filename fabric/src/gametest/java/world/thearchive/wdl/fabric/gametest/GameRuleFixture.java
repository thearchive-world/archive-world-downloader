// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.world.level.gamerules.GameRule;

/**
 * Sets a gamerule with the command name taken from {@link GameRule#id()}, so a band rename cannot silently no-op it.
 * The framework's {@code runCommand} is void and swallows a failed command, so a hardcoded name that has drifted across
 * bands (1.21.x renamed them to snake_case, e.g. doMobLoot to mob_drops) would set nothing while the run stays green.
 * Taking the name from the {@code GameRule} field instead (e.g. {@code
 * GameRules.MOB_DROPS}) makes it compile-forced-correct per band: a port that names the wrong field does not compile,
 * and the right field carries the right command name.
 */
@SuppressWarnings("UnstableApiUsage")
final class GameRuleFixture {
    private GameRuleFixture() {}

    /** Run {@code gamerule <rule.id()> <value>}; the name from {@code rule.id()} cannot be band-wrong. */
    static void set(TestServerContext server, GameRule<Boolean> rule, boolean value) {
        server.runCommand("gamerule " + rule.id() + " " + value);
    }
}
