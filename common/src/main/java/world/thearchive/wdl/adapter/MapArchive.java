// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.MapHash;
import world.thearchive.wdl.core.MapManifest;

/**
 * The on-sight map archive: the live remap table that resolves a server's session-local map id to a content-stable
 * archive id and streams each received map's data to the injected {@link MapDataSink} at its first imaged sighting, so
 * a crashed capture keeps the maps it already saw. A map in a chest whose chunk flushes mid-roam is captured before the
 * holder drains rather than being lost at finish (the unload fix). It wraps a {@link MapManifest} (the persisted
 * hash-to-id table and the shared monotonic counter), memoizes each session id's resolution, and streams each archive
 * id's data exactly once.
 *
 * <p>The resolution is imaged-sticky and imageless-upgradeable: an id first seen without colors (a chest-only map)
 * takes a fresh counter id, but if the same id is later seen with colors (carried, so the server sent them) it upgrades
 * to the content-dedup archive id, so a carried map always renders. The remap rewrite is single-pass: each captured
 * holder is a fresh serialized copy and is remapped exactly once, at the point it is consumed.
 *
 * <p>Band-agnostic: the three hashed fields are read off the serialized inner {@code "data"} tag through the
 * band-stable {@code Codec}/{@code NbtOps} pattern ({@link MapIdCollector}'s precedent), so hashing adds no per-band
 * coupling across the 1.21.5 codec cut. {@code locked} and the centers are excluded from the hash.
 */
final class MapArchive {
    /** Resolves a session-local map id to its serialized inner data tag, or null if the colors were not received. */
    @FunctionalInterface
    public interface ImageResolver {
        @Nullable
        Tag resolve(int sessionId);
    }

    /**
     * Receives each map's serialized inner data tag exactly once, at its first imaged sighting (the on-sight stream to
     * disk). The id is the archive id in remap mode and the verbatim server id in off mode. Must not throw: the id is
     * marked imaged at the fire, so a throw would leave it marked with nothing streamed this session.
     */
    @FunctionalInterface
    public interface MapDataSink {
        void accept(int archiveId, Tag dataTag);
    }

    private final MapManifest manifest;
    private final ImageResolver resolver;
    private final boolean remap;
    private int identityHighWater;
    private final Map<Integer, Integer> archiveIdBySessionId = new LinkedHashMap<>();
    private final MapDataSink dataSink;
    // The ids already streamed once (archive ids in remap mode, verbatim server ids in off mode). The
    // write-once and stickiness gate; never exposed, so the primitive set stays unboxed.
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

    /**
     * The archive id for {@code sessionId}: memoized so repeated occurrences resolve consistently. An imaged resolution
     * is sticky and dedups by content hash; an imageless one takes a fresh counter id but upgrades if the same id is
     * later seen imaged. The image data is streamed exactly once per archive id.
     */
    public int archiveIdFor(int sessionId) {
        if (!remap) {
            return identityIdFor(sessionId);
        }
        Integer cached = archiveIdBySessionId.get(sessionId);
        if (cached != null && imagedIds.contains(cached.intValue())) {
            return cached; // already imaged: sticky and idempotent
        }
        Tag dataTag = resolver.resolve(sessionId);
        if (dataTag != null) {
            int archiveId = manifest.lookupOrInsert(hashOf(dataTag));
            if (imagedIds.add(archiveId)) {
                dataSink.accept(archiveId, dataTag); // first image of this content: stream it now
            }
            archiveIdBySessionId.put(sessionId, archiveId);
            return archiveId;
        }
        if (cached != null) {
            return cached; // still imageless: keep the id already allocated for it
        }
        int archiveId = manifest.allocateImageless();
        archiveIdBySessionId.put(sessionId, archiveId);
        return archiveId;
    }

    /**
     * Off-mode resolution: the archive id is the original session id. The image is still streamed on-sight (keyed by
     * the original id), and the id raises the idcounts high-water even when imageless, so a reopened world never
     * re-issues it. Re-resolves an id first seen imageless so a later carried copy still images it.
     */
    private int identityIdFor(int sessionId) {
        identityHighWater = Math.max(identityHighWater, sessionId);
        if (!imagedIds.contains(sessionId)) {
            Tag dataTag = resolver.resolve(sessionId);
            if (dataTag != null) {
                imagedIds.add(sessionId); // imaged sightings only: a null resolve must stay upgradeable
                dataSink.accept(sessionId, dataTag);
            }
        }
        return sessionId;
    }

    /**
     * Rewrite every map id referenced by {@code holder}'s {@code listKey} list to its archive id, recursing into nested
     * shulkers and bundles, and stream each first-imaged map's data on the way (the on-sight stream). Single-pass: call
     * exactly once per captured holder.
     */
    public void remap(CompoundTag holder, String listKey) {
        MapIdRemap.remapFromItemList(holder, listKey, this::archiveIdFor);
    }

    /**
     * Remap (and on-sight stream) the maps referenced by a single entity-borne item (an item frame's or a dropped item
     * entity's {@code "Item"}). Single-pass: call exactly once per captured entity item.
     */
    public void remapItem(CompoundTag item) {
        MapIdRemap.remapItem(item, this::archiveIdFor);
    }

    /**
     * The finalize-time idcounts tag, floored at the counter high-water so the reopened world's allocator issues the
     * next id above every captured id, or null when no id was referenced or seeded this session. The
     * {@code map_<id>.dat} files themselves stream through the sink at first image.
     */
    public @Nullable Tag idCountsTag() {
        int highWater = remap ? manifest.highestAssignedId() : identityHighWater;
        return highWater >= 0 ? MapDataWriter.serializeIdCounts(highWater) : null;
    }

    /**
     * The content hash of a serialized inner map data tag: SHA-256 over the colors, scale and dimension read
     * band-agnostically through {@code Codec}/{@code NbtOps}; {@code locked} and the centers are excluded.
     */
    static String hashOf(Tag dataTag) {
        if (!(dataTag instanceof CompoundTag data)) {
            return MapHash.of(new byte[0], 0, "");
        }
        return MapHash.of(readColors(data), readByte(data, "scale"), readString(data, "dimension"));
    }

    private static byte[] readColors(CompoundTag data) {
        Tag tag = data.get("colors");
        if (tag == null) {
            return new byte[0];
        }
        return Codec.BYTE_BUFFER.parse(NbtOps.INSTANCE, tag).result().map(MapArchive::toArray).orElse(new byte[0]);
    }

    private static byte[] toArray(ByteBuffer buffer) {
        ByteBuffer view = buffer.duplicate();
        byte[] out = new byte[view.remaining()];
        view.get(out);
        return out;
    }

    private static int readByte(CompoundTag data, String key) {
        Tag tag = data.get(key);
        if (tag == null) {
            return 0; // the 1.21.5 codec form omits a defaulted scale
        }
        return Codec.BYTE.parse(NbtOps.INSTANCE, tag).result().map(Byte::intValue).orElse(0);
    }

    private static String readString(CompoundTag data, String key) {
        Tag tag = data.get(key);
        if (tag == null) {
            return "";
        }
        return Codec.STRING.parse(NbtOps.INSTANCE, tag).result().orElse("");
    }
}
