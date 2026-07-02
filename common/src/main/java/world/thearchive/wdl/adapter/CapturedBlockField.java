// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.List;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.Tag;
import org.jspecify.annotations.Nullable;

/**
 * Every block-entity datum this capture puts on disk, one constant per NBT key, carrying how the key is compared and
 * copied and whether it is captured content or open-time menu state. The single list two consumers read, so neither can
 * drift from the other: {@link ContainerMerge} writes the open-time state keys onto a block entity beside the sink's
 * {@code "Items"}, and {@link ChunkMerge} carries these keys forward from an on-disk chunk into a freshly re-captured
 * one, except where that write captured the field itself (see {@link #capturesWholeOnOpen}). A key written by one visit
 * and not carried by the next, at a position the next visit captured nothing for, is data the mod captured and then
 * dropped, so the two must enumerate the same set; adding a constant here reaches both.
 *
 * <p>The state subset is a whitelist on purpose, and stays one: it fails closed, so an internal holder key
 * ({@code wdl_block_entity_id}) can never leak to disk through the open-time write.
 *
 * <p>Content versus state decides {@link #holdsContent}, which is what {@link ChunkMerge#hasCapturedContent} and the
 * resume outline read: a crafter's {@code "triggered"} rides along with contents but is not itself content, so a
 * crafter carrying only state must not read as a recovered container.
 *
 * <p>The fields enumerated here are vanilla's own on this band. The carry-forward considers all of them for every block
 * entity in a re-written chunk, so a non-vanilla block entity that happened to use one of these key names could receive
 * a carried value; no vanilla block entity besides the crafter and the brewing stand writes any of the four state keys.
 */
enum CapturedBlockField {
    /** A block container's slots, written by {@link ContainerSink#merge} (chiseled bookshelves included). */
    ITEMS("Items", null, Shape.LIST),
    /** A lectern's book and the page it is open at, written by {@link LecternSink#merge}. */
    BOOK("Book", "Page", Shape.COMPOUND),
    /** A jukebox's disc and its song-start tick, written by the interaction holder merge. */
    RECORD_ITEM("RecordItem", "ticks_since_song_started", Shape.COMPOUND),
    /** A beehive's occupants, written by the interaction holder merge. */
    BEES("bees", null, Shape.LIST),
    /** A crafter's disabled input slots; vanilla writes an int array, empty when no slot is disabled. */
    DISABLED_SLOTS("disabled_slots", new IntArrayTag(new int[0])),
    /** Whether a crafter is powered; vanilla writes an int, zero when it is not. */
    TRIGGERED("triggered", IntTag.valueOf(0)),
    /** A brewing stand's remaining brew ticks; vanilla writes a short, zero when it is not brewing. */
    BREW_TIME("BrewTime", ShortTag.valueOf((short) 0)),
    /** A brewing stand's blaze-powder fuel; vanilla writes a byte, zero when it has none. */
    FUEL("Fuel", ByteTag.valueOf((byte) 0));

    /**
     * Iterating this instead of {@code values()} keeps the per-block-entity loops from cloning the backing array on
     * every call; the carry-forward runs them once per block entity of every re-written chunk.
     */
    private static final List<CapturedBlockField> FIELDS = List.of(values());

    /** How a field's tag is compared and copied, which is what decides its carry-forward rule. */
    private enum Shape {
        /** A list, where an empty one on the fresh side is treated as nothing captured. */
        LIST,
        /** A compound plus an optional sidecar value that only travels with it. */
        COMPOUND,
        /**
         * A scalar this mod can capture only from an opened menu. Vanilla writes these unconditionally, so a freshly
         * captured block entity always carries the key at the client's default rather than omitting it, and the
         * carry-forward has to test against that default rather than against absence.
         */
        VALUE
    }

    private final String key;
    private final @Nullable String sidecarKey;
    private final Shape shape;
    private final @Nullable Tag clientDefault;

    CapturedBlockField(String key, @Nullable String sidecarKey, Shape shape) {
        this.key = key;
        this.sidecarKey = sidecarKey;
        this.shape = shape;
        this.clientDefault = null;
    }

    CapturedBlockField(String key, Tag clientDefault) {
        this.key = key;
        this.sidecarKey = null;
        this.shape = Shape.VALUE;
        this.clientDefault = clientDefault;
    }

    /** Every field, in declaration order, without the array copy {@code values()} makes. */
    static List<CapturedBlockField> fields() {
        return FIELDS;
    }

    /** This field's vanilla NBT key. */
    String key() {
        return key;
    }

    /**
     * Whether this field is open-time menu state the open-time write copies from its stash holder, rather than content
     * some sink writes. The whitelist {@link ContainerMerge} copies from.
     */
    boolean openTimeState() {
        return shape == Shape.VALUE;
    }

    /**
     * Whether an open-time capture at a block entity's position makes the fresh side authoritative for this field, so
     * nothing carries over it. True for the open-time state keys, and for a container's contents unless the capture
     * unit is a single slot: a whole-container capture shows every slot at once, so an empty fresh list there is a
     * container seen to hold nothing rather than one nothing looked inside, and carrying the on-disk list over it would
     * put back the items the player watched leave. The single-slot case is {@link ChunkMerge#capturesPerSlot}, whose
     * fresh list is a fragment and still unions.
     */
    boolean capturesWholeOnOpen(boolean itemsBySlot) {
        return this == ITEMS ? !itemsBySlot : openTimeState();
    }

    /**
     * Whether {@code blockEntity} holds captured content in this field: a non-empty list, or a present compound. Always
     * false for open-time state, which accompanies content but is not content itself.
     */
    boolean holdsContent(CompoundTag blockEntity) {
        return switch (shape) {
            case LIST -> NbtMerge.isNonEmptyList(blockEntity, key);
            case COMPOUND -> blockEntity.get(key) instanceof CompoundTag;
            case VALUE -> false;
        };
    }

    /**
     * Carry this field from {@code disk} into {@code fresh} when the fresh side holds nothing this mod captured, by
     * this field's shape; true if a copy happened. {@code itemsBySlot} selects the per-slot union for a container whose
     * capture unit is a single slot rather than the whole container, where the fresh list is a fragment and the
     * all-or-nothing rule would drop every slot the fragment does not name.
     */
    boolean carry(CompoundTag disk, CompoundTag fresh, boolean itemsBySlot, int occupiedSlots) {
        if (this == ITEMS && itemsBySlot) {
            return NbtMerge.carryListBySlot(disk, fresh, key, occupiedSlots);
        }
        return switch (shape) {
            case LIST -> NbtMerge.carryList(disk, fresh, key);
            case COMPOUND -> NbtMerge.carryCompound(disk, fresh, key, sidecarKey);
            case VALUE -> NbtMerge.carryValue(disk, fresh, key, clientDefault);
        };
    }
}
