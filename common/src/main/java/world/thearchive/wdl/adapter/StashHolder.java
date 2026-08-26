// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import org.jspecify.annotations.Nullable;

/**
 * One stashed open-time holder, either the live tag (fresh, still last-seen-wins overwritable) or its gzip-packed NBT
 * bytes once compacted. The stash retains every holder until its chunk flushes, and a shulker-filled container's tag is
 * hundreds of kilobytes of scattered tag objects, so a dense storage room session holds hundreds of megabytes as trees;
 * packed to one byte array each, the same session holds a few tens of kilobytes per container and the object count
 * collapses with it. Main-thread only, like the stashes themselves.
 */
final class StashHolder {
    private @Nullable NBTTagCompound tag;
    private byte @Nullable [] packed;
    private boolean packFailed;

    private StashHolder(NBTTagCompound tag) {
        this.tag = tag;
    }

    static StashHolder of(NBTTagCompound tag) {
        return new StashHolder(tag);
    }

    boolean isPacked() {
        return packed != null;
    }

    /** Whether {@link #compact} should be attempted: still a live tree, and no earlier pack has failed. */
    boolean shouldCompact() {
        return tag != null && !packFailed;
    }

    /**
     * Pack the live tag to gzip bytes and drop the tree; a no-op when already packed. A failed pack keeps the live tree
     * (the data stays intact) and retires this holder from packing, so a failure cannot be retried every tick against
     * the same bytes.
     */
    void compact() throws IOException {
        NBTTagCompound live = tag;
        if (live == null) {
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            CompressedStreamTools.writeCompressed(live, bytes);
        } catch (IOException e) {
            packFailed = true;
            throw e;
        }
        packed = bytes.toByteArray();
        tag = null;
    }

    /** The holder tag: the live tree as-is, or the packed bytes decoded (the holder stays packed). */
    NBTTagCompound unpack() throws IOException {
        NBTTagCompound live = tag;
        if (live != null) {
            return live;
        }
        byte[] bytes = packed;
        if (bytes == null) {
            throw new IOException("stash holder holds neither a tag nor packed bytes");
        }
        return CompressedStreamTools.readCompressed(new ByteArrayInputStream(bytes));
    }
}
