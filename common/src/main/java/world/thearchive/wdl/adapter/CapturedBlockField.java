// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.google.common.collect.ImmutableList;
import java.util.List;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagShort;
import org.jspecify.annotations.Nullable;

/**
 * The single list two consumers read, so neither can drift from the other: {@link ContainerMerge} writes the open-time
 * state keys onto a block entity beside the sink's {@code "Items"}, and {@link ChunkMerge} carries these keys forward
 * from an on-disk chunk into a freshly re-captured one, except where that write captured the field itself (see
 * {@link #capturesWholeOnOpen}). A key written by one visit and not carried by the next, at a position the next visit
 * captured nothing for, is data the mod captured and then dropped, so the two must enumerate the same set; adding a
 * constant here reaches both.
 *
 * <p>The state subset is a whitelist on purpose, and stays one: it fails closed, so an internal holder key
 * ({@code wdl_block_entity_id}) can never leak to disk through the open-time write.
 *
 * <p>Content versus state decides {@link #holdsContent}, which is what {@link ChunkMerge#hasCapturedContent} and the
 * resume outline read: a state key rides along with contents but is not itself content, so a block entity carrying only
 * state must not read as a recovered container.
 */
enum CapturedBlockField {
    ITEMS("Items", null, Shape.LIST),
    BOOK("Book", "Page", Shape.COMPOUND),
    RECORD_ITEM("RecordItem", "IsPlaying", Shape.COMPOUND),
    BEES("Bees", null, Shape.LIST),
    DISABLED_SLOTS("disabled_slots", new NBTTagIntArray(new int[0])),
    TRIGGERED("triggered", new NBTTagInt(0)),
    /** A brewing stand's remaining brew ticks; vanilla writes a short, zero when it is not brewing. */
    BREW_TIME("BrewTime", new NBTTagShort((short) 0)),
    /** A brewing stand's blaze-powder fuel; vanilla writes a byte, zero when it has none. */
    FUEL("Fuel", new NBTTagByte((byte) 0));

    /**
     * Iterating this instead of {@code values()} keeps the per-block-entity loops from cloning the backing array on
     * every call.
     */
    private static final List<CapturedBlockField> FIELDS = ImmutableList.copyOf(values());

    private enum Shape {
        LIST,
        COMPOUND,
        /**
         * A value only an opened menu exposes. Vanilla writes the brewing stand's two ({@code BrewTime} and
         * {@code Fuel}) unconditionally, so a freshly captured brewing stand always carries each at the client's
         * default rather than omitting it, and the carry-forward has to test against that default rather than against
         * absence.
         */
        VALUE
    }

    private final String key;
    private final @Nullable String sidecarKey;
    private final Shape shape;
    private final @Nullable NBTBase clientDefault;

    CapturedBlockField(String key, @Nullable String sidecarKey, Shape shape) {
        this.key = key;
        this.sidecarKey = sidecarKey;
        this.shape = shape;
        this.clientDefault = null;
    }

    CapturedBlockField(String key, NBTBase clientDefault) {
        this.key = key;
        this.sidecarKey = null;
        this.shape = Shape.VALUE;
        this.clientDefault = clientDefault;
    }

    static List<CapturedBlockField> fields() {
        return FIELDS;
    }

    String key() {
        return key;
    }

    boolean openTimeState() {
        return shape == Shape.VALUE;
    }

    /**
     * Whether an open-time capture at a block entity's position makes the fresh side authoritative for this field, so
     * nothing carries over it. True for the open-time state keys, and for a container's contents unless the capture
     * unit is a single slot: a whole-container capture shows every slot at once, so an empty fresh list there is a
     * container seen to hold nothing rather than one nothing looked inside, and carrying the on-disk list over it would
     * put back the items the player watched leave.
     */
    boolean capturesWholeOnOpen(boolean itemsBySlot) {
        return this == ITEMS ? !itemsBySlot : openTimeState();
    }

    boolean holdsContent(NBTTagCompound blockEntity) {
        switch (shape) {
            case LIST:
                return NbtMerge.isNonEmptyList(blockEntity, key);
            case COMPOUND:
                return blockEntity.getTag(key) instanceof NBTTagCompound;
            case VALUE:
                return false;
            default:
                throw new IncompatibleClassChangeError();
        }
    }

    /**
     * {@code itemsBySlot} selects the per-slot union for a container whose capture unit is a single slot rather than
     * the whole container, where the fresh list is a fragment and the all-or-nothing rule would drop every slot the
     * fragment does not name.
     */
    boolean carry(NBTTagCompound disk, NBTTagCompound fresh, boolean itemsBySlot, int occupiedSlots) {
        if (this == ITEMS && itemsBySlot) {
            return NbtMerge.carryListBySlot(disk, fresh, key, occupiedSlots);
        }
        switch (shape) {
            case LIST:
                return NbtMerge.carryList(disk, fresh, key);
            case COMPOUND:
                return NbtMerge.carryCompound(disk, fresh, key, sidecarKey);
            case VALUE:
                return NbtMerge.carryValue(disk, fresh, key, clientDefault);
            default:
                throw new IncompatibleClassChangeError();
        }
    }
}
