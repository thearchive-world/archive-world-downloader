// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.dimension.DimensionType;
import org.jspecify.annotations.Nullable;

final class PlayerTag {
    private PlayerTag() {}

    static void applyStripKnobs(CompoundTag raw, boolean saveInventory, boolean saveEnderChest) {
        if (!saveInventory) {
            raw.remove("Inventory");
            raw.remove("SelectedItemSlot");
            raw.remove("equipment");
        }
        if (!saveEnderChest) {
            raw.remove("EnderItems");
        }
    }

    static void stripDeathLocation(CompoundTag raw) {
        raw.remove("LastDeathLocation");
        raw.remove("current_explosion_impact_pos");
    }

    static void stripRespawnPoint(CompoundTag raw) {
        raw.remove("SpawnX");
        raw.remove("SpawnY");
        raw.remove("SpawnZ");
        raw.remove("SpawnForced");
        raw.remove("Spawns");
    }

    /**
     * Vanilla reads this back as the integer id ({@code Entity.load}), so it is written as the id, not the 1.16 string
     * form: a string here is read by {@code getInt} as 0 and lands the player in the overworld.
     */
    static void setDimension(CompoundTag raw, DimensionType dimension) {
        raw.putInt("Dimension", dimension.getId());
    }

    static @Nullable DimensionType dimensionOf(CompoundTag raw) {
        // 99 is vanilla's any-numeric tag sentinel: an absent or non-numeric tag (a 1.16 string id) names no dimension
        // rather than getInt collapsing it to the overworld.
        if (!raw.contains("Dimension", 99)) {
            return null;
        }
        return DimensionType.getById(raw.getInt("Dimension"));
    }

    /**
     * Writes the shapes vanilla reads back (a three-double list, and a two-float list of yaw then pitch); a mismatched
     * shape loads silently.
     */
    static void setPosition(CompoundTag raw, BlockPos pos, float yaw, float pitch) {
        ListTag position = new ListTag();
        position.add(new DoubleTag(pos.getX()));
        position.add(new DoubleTag(pos.getY()));
        position.add(new DoubleTag(pos.getZ()));
        raw.put("Pos", position);
        ListTag rotation = new ListTag();
        rotation.add(new FloatTag(yaw));
        rotation.add(new FloatTag(pitch));
        raw.put("Rotation", rotation);
    }

    static void setEnderItems(CompoundTag raw, CompoundTag enderHolder) {
        if (enderHolder.get("Items") instanceof ListTag) {
            ListTag items = (ListTag) enderHolder.get("Items");
            raw.put("EnderItems", items);
        }
    }

    static void setRootVehicle(CompoundTag raw, UUID attach, CompoundTag entityTag) {
        CompoundTag rootVehicle = new CompoundTag();
        rootVehicle.putUUID("Attach", attach);
        rootVehicle.put("Entity", entityTag);
        raw.put("RootVehicle", rootVehicle);
    }

    /**
     * Carry the prior session's ender chest forward into {@code freshRaw} on a resume that did not re-open it: the
     * ender chest reaches the client only through its open menu, so a resume that never opens it produces a fresh
     * player tag with empty {@code "EnderItems"} that would wipe the previously-downloaded one.
     */
    static boolean carryForwardEnderItems(CompoundTag priorPlayer, CompoundTag freshRaw) {
        if (freshRaw.get("EnderItems") instanceof ListTag && !((ListTag) freshRaw.get("EnderItems")).isEmpty()) {
            return false;
        }
        ListTag prior = priorPlayer.get("EnderItems") instanceof ListTag
                ? (ListTag) priorPlayer.get("EnderItems")
                : null;
        if (prior == null || prior.isEmpty()) {
            return false;
        }
        freshRaw.put("EnderItems", prior.copy());
        return true;
    }

    /**
     * Restore a prior download's captured mount contents into a resumed player's fresh {@code RootVehicle} on a resume
     * that finished SEATED in the same mount without reopening its container: that fresh serialize carries empty
     * menu-only contents, and the wholesale rewrite of the saved player would drop the prior download's loot. A resume
     * that finished un-seated carries nothing: a dismounted mount is a normal world entity, captured by the standalone
     * entity path, and writing it into the Player slot would wrongly re-seat the player and collide same-UUID with the
     * standalone copy.
     */
    static boolean restorePriorMountContents(CompoundTag priorPlayer, CompoundTag freshRaw) {
        CompoundTag prior = priorPlayer.get("RootVehicle") instanceof CompoundTag
                ? (CompoundTag) priorPlayer.get("RootVehicle")
                : null;
        CompoundTag fresh = freshRaw.get("RootVehicle") instanceof CompoundTag
                ? (CompoundTag) freshRaw.get("RootVehicle")
                : null;
        if (prior == null || fresh == null) {
            return false;
        }
        return restorePriorMountItems(prior, fresh);
    }

    /**
     * Each record is a tree, so both are indexed by node and every prior node's contents carry into the fresh node of
     * the same {@code UUID}: the entity the player rides need not be the root of either record, and the two roots
     * differ whenever something has pushed itself under the mount since. Matching per node is what keeps a genuine
     * mount switch, which shares no node with the prior record, from grafting one mount's contents onto another.
     */
    private static boolean restorePriorMountItems(CompoundTag prior, CompoundTag fresh) {
        CompoundTag freshEntity = fresh.get("Entity") instanceof CompoundTag ? (CompoundTag) fresh.get("Entity") : null;
        CompoundTag priorEntity = prior.get("Entity") instanceof CompoundTag ? (CompoundTag) prior.get("Entity") : null;
        if (freshEntity == null || priorEntity == null) {
            return false;
        }
        Map<UUID, CompoundTag> freshNodes = EntityTreeWalk.byUuid(freshEntity);
        boolean restored = false;
        for (Map.Entry<UUID, CompoundTag> priorNode : EntityTreeWalk.byUuid(priorEntity).entrySet()) {
            CompoundTag freshNode = freshNodes.get(priorNode.getKey());
            if (freshNode != null && NbtMerge.carryList(priorNode.getValue(), freshNode, "Items")) {
                restored = true;
            }
        }
        return restored;
    }
}
