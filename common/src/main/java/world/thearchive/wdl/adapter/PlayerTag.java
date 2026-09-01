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

/**
 * Band-agnostic NBT ops on an already-serialized player tag (the {@code writeToNBT} result), using the classic MCP
 * {@code getTag}/{@code removeTag}/{@code setTag} NBT ops (the {@link ContainerMerge} discipline) and vanilla-verbatim
 * keys. Operates on our own captured copy, never on a live entity, so it cannot corrupt the player's session.
 *
 * <ul>
 * <li>{@link #applyStripKnobs} drops the inventory key per the opt-out config.</li>
 * <li>{@link #stripDeathLocation} is the unconditional privacy strip (no toggle).</li>
 * <li>{@link #setDimension} writes the {@code "Dimension"}, and {@link #dimensionOf} reads it back.</li>
 * <li>{@link #setPosition} overwrites {@code "Pos"} and {@code "Rotation"} with the capture anchor, so the saved player
 * lands with the world spawn.</li>
 * <li>{@link #setEnderItems} remaps a captured container holder's {@code "Items"} into {@code "EnderItems"}.</li>
 * <li>{@link #setRootVehicle} writes the vanilla {@code "RootVehicle"} record ({@code "Attach"} plus {@code "Entity"})
 * for a seated player's mount, the {@code "Attach"} UUID written through {@code NBTTagCompound.setUniqueId}.</li>
 * </ul>
 */
final class PlayerTag {
    private PlayerTag() {}

    /**
     * Apply the strip opt-out to {@code raw}: when {@code saveInventory} is false, drop {@code "Inventory"} and
     * {@code "SelectedItemSlot"}. At 1.12.2 the 36-slot main inventory, the 4-slot armor and the offhand slot are all
     * written into the single {@code "Inventory"} list (there is no separate equipment compound), so dropping that one
     * key already strips armor and offhand too; when {@code saveEnderChest} is false, drop {@code "EnderItems"}. Each
     * removal leaves a tag vanilla loads (every dropped key is optional).
     */
    static void applyStripKnobs(NBTTagCompound raw, boolean saveInventory, boolean saveEnderChest) {
        if (!saveInventory) {
            raw.removeTag("Inventory");
            raw.removeTag("SelectedItemSlot");
        }
        if (!saveEnderChest) {
            raw.removeTag("EnderItems");
        }
    }

    /**
     * Unconditional privacy strip: remove both coordinate-bearing real-world location fields. Neither key exists in a
     * 1.12.2 player tag (the last death location is a 1.19 addition and the explosion-impact position a 1.21 addition),
     * so this is a harmless no-op kept for signature parity with the shared caller.
     */
    static void stripDeathLocation(NBTTagCompound raw) {
        raw.removeTag("LastDeathLocation");
        raw.removeTag("current_explosion_impact_pos");
    }

    /**
     * Write the canonical {@code "Dimension"} id. The caller passes the by-type-canonicalized capture dimension
     * ({@code targetDimension}), so a non-standard server level key and a datapack dimension alike already resolve to
     * one of the three {@link VanillaDimensions#forType} returns. Vanilla reads this back as the integer id
     * ({@code Entity.readFromNBT}), which is exactly the shape written here.
     */
    static void setDimension(NBTTagCompound raw, DimensionType dimension) {
        raw.setInteger("Dimension", dimension.getId());
    }

