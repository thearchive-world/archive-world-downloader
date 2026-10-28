// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;

import world.thearchive.wdl.adapter.WdlRegionStorage;

public final class RegionRoundTrip {
    private RegionRoundTrip() {}

    public static WdlRegionStorage open(Path directory) {
        return new WdlRegionStorage(directory.toFile());
    }

    /**
     * Write {@code tag} at {@code pos}, drain to disk, then read it back through a <em>fresh</em> storage instance.
     */
    public static NBTTagCompound writeThenRead(Path directory, ChunkPos pos, NBTTagCompound tag) {
        try (WdlRegionStorage writer = open(directory)) {
            writer.write(pos, tag);
        } catch (IOException e) {
            throw new RuntimeException("failed writing region chunk " + pos, e);
        }
        try (WdlRegionStorage reader = open(directory)) {
            return Optional.ofNullable(reader.read(pos))
                    .orElseThrow(() -> new IllegalStateException("no chunk read back at " + pos));
        } catch (IOException e) {
            throw new RuntimeException("failed reading region chunk " + pos, e);
        }
    }
}
