// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import org.jspecify.annotations.Nullable;

final class PlayerTag {
    private PlayerTag() {}

    static void applyStripKnobs(NBTTagCompound raw, boolean saveInventory, boolean saveEnderChest) {
        if (!saveInventory) {
            raw.removeTag("Inventory");
            raw.removeTag("SelectedItemSlot");
        }
        if (!saveEnderChest) {
            raw.removeTag("EnderItems");
        }
    }

    static void stripDeathLocation(NBTTagCompound raw) {
        raw.removeTag("LastDeathLocation");
        raw.removeTag("current_explosion_impact_pos");
    }

    static void stripRespawnPoint(NBTTagCompound raw) {
        raw.removeTag("SpawnX");
        raw.removeTag("SpawnY");
        raw.removeTag("SpawnZ");
        raw.removeTag("SpawnForced");
        raw.removeTag("Spawns");
    }

    static void setDimension(NBTTagCompound raw, DimensionType dimension) {
        raw.setInteger("Dimension", dimension.getId());
    }

    static @Nullable DimensionType dimensionOf(NBTTagCompound raw) {
        // 99 is vanilla's any-numeric tag sentinel: an absent or non-numeric tag names no dimension rather than
        // getInteger collapsing it to the overworld. DimensionType.getById throws for an id outside the registered
        // types (unlike the higher bands' null-returning form), so the id is checked directly instead.
        if (!raw.hasKey("Dimension", 99)) {
            return null;
        }
        int id = raw.getInteger("Dimension");
        if (id == DimensionType.OVERWORLD.getId()) {
            return DimensionType.OVERWORLD;
        }
        if (id == DimensionType.NETHER.getId()) {
            return DimensionType.NETHER;
        }
        return id == DimensionType.THE_END.getId() ? DimensionType.THE_END : null;
    }

    /**
     * Writes the vanilla-verbatim shapes {@code Entity.readFromNBT} reads back (a three-double list read through
     * {@code getDoubleAt}, and a two-float list of yaw then pitch read through {@code getFloatAt}); a mismatched shape
     * loads silently (an out-of-range or wrong-type element answers 0).
     */
    static void setPosition(NBTTagCompound raw, BlockPos pos, float yaw, float pitch) {
        NBTTagList position = new NBTTagList();
        position.appendTag(new NBTTagDouble(pos.getX()));
        position.appendTag(new NBTTagDouble(pos.getY()));
        position.appendTag(new NBTTagDouble(pos.getZ()));
        raw.setTag("Pos", position);
        NBTTagList rotation = new NBTTagList();
        rotation.appendTag(new NBTTagFloat(yaw));
        rotation.appendTag(new NBTTagFloat(pitch));
        raw.setTag("Rotation", rotation);
    }

    static void setEnderItems(NBTTagCompound raw, NBTTagCompound enderHolder) {
        if (enderHolder.getTag("Items") instanceof NBTTagList) {
            NBTTagList items = (NBTTagList) enderHolder.getTag("Items");
            raw.setTag("EnderItems", items);
        }
    }

    static void setRootVehicle(NBTTagCompound raw, UUID attach, NBTTagCompound entityTag) {
        NBTTagCompound rootVehicle = new NBTTagCompound();
        rootVehicle.setUniqueId("Attach", attach);
        rootVehicle.setTag("Entity", entityTag);
        raw.setTag("RootVehicle", rootVehicle);
    }

    /**
     * Carry the prior session's ender chest forward into {@code freshRaw} on a resume that did not re-open it: the
     * ender chest reaches the client only through its open menu, so a resume that never opens it produces a fresh
     * player tag with empty {@code "EnderItems"} that would wipe the previously-downloaded one.
     */
    static boolean carryForwardEnderItems(NBTTagCompound priorPlayer, NBTTagCompound freshRaw) {
        if (freshRaw.getTag("EnderItems") instanceof NBTTagList
                && !((NBTTagList) freshRaw.getTag("EnderItems")).isEmpty()) {
            return false;
        }
        NBTTagList prior = priorPlayer.getTag("EnderItems") instanceof NBTTagList
                ? (NBTTagList) priorPlayer.getTag("EnderItems")
                : null;
        if (prior == null || prior.isEmpty()) {
            return false;
        }
        freshRaw.setTag("EnderItems", prior.copy());
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
    static boolean restorePriorMountContents(NBTTagCompound priorPlayer, NBTTagCompound freshRaw) {
        NBTTagCompound prior = priorPlayer.getTag("RootVehicle") instanceof NBTTagCompound
                ? (NBTTagCompound) priorPlayer.getTag("RootVehicle")
                : null;
        NBTTagCompound fresh = freshRaw.getTag("RootVehicle") instanceof NBTTagCompound
                ? (NBTTagCompound) freshRaw.getTag("RootVehicle")
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
    private static boolean restorePriorMountItems(NBTTagCompound prior, NBTTagCompound fresh) {
        NBTTagCompound freshEntity = fresh.getTag("Entity") instanceof NBTTagCompound
                ? (NBTTagCompound) fresh.getTag("Entity")
                : null;
        NBTTagCompound priorEntity = prior.getTag("Entity") instanceof NBTTagCompound
                ? (NBTTagCompound) prior.getTag("Entity")
                : null;
        if (freshEntity == null || priorEntity == null) {
            return false;
        }
        Map<UUID, NBTTagCompound> freshNodes = EntityTreeWalk.byUuid(freshEntity);
        boolean restored = false;
        for (Map.Entry<UUID, NBTTagCompound> priorNode : EntityTreeWalk.byUuid(priorEntity).entrySet()) {
            NBTTagCompound freshNode = freshNodes.get(priorNode.getKey());
            if (freshNode != null && NbtMerge.carryList(priorNode.getValue(), freshNode, "Items")) {
                restored = true;
            }
        }
        return restored;
    }
}
