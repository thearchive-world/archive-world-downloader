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
 * Main-thread only, like the stashes themselves.
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

    boolean shouldCompact() {
        return tag != null && !packFailed;
    }

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
