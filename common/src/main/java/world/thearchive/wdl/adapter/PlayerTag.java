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

/**
 * Band-agnostic NBT ops on an already-serialized player tag (the {@code saveWithoutId} result), using the band-stable
 * {@code get}/{@code remove}/{@code put}/{@code store} NBT ops (the {@link ContainerMerge} discipline) and
 * vanilla-verbatim keys. Operates on our own captured copy, never on a live entity, so it cannot corrupt the player's
 * session.
 *
 * <ul>
 * <li>{@link #applyStripKnobs} drops the inventory and/or ender-chest keys per the opt-out config.</li>
 * <li>{@link #stripDeathLocation} is the unconditional privacy strip (no toggle).</li>
 * <li>{@link #setDimension} writes the {@code "Dimension"} (absent from a {@code LocalPlayer} serialize, so we own that
 * single write), and {@link #dimensionOf} reads it back.</li>
 * <li>{@link #setPosition} overwrites {@code "Pos"} and {@code "Rotation"} with the capture anchor, so the saved player
 * lands with the world spawn.</li>
 * <li>{@link #setEnderItems} remaps a captured container holder's {@code "Items"} into {@code "EnderItems"}.</li>
 * <li>{@link #setRootVehicle} writes the vanilla {@code "RootVehicle"} record ({@code "Attach"} plus {@code "Entity"})
 * for a seated player's mount, the {@code store}-with-codec op ({@code UUIDUtil.CODEC}) whose int-array {@code Attach}
 * shape is band-local vanilla-verbatim, re-authored at the deep-band seam, the vanilla-verbatim-shape discipline
 * {@link #setPosition} already carries.</li>
 * </ul>
 */
final class PlayerTag {
    private PlayerTag() {}

    /**
     * Apply the strip opt-outs to {@code raw}: when {@code saveInventory} is false, drop {@code "Inventory"},
     * {@code "SelectedItemSlot"}, and {@code "equipment"} (offhand and armor live there, not in the 36-slot
     * {@code "Inventory"} list); when {@code saveEnderChest} is false, drop {@code "EnderItems"}. Each removal leaves a
     * tag vanilla loads (every dropped key is optional).
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
     * Unconditional privacy strip: remove both coordinate-bearing real-world location fields, the last death location
     * and the last explosion-impulse impact position. Both are written by vanilla only when present, so removing them
     * leaves a valid compound, and there is deliberately no toggle that restores either.
     */
    static void stripDeathLocation(CompoundTag raw) {
        raw.remove("LastDeathLocation");
        raw.remove("current_explosion_impact_pos");
    }

    /**
     * Write the canonical {@code "Dimension"} id. The value is whatever {@code dimension} carries, verbatim: the caller
     * passes the by-type-canonicalized capture dimension ({@code targetDimension}), so a non-standard server level key
     * and a datapack dimension alike already resolve to the vanilla folder the capture writes them under, and the id
     * written is always one of the three {@link VanillaDimensions#forType} returns. Matches
     * {@code ServerPlayer.addAdditionalSaveData}, which writes the plain id string.
     */
    static void setDimension(CompoundTag raw, ResourceKey<Level> dimension) {
        raw.putString("Dimension", dimension.location().toString());
    }

    /**
     * The dimension a player tag's {@code "Dimension"} names, or null when the key is absent or holds an id outside the
     * three {@link #setDimension} can have written. The read side of {@link #setDimension}, and the only way to learn
     * which folder a prior download parked something in.
     */
    static @Nullable ResourceKey<Level> dimensionOf(CompoundTag raw) {
        return VanillaDimensions.forId(raw.getStringOr("Dimension", ""));
    }

    /**
     * Overwrite the player tag's {@code "Pos"} and {@code "Rotation"} with the capture anchor, so the saved player
     * entity lands with the world spawn rather than wherever the client body was parked. Writes the vanilla-verbatim
     * shapes {@code Vec3.CODEC} and {@code Vec2.CODEC} read back (a three-double list, and a two-float list of yaw then
     * pitch); a mismatched shape loads silently as the origin.
     */
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

    /**
     * Remap a captured ender-chest holder's {@code "Items"} list (the {@link ContainerSink#captureItems} shape) into
     * the player tag's {@code "EnderItems"} (the same {@code ItemStackWithSlot} element form on this band, so the list
     * transplants directly). A holder with no {@code "Items"} list leaves the existing {@code "EnderItems"} untouched.
     */
    static void setEnderItems(CompoundTag raw, CompoundTag enderHolder) {
        if (enderHolder.get("Items") instanceof ListTag items) {
            raw.put("EnderItems", items);
        }
    }

    /**
     * Write the seated player's mount as vanilla's {@code "RootVehicle"} record: {@code "Attach"} is the direct
     * vehicle's {@code UUID} (the {@code UUIDUtil.CODEC} four-int array) and {@code "Entity"} is the root vehicle
     * serialized standalone. Mirrors {@code ServerPlayer.saveParentVehicle}, which
     * {@code ServerPlayer.loadAndSpawnParentVehicle} reads on a vanilla open (a singleplayer saved player included) to
     * respawn the vehicle and re-seat the player; an absent record is a clean load that leaves the player standing.
     */
    static void setRootVehicle(CompoundTag raw, UUID attach, CompoundTag entityTag) {
        CompoundTag rootVehicle = new CompoundTag();
        rootVehicle.store("Attach", UUIDUtil.CODEC, attach);
        rootVehicle.put("Entity", entityTag);
        raw.put("RootVehicle", rootVehicle);
    }

    /**
     * Carry the prior session's ender chest forward into {@code freshRaw} on a resume that did not re-open it: the
     * ender chest reaches the client only through its open menu, so a resume that never opens it produces a fresh
     * player tag with empty {@code "EnderItems"} that would wipe the previously-downloaded one. Prefer non-empty, the
     * {@link ChunkMerge} discipline: if the fresh tag already has a non-empty ender chest (re-opened this session) it
     * wins and nothing carries; otherwise the prior {@code "EnderItems"}, read from the prior download's player tag, is
     * copied in. Returns whether a carry-forward happened.
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
     * menu-only contents, and the wholesale rewrite of the saved player would drop the prior download's loot. Matched
     * per node on each entity's own {@code UUID}, so a mount switch never grafts contents. A resume that finished
     * un-seated carries nothing: a dismounted mount is a normal world entity, captured by the standalone entity path,
     * and writing it into the Player slot would wrongly re-seat the player and collide same-UUID with the standalone
     * copy. Returns whether it restored.
     */
    static boolean restorePriorMountContents(CompoundTag priorPlayer, CompoundTag freshRaw) {
        if (!(priorPlayer.get("RootVehicle") instanceof CompoundTag prior)
                || !(freshRaw.get("RootVehicle") instanceof CompoundTag fresh)) {
            return false;
        }
        return restorePriorMountItems(prior, fresh);
    }

    /**
     * Restore a prior download's captured container contents into a fresh {@code RootVehicle} whose {@code Entity}
     * serialized empty (a resume finished seated without reopening the container). Each record is a tree, so both are
     * indexed by node and every prior node's contents carry into the fresh node of the same {@code UUID}: the entity
     * the player rides need not be the root of either record, and the two roots differ whenever something has pushed
     * itself under the mount since. Matching per node is what keeps a genuine mount switch, which shares no node with
     * the prior record, from grafting one mount's contents onto another. Carries only where the fresh node holds no
     * contents of its own. Returns whether anything restored.
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
