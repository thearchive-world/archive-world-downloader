// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Server-authoritative {@code PersistenceRequired} restoration on capture, exercised end to end. Two vanilla mechanisms
 * set the flag and the capture reconstructs both: a name tag renames a mob ({@code Mob.hasCustomName}), and a mob
 * equips an item impossible for its natural spawn, proving a loot pickup ({@code NaturalEquipment.wasLootEquipped}). A
 * custom-named zombie and zombies carrying a diamond sword (mainhand) or netherite helmet (head) must all be stamped,
 * while a zombie holding a natural-pool iron sword is the ambiguous control and must not be. Summoning them at one spot
 * down an otherwise identical code path proves the capture discriminates rather than blindly stamping, and reaches the
 * live {@code Mob} paths the headless unit tests cannot (a real {@code Mob} needs a {@code Level}). Force persistence
 * stays off (WdlConfig.DEFAULTS) so each stamp is attributable to its own mechanism, and the mobs are {@code NoAI} so
 * they neither wander out of the chunk nor pick anything up.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlDerivedMobPersistenceTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            ChunkPos playerChunk = context.computeOnClient(client -> client.player.chunkPosition());
            BlockPos base = context.computeOnClient(client -> client.player.blockPosition());
            String at = (base.getX() + 0.5) + " " + base.getY() + " " + (base.getZ() + 0.5);

            CaptureDriver run = CaptureDriver.start(context,
                    new DownloadTarget("wdl-derived-persist", "wdl-derived-persist", DownloadMode.NEW),
                    WdlConfig.DEFAULTS);
            run.tick(5);
            // Proven pickup: a diamond sword is impossible for a natural zombie mainhand.
            server.runCommand("summon minecraft:zombie " + at
                    + " {NoAI:1b,equipment:{mainhand:{id:\"minecraft:diamond_sword\",count:1}}}");
            // Control: an iron sword is inside the zombie mainhand pool, so ambiguous, and must not be stamped.
            server.runCommand("summon minecraft:zombie " + at
                    + " {NoAI:1b,equipment:{mainhand:{id:\"minecraft:iron_sword\",count:1}}}");
            // Armor-slot pickup: a netherite helmet is impossible for a natural zombie head, so it must be stamped.
            server.runCommand("summon minecraft:zombie " + at
                    + " {NoAI:1b,equipment:{head:{id:\"minecraft:netherite_helmet\",count:1}}}");
            // Named-mob path: a custom name (the effect of a name tag) makes hasCustomName() true, so this
            // flagship case of a name-tagged pet must be stamped. Empty hands keep it off the pickup path.
            server.runCommand("summon minecraft:zombie " + at + " {NoAI:1b,CustomName:\"Rex\"}");
            Path saveRoot = run.tick(60).stopAndAwaitSave();

            Optional<CompoundTag> entityChunk = CaptureReadback.readEntityChunk(saveRoot, playerChunk);
            Check.that(entityChunk.isPresent(), "entity chunk " + playerChunk + " is missing from the save");
            List<CompoundTag> entities = CaptureReadback.entities(entityChunk.get());

            CompoundTag proven = findZombieBySlot(entities, "mainhand", "minecraft:diamond_sword");
            Check.that(persistent(proven),
                    "the diamond-sword zombie proves a pickup and must be stamped PersistenceRequired");

            CompoundTag natural = findZombieBySlot(entities, "mainhand", "minecraft:iron_sword");
            Check.that(!persistent(natural),
                    "the iron-sword zombie is ambiguous (natural pool) and must not be stamped");

            CompoundTag armor = findZombieBySlot(entities, "head", "minecraft:netherite_helmet");
            Check.that(persistent(armor),
                    "the netherite-helmet zombie proves an armor pickup and must be stamped PersistenceRequired");

            CompoundTag named = findZombieByName(entities, "Rex");
            Check.that(persistent(named),
                    "the name-tagged zombie is the flagship named-mob path and must be stamped PersistenceRequired");
        }
    }

    private static boolean persistent(CompoundTag entity) {
        return entity.getBoolean("PersistenceRequired").orElse(false);
    }

    private static CompoundTag findZombieBySlot(List<CompoundTag> entities, String slot, String itemId) {
        return entities.stream()
                .filter(entity -> entity.getString("id").orElse("").equals("minecraft:zombie"))
                .filter(entity -> itemId.equals(slotItemId(entity, slot)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no captured zombie with " + slot + "=" + itemId + " in: "
                        + entities.stream().map(entity -> entity.getString("id").orElse("?")).toList()));
    }

    private static CompoundTag findZombieByName(List<CompoundTag> entities, String customName) {
        return entities.stream()
                .filter(entity -> entity.getString("id").orElse("").equals("minecraft:zombie"))
                .filter(entity -> customName.equals(customNameOf(entity)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no captured zombie named " + customName + " in: "
                        + entities.stream().map(entity -> entity.getString("id").orElse("?")).toList()));
    }

    /**
     * The custom name a mob was captured with: {@code Entity} writes it under {@code CustomName}, and a plain literal
     * component collapses to a bare string through {@code ComponentSerialization.CODEC}.
     */
    private static String customNameOf(CompoundTag entity) {
        return entity.getString("CustomName").orElse("");
    }

    /** The serialized item id worn in a slot: entities/ writes equipment as a slot-name map (EntityEquipment.CODEC). */
    private static String slotItemId(CompoundTag entity, String slot) {
        return entity.getCompound("equipment")
                .flatMap(equipment -> equipment.getCompound(slot))
                .flatMap(item -> item.getString("id"))
                .orElse("");
    }
}
