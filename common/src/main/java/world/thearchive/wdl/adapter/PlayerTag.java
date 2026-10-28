// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

final class PlayerTag {
    private PlayerTag() {}

    /**
     * Apply the strip opt-outs to {@code raw}: when {@code saveInventory} is false, drop {@code "Inventory"},
     * {@code "SelectedItemSlot"}, and {@code "equipment"} (on versions that write it, offhand and armor live there
     * rather than in the {@code "Inventory"} list); when {@code saveEnderChest} is false, drop {@code "EnderItems"}.
     */
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

    /**
     * Unconditional privacy strip: remove the coordinate-bearing real-world location fields, the last death location
     * and the last explosion-impulse impact position, where the version writes them.
     */
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
     * Write the canonical {@code "Dimension"} id. It is absent from a {@code LocalPlayer} serialize, so we own that
     * single write.
     */
    static void setDimension(CompoundTag raw, ResourceKey<Level> dimension) {
        raw.putString("Dimension", dimension.location().toString());
    }

    static @Nullable ResourceKey<Level> dimensionOf(CompoundTag raw) {
        return VanillaDimensions.forId(raw.getStringOr("Dimension", ""));
    }

    static void setPosition(CompoundTag raw, BlockPos pos, float yaw, float pitch) {
        ListTag position = new ListTag();
        position.add(DoubleTag.valueOf(pos.getX()));
        position.add(DoubleTag.valueOf(pos.getY()));
        position.add(DoubleTag.valueOf(pos.getZ()));
        raw.put("Pos", position);
        ListTag rotation = new ListTag();
        rotation.add(FloatTag.valueOf(yaw));
        rotation.add(FloatTag.valueOf(pitch));
        raw.put("Rotation", rotation);
    }

    static void setEnderItems(CompoundTag raw, CompoundTag enderHolder) {
        if (enderHolder.get("Items") instanceof ListTag items) {
            raw.put("EnderItems", items);
        }
    }

    static void setRootVehicle(CompoundTag raw, UUID attach, CompoundTag entityTag) {
        CompoundTag rootVehicle = new CompoundTag();
        rootVehicle.store("Attach", UUIDUtil.CODEC, attach);
        rootVehicle.put("Entity", entityTag);
        raw.put("RootVehicle", rootVehicle);
    }

    /**
     * Carry the prior session's ender chest forward into {@code freshRaw} on a resume that did not re-open it: the
     * ender chest reaches the client only through its open menu, so a resume that never opens it produces a fresh
     * player tag with empty {@code "EnderItems"} that would wipe the previously-downloaded one.
     */
    static boolean carryForwardEnderItems(CompoundTag priorPlayer, CompoundTag freshRaw) {
        if (freshRaw.get("EnderItems") instanceof ListTag fresh && !fresh.isEmpty()) {
            return false;
        }
        if (!(priorPlayer.get("EnderItems") instanceof ListTag prior) || prior.isEmpty()) {
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
        if (!(priorPlayer.get("RootVehicle") instanceof CompoundTag prior)
                || !(freshRaw.get("RootVehicle") instanceof CompoundTag fresh)) {
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
        if (!(fresh.get("Entity") instanceof CompoundTag freshEntity)
                || !(prior.get("Entity") instanceof CompoundTag priorEntity)) {
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