    /**
     * The dimension a player tag's {@code "Dimension"} names, or null when the key is absent or holds an id outside the
     * three {@link #setDimension} can have written. The read side of {@link #setDimension}, and the only way to learn
     * which folder a prior download parked something in.
     */
    static @Nullable DimensionType dimensionOf(NBTTagCompound raw) {
        // 99 is vanilla's any-numeric tag sentinel: an absent or non-numeric tag names no dimension rather than
        // getInteger collapsing it to the overworld. DimensionType.getById throws for an id outside the three
        // registered types (unlike the higher bands' null-returning form), so the id is checked directly instead.
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
     * Overwrite the player tag's {@code "Pos"} and {@code "Rotation"} with the capture anchor, so the saved player
     * entity lands with the world spawn rather than wherever the client body was parked. Writes the vanilla-verbatim
     * shapes {@code Entity.readFromNBT} reads back (a three-double list read through {@code getDoubleAt}, and a
     * two-float list of yaw then pitch read through {@code getFloatAt}); a mismatched shape loads silently as the
     * origin (an out-of-range or wrong-type element answers 0).
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

    /**
     * Remap a captured ender-chest holder's {@code "Items"} list (the {@link ContainerSink#captureItems} shape) into
     * the player tag's {@code "EnderItems"} (the same item-list element form on this band, so the list transplants
     * directly). A holder with no {@code "Items"} list leaves the existing {@code "EnderItems"} untouched.
     */
    static void setEnderItems(NBTTagCompound raw, NBTTagCompound enderHolder) {
        if (enderHolder.getTag("Items") instanceof NBTTagList) {
            NBTTagList items = (NBTTagList) enderHolder.getTag("Items");
            raw.setTag("EnderItems", items);
        }
    }

    /**
     * Write the seated player's mount as vanilla's {@code "RootVehicle"} record: {@code "Attach"} is the direct
     * vehicle's {@code UUID} (the {@code AttachMost}/{@code AttachLeast} long pair {@code NBTTagCompound.setUniqueId}
     * writes) and {@code "Entity"} is the root vehicle serialized standalone. Mirrors
     * {@code EntityPlayerMP.writeEntityToNBT}'s own write; on a vanilla open (a singleplayer saved player included)
     * {@code PlayerList} reads this back at player join, respawns the vehicle and re-seats the player through
     * {@code startRiding}. An absent record is a clean load that leaves the player standing.
     */
    static void setRootVehicle(NBTTagCompound raw, UUID attach, NBTTagCompound entityTag) {
        NBTTagCompound rootVehicle = new NBTTagCompound();
        rootVehicle.setUniqueId("Attach", attach);
        rootVehicle.setTag("Entity", entityTag);
        raw.setTag("RootVehicle", rootVehicle);
    }

    /**
     * Carry the prior session's ender chest forward into {@code freshRaw} on a resume that did not re-open it: the
     * ender chest reaches the client only through its open menu, so a resume that never opens it produces a fresh
     * player tag with empty {@code "EnderItems"} that would wipe the previously-downloaded one. Prefer non-empty, the
     * {@link ChunkMerge} discipline: if the fresh tag already has a non-empty ender chest (re-opened this session) it
     * wins and nothing carries; otherwise the prior {@code "EnderItems"}, read from the prior download's player tag, is
     * copied in. Returns whether a carry-forward happened.
     */
    static boolean carryForwardEnderItems(NBTTagCompound priorPlayer, NBTTagCompound freshRaw) {
        if (freshRaw.getTag("EnderItems") instanceof NBTTagList
                && !((NBTTagList) freshRaw.getTag("EnderItems")).hasNoTags()) {
            return false;
        }
        NBTTagList prior = priorPlayer.getTag("EnderItems") instanceof NBTTagList
                ? (NBTTagList) priorPlayer.getTag("EnderItems")
                : null;
        if (prior == null || prior.hasNoTags()) {
            return false;
        }
        freshRaw.setTag("EnderItems", prior.copy());
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
     * Restore a prior download's captured container contents into a fresh {@code RootVehicle} whose {@code Entity}
     * serialized empty (a resume finished seated without reopening the container). Each record is a tree, so both are
     * indexed by node and every prior node's contents carry into the fresh node of the same {@code UUID}: the entity
     * the player rides need not be the root of either record, and the two roots differ whenever something has pushed
     * itself under the mount since. Matching per node is what keeps a genuine mount switch, which shares no node with
     * the prior record, from grafting one mount's contents onto another. Carries only where the fresh node holds no
     * contents of its own. Returns whether anything restored.
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
