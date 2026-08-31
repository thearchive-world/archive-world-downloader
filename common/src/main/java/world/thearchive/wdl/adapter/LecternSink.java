// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * Per-band lectern-book-capture axis: serialize a book placed on a lectern (with the reading page) into the vanilla
 * lectern block-entity {@code "Book"}/{@code "Page"} NBT and merge it into a captured lectern block-entity tag. A
 * lectern's book never reaches the client's persisted {@code LecternBlockEntity} ({@code LecternBlockEntity} overrides
 * neither {@code getUpdatePacket} nor {@code getUpdateTag}, so the chunk packet carries no book and there is no
 * block-entity-data sync of one); it reaches the client only through the open lectern read menu's slot 0. So a captured
 * chunk's lectern is structurally present but book-less until this axis merges in the book lifted from the open menu.
 *
 * <p>The live step ({@link #captureBook(ItemStack, int)}) serializes the book lifted from the open menu. The pure step
 * ({@link #merge(CompoundTag, CompoundTag)}) sets {@code "Book"}/{@code "Page"} on a copy of an already-captured
 * lectern block-entity tag.
 *
 * <p>Both methods are per-band.
 */
public interface LecternSink {
    /**
     * Serialize {@code book} (the open menu's slot-0 stack) and {@code page} (the reading page) into a holder
     * {@link CompoundTag} carrying {@code "Book"} + {@code "Page"} (an int). Server-free. Assumes a non-empty book; the
     * caller drops an empty slot 0 before calling this.
     */
    CompoundTag captureBook(ItemStack book, int page);

    /**
     * Set {@code "Book"} + {@code "Page"} on a copy of {@code lecternBlockEntityTag} from {@code capturedBookHolder},
     * leaving every other field intact (id, x/y/z, ...): the captured chunk's lectern gains its real book with no
     * clobber. The input tag is not mutated.
     */
    CompoundTag merge(CompoundTag lecternBlockEntityTag, CompoundTag capturedBookHolder);
}
