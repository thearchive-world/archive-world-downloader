// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;

import com.google.common.collect.ImmutableList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ChunkCodecImpl;
import world.thearchive.wdl.adapter.impl.LecternSinkImpl;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The lectern-merge synergy re-capture unlocks: a lectern placed AFTER its chunk was first captured has no block entity
 * in the snapshot-once tag, so the open-time book stash has nothing to merge onto. Re-capturing the chunk re-encodes
 * its block entities, so the placed lectern's block entity now exists and {@link ContainerMerge#mergeLecternChunkStash}
 * lands the stashed {@code Book}/{@code Page} on it. The book comes only from the stash, never the re-encode (the live
 * client lectern BE never holds a book, so a re-encode can never blank what merge sets), and the merge runs once per
 * chunk at flush, so repeated re-capture before that single flush cannot double-count or blank the contents.
 */
class LecternRecaptureSynergyTest {
    private static final int LECTERN_X = 4;
    private static final int LECTERN_Y = 65;
    private static final int LECTERN_Z = 6;

    private final ChunkCodec codec = new ChunkCodecImpl();
    private final LecternSink sink = new LecternSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /** A written book with a custom {@code title}, below 1.20.5's pre-component {@code title}/{@code pages} NBT. */
    private static ItemStack writtenBook(String title) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = book.getOrCreateTag();
        tag.putString("title", title);
        tag.putString("author", "Author");
        tag.putInt("generation", 0);
        tag.putBoolean("resolved", true);
        ListTag pages = new ListTag();
        pages.add(new StringTag(Component.Serializer.toJson(new TextComponent("page"))));
        tag.put("pages", pages);
        return book;
    }

    private CompoundTag stashBook(String title, int page) {
        return sink.captureBook(writtenBook(title), page);
    }

    private static String mergedTitle(CompoundTag chunkTag) {
        ListTag blockEntities = chunkTag.getCompound("Level").getList("TileEntities", 10);
        CompoundTag lectern = blockEntities.getCompound(0);
        ItemStack back = ItemStack.of(lectern.getCompound("Book"));
        assertTrue(!back.isEmpty(), "the re-captured lectern carries a decodable Book");
        return back.getTag().getString("title");
    }

    @Test
    void withoutRecaptureNothingMergesOntoTheMissingLectern() {
        // The snapshot-once gap: the chunk was captured before the lectern was placed, so its tag has none.
        CompoundTag snapshotOnceTag = codec.encode(SyntheticChunks.full(false), false);

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(new BlockPos(LECTERN_X, LECTERN_Y, LECTERN_Z), stashBook("Placed Late", 0));

        int merged = ContainerMerge.mergeLecternChunkStash(sink, snapshotOnceTag,
                new ChunkPos(new BlockPos(LECTERN_X, LECTERN_Y, LECTERN_Z)), stash).merged();

        assertEquals(0, merged, "with no re-captured lectern block entity there is nothing to merge onto");
    }

    @Test
    @Disabled("lecterns are a 1.14 block entity, absent at 1.13.2; a re-captured lectern block entity cannot be built "
            + "here, so this synergy is a documented limit at this band")
    void reCapturingThePlacedLecternLetsTheStashMergeOntoIt() {
        // After re-capture the chunk's block entities include the placed lectern, so the merge lands.
        ChunkSnapshotSource snapshot = SyntheticChunks.fullWithBlockEntities(false,
                ImmutableList.of(blockEntity("minecraft:lectern", LECTERN_X, LECTERN_Y, LECTERN_Z)));
        CompoundTag recapturedTag = codec.encode(snapshot, false);

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(new BlockPos(LECTERN_X, LECTERN_Y, LECTERN_Z), stashBook("Recaptured", 2));

        int merged = ContainerMerge.mergeLecternChunkStash(sink, recapturedTag,
                new ChunkPos(new BlockPos(LECTERN_X, LECTERN_Y, LECTERN_Z)), stash).merged();

        assertEquals(1, merged, "the re-captured lectern block entity gives the stash something to merge onto");
        assertEquals("Recaptured", mergedTitle(recapturedTag), "the stashed book lands on the re-captured lectern");
    }

    @Test
    @Disabled("lecterns are a 1.14 block entity, absent at 1.13.2; a re-captured lectern block entity cannot be built "
            + "here, so this synergy is a documented limit at this band")
    void repeatedRecaptureBeforeFlushMergesContentsExactlyOnce() {
        // Re-capture re-encodes the chunk many times while it is hot; each fresh tag carries the (book-less)
        // lectern block entity. The book comes from the stash and merges only at the single flush, so the
        // count and contents are stable no matter how many re-encodes preceded it.
        CompoundTag latestRecapture = null;
        for (int reencode = 0; reencode < 3; reencode++) {
            ChunkSnapshotSource snapshot = SyntheticChunks.fullWithBlockEntities(false,
                    ImmutableList.of(blockEntity("minecraft:lectern", LECTERN_X, LECTERN_Y, LECTERN_Z)));
            latestRecapture = codec.encode(snapshot, false);
        }

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(new BlockPos(LECTERN_X, LECTERN_Y, LECTERN_Z), stashBook("Stable", 1));

        ChunkPos chunkPos = new ChunkPos(new BlockPos(LECTERN_X, LECTERN_Y, LECTERN_Z));
        int merged = ContainerMerge.mergeLecternChunkStash(sink, latestRecapture, chunkPos, stash).merged();
        assertEquals(1, merged, "the single flush merges the stash exactly once");
        assertTrue(stash.isEmpty(), "the stash entry is drained at flush, so it cannot merge again");

        // A second flush-time merge (the stash now drained) adds nothing: re-capture never re-stocks the stash.
        int mergedAgain = ContainerMerge.mergeLecternChunkStash(sink, latestRecapture, chunkPos, stash).merged();
        assertEquals(0, mergedAgain, "a drained stash contributes no further merges, so the count cannot double");
        assertEquals("Stable", mergedTitle(latestRecapture), "the contents remain intact");
    }
}
