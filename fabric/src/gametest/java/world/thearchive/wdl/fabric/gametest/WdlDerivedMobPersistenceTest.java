// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * The mobs are {@code NoAI} so they do not wander out of the chunk.
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
            server.runCommand("summon minecraft:zombie " + at
                    + " {NoAI:1b,HandItems:[{id:\"minecraft:diamond_sword\",count:1},{}]}");
            server.runCommand("summon minecraft:zombie " + at
                    + " {NoAI:1b,HandItems:[{id:\"minecraft:iron_sword\",count:1},{}]}");
            server.runCommand("summon minecraft:zombie " + at
                    + " {NoAI:1b,ArmorItems:[{},{},{},{id:\"minecraft:netherite_helmet\",count:1}]}");
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
        return entity.getBoolean("PersistenceRequired");
    }

    private static CompoundTag findZombieBySlot(List<CompoundTag> entities, String slot, String itemId) {
        return entities.stream()
                .filter(entity -> entity.getString("id").equals("minecraft:zombie"))
                .filter(entity -> itemId.equals(slotItemId(entity, slot)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no captured zombie with " + slot + "=" + itemId + " in: "
                        + entities.stream().map(entity -> (entity.contains("id") ? entity.getString("id") : "?"))
                                .toList()));
    }

    private static CompoundTag findZombieByName(List<CompoundTag> entities, String customName) {
        return entities.stream()
                .filter(entity -> entity.getString("id").equals("minecraft:zombie"))
                .filter(entity -> customName.equals(customNameOf(entity)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no captured zombie named " + customName + " in: "
                        + entities.stream().map(entity -> (entity.contains("id") ? entity.getString("id") : "?"))
                                .toList()));
    }

    private static String customNameOf(CompoundTag entity) {
        String json = entity.getString("CustomName");
        if (json.isEmpty()) {
            return "";
        }
        JsonElement name = JsonParser.parseString(json);
        if (name.isJsonPrimitive()) {
            return name.getAsString();
        }
        return name.isJsonObject() && name.getAsJsonObject().has("text")
                ? name.getAsJsonObject().get("text").getAsString()
                : "";
    }

    private static String slotItemId(CompoundTag entity, String slot) {
        return switch (slot) {
            case "mainhand" -> listItemId(entity, "HandItems", 0);
            case "offhand" -> listItemId(entity, "HandItems", 1);
            case "feet" -> listItemId(entity, "ArmorItems", 0);
            case "legs" -> listItemId(entity, "ArmorItems", 1);
            case "chest" -> listItemId(entity, "ArmorItems", 2);
            case "head" -> listItemId(entity, "ArmorItems", 3);
            default -> "";
        };
    }

    private static String listItemId(CompoundTag entity, String key, int index) {
        ListTag items = entity.getList(key, Tag.TAG_COMPOUND);
        return index < items.size() ? items.getCompound(index).getString("id") : "";
    }
}
