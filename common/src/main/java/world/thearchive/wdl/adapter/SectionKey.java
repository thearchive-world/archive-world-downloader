// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.util.math.BlockPos;

/**
 * The section-coordinate long packing the outline draw-set keys its buckets by, replacing {@code net.minecraft.core
 * .SectionPos} (a 1.14 addition absent below that band). The producer ({@link OutlineTracker}) packs a container's
 * section with {@link #blockToSection}/{@link #asLong}, and the consumer (the per-band outline renderer) unpacks it
 * with {@link #x}/{@link #y}/{@link #z} then {@link #sectionToBlockCoord}; the two must share this class so the encode
 * and decode cannot drift. The layout is a self-contained 22/20/22-bit scheme (x in the high 22 bits, z in the middle
 * 22, y in the low 20), sign-extended on read, so section coordinates across the full world range round-trip.
 */
public final class SectionKey {
    private static final int Z_BITS = 22;
    private static final int Y_BITS = 20;
    private static final int Z_SHIFT = Y_BITS;
    private static final int X_SHIFT = Y_BITS + Z_BITS;

    private SectionKey() {}

    /** Pack section coordinates {@code (x, y, z)} into the bucket key. */
    public static long asLong(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) << X_SHIFT | ((long) z & 0x3FFFFF) << Z_SHIFT | (long) y & 0xFFFFF;
    }

    /** The section key covering a packed {@link BlockPos#toLong() block key} (block coordinates right-shifted by 4). */
    public static long blockToSection(long blockKey) {
        BlockPos pos = BlockPos.fromLong(blockKey);
        return asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }

    /** The section x coordinate packed into {@code key}. */
    public static int x(long key) {
        // x occupies the top bits, so the arithmetic shift already sign-extends it; y and z need their left shift.
        return (int) (key >> X_SHIFT);
    }

    /** The section y coordinate packed into {@code key}. */
    public static int y(long key) {
        return (int) (key << 64 - Y_BITS >> 64 - Y_BITS);
    }

    /** The section z coordinate packed into {@code key}. */
    public static int z(long key) {
        return (int) (key << 64 - Z_SHIFT - 22 >> 64 - 22);
    }

    /** The block coordinate at the low corner of section coordinate {@code sectionCoord} (left-shift by 4). */
    public static int sectionToBlockCoord(int sectionCoord) {
        return sectionCoord << 4;
    }
}
