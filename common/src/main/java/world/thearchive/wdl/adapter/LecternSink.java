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
 * chunk's lectern is structurally present but book-less until this axis merges in the book the session lifted from the
 * open menu.
 *
 * <p>Mirrors {@link ContainerSink}'s two-step seam, writing {@code "Book"}/{@code "Page"} instead of {@code "Items"}.
 * The live step ({@link #captureBook(ItemStack, int)}) serializes the book the session lifted from the open menu; it is
 * client-coupled. The pure step ({@link #merge(CompoundTag, CompoundTag)}) sets {@code "Book"}/{@code "Page"} on a copy
 * of an already-captured lectern block-entity tag and is what the headless round-trip exercises.
 *
 * <p>Per-band in full (both methods): {@code captureBook} serializes via the band's codec layer (1.21.11 takes a
 * {@code ValueOutput}/{@code TagValueOutput}, the &le;1.21.8 sub-band takes the older {@code CompoundTag} form), and
 * {@code merge} reads the holder with {@code getCompoundOrEmpty}/ {@code getIntOr}, which are 1.21.10+ accessors (the
 * &le;1.21.8 band re-spells them). The on-disk {@code "Book"}/{@code "Page"} format the band's own block entity reads
 * back is identical across bands. This matches {@link ContainerSink}, whose {@code merge} likewise uses a 1.21.10+
 * accessor and also lives in the per-band class.
 */
public interface LecternSink {
    /**
     * Serialize {@code book} (the open menu's slot-0 stack) and {@code page} (the reading page) into a holder
     * {@link CompoundTag} carrying {@code "Book"} (the stack via {@code ItemStack.CODEC}) + {@code "Page"} (an int),
     * exactly as {@code LecternBlockEntity.saveAdditional} writes them. Server-free: a discarding problem reporter
     * replaces the world-scoped one, mirroring {@link ContainerSink}'s lift. Assumes a non-empty book
     * ({@code ItemStack.CODEC} does not encode {@code ItemStack.EMPTY}); the caller drops an empty slot 0 before
     * calling this.
     */
    CompoundTag captureBook(ItemStack book, int page);

    /**
     * Set {@code "Book"} + {@code "Page"} on a copy of {@code lecternBlockEntityTag} from {@code capturedBookHolder},
     * leaving every other field intact (id, x/y/z, ...): the captured chunk's lectern gains its real book with no
     * clobber. The input tag is not mutated.
     */
    CompoundTag merge(CompoundTag lecternBlockEntityTag, CompoundTag capturedBookHolder);
}
