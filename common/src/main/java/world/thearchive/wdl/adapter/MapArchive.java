// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.MapHash;
import world.thearchive.wdl.core.MapManifest;

/**
 * In remap mode the resolution is imaged-sticky and imageless-upgradeable: an id first seen without colors takes a
 * fresh counter id, but if the same id is later seen with colors it upgrades to the content-dedup archive id, so a
 * carried map renders.
 */
final class MapArchive {
    @FunctionalInterface
    public interface ImageResolver {
        @Nullable
        NBTBase resolve(int sessionId);
    }

    /**
     * Must not throw: the id is marked imaged at the fire, so a throw would leave it marked with nothing streamed this
     * session.
     */
    @FunctionalInterface
    public interface MapDataSink {
        void accept(int archiveId, NBTBase dataTag);
    }

    private final MapManifest manifest;
    private final ImageResolver resolver;
    private final boolean remap;
    private int identityHighWater;
    private final Map<Integer, Integer> archiveIdBySessionId = new LinkedHashMap<>();
    private final MapDataSink dataSink;
    private final IntOpenHashSet imagedIds = new IntOpenHashSet();

    public MapArchive(MapManifest manifest, ImageResolver resolver, MapDataSink dataSink) {
        this(manifest, resolver, dataSink, true, -1);
    }

    public MapArchive(MapManifest manifest, ImageResolver resolver, MapDataSink dataSink, boolean remap,
            int identityFloor) {
        this.manifest = manifest;
        this.resolver = resolver;
        this.dataSink = dataSink;
        this.remap = remap;
        this.identityHighWater = identityFloor;
    }

    public MapManifest manifest() {
        return manifest;
    }

    public int archiveIdFor(int sessionId) {
        if (!remap) {
            return identityIdFor(sessionId);
        }
        Integer cached = archiveIdBySessionId.get(sessionId);
        if (cached != null && imagedIds.contains(cached.intValue())) {
            return cached;
        }
        NBTBase dataTag = resolver.resolve(sessionId);
        if (dataTag != null) {
            int archiveId = manifest.lookupOrInsert(hashOf(dataTag));
            if (imagedIds.add(archiveId)) {
                dataSink.accept(archiveId, dataTag);
            }
            archiveIdBySessionId.put(sessionId, archiveId);
            return archiveId;
        }
        if (cached != null) {
            return cached;
        }
        int archiveId = manifest.allocateImageless();
        archiveIdBySessionId.put(sessionId, archiveId);
        return archiveId;
    }

    /**
     * The id raises the idcounts high-water even when imageless, so the reopened world of a finished download never
     * re-issues it. Re-resolves an id first seen imageless so a later carried copy still images it.
     */
    private int identityIdFor(int sessionId) {
        identityHighWater = Math.max(identityHighWater, sessionId);
        if (!imagedIds.contains(sessionId)) {
            NBTBase dataTag = resolver.resolve(sessionId);
            if (dataTag != null) {
                imagedIds.add(sessionId);
                dataSink.accept(sessionId, dataTag);
            }
        }
        return sessionId;
    }

    /**
     * Single-pass in remap mode: call exactly once per captured holder.
     */
    public void remap(NBTTagCompound holder, String listKey) {
        MapIdRemap.remapFromItemList(holder, listKey, this::archiveIdFor);
    }

    /**
     * Single-pass in remap mode: call exactly once per captured entity item.
     */
    public void remapItem(NBTTagCompound item) {
        MapIdRemap.remapItem(item, this::archiveIdFor);
    }

    public @Nullable NBTBase idCountsTag() {
        int highWater = remap ? manifest.highestAssignedId() : identityHighWater;
        return highWater >= 0 ? MapDataWriter.serializeIdCounts(highWater) : null;
    }

    static String hashOf(NBTBase dataTag) {
        if (!(dataTag instanceof NBTTagCompound)) {
            return MapHash.of(new byte[0], 0, "");
        }
        NBTTagCompound data = (NBTTagCompound) dataTag;
        return MapHash.of(data.getByteArray("colors"), data.getByte("scale"),
                String.valueOf(data.getInteger("dimension")));
    }
}
