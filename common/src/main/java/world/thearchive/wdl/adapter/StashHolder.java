// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.jspecify.annotations.Nullable;

/**
 * Main-thread only, like the stashes themselves.
 */
final class StashHolder {
    private @Nullable CompoundTag tag;
    private byte @Nullable [] packed;
    private boolean packFailed;

    private StashHolder(CompoundTag tag) {
        this.tag = tag;
    }

    static StashHolder of(CompoundTag tag) {
        return new StashHolder(tag);
    }

    boolean isPacked() {
        return packed != null;
    }

    boolean shouldCompact() {
        return tag != null && !packFailed;
    }

    void compact() throws IOException {
        CompoundTag live = tag;
        if (live == null) {
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            NbtIo.writeCompressed(live, bytes);
        } catch (IOException e) {
            packFailed = true;
            throw e;
        }
        packed = bytes.toByteArray();
        tag = null;
    }

    CompoundTag unpack() throws IOException {
        CompoundTag live = tag;
        if (live != null) {
            return live;
        }
        byte[] bytes = packed;
        if (bytes == null) {
            throw new IOException("stash holder holds neither a tag nor packed bytes");
        }
        return NbtIo.readCompressed(new ByteArrayInputStream(bytes));
    }
}
