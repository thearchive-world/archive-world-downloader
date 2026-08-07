// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.TagValueInput;
import org.junit.jupiter.api.BeforeAll;
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

    private static RegistryAccess registries;
    private final ChunkCodec codec = new ChunkCodecImpl();
    private final LecternSink sink = new LecternSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen();
    }

    private CompoundTag stashBook(String title, int page) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough(title), "Author", 0,
                List.of(Filterable.passThrough(Component.literal("page"))), true));
        return sink.captureBook(book, page, registries);
    }

    private static String mergedTitle(CompoundTag chunkTag) {
        ListTag blockEntities = chunkTag.getListOrEmpty("block_entities");
        CompoundTag lectern = blockEntities.getCompoundOrEmpty(0);
        Optional<ItemStack> back = TagValueInput.create(ProblemReporter.DISCARDING, registries, lectern)
                .read("Book", ItemStack.CODEC);
        assertTrue(back.isPresent(), "the re-captured lectern carries a decodable Book");
        return back.get().get(DataComponents.WRITTEN_BOOK_CONTENT).title().raw();
    }

    @Test
    void withoutRecaptureNothingMergesOntoTheMissingLectern() {
        // The snapshot-once gap: the chunk was captured before the lectern was placed, so its tag has none.
        CompoundTag snapshotOnceTag = codec.encode(SyntheticChunks.full(registries, false), registries, false);

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(new BlockPos(LECTERN_X, LECTERN_Y, LECTERN_Z), stashBook("Placed Late", 0));

        int merged = ContainerMerge.mergeLecternChunkStash(sink, snapshotOnceTag,
                ChunkPos.containing(new BlockPos(LECTERN_X, LECTERN_Y, LECTERN_Z)), stash).merged();

        assertEquals(0, merged, "with no re-captured lectern block entity there is nothing to merge onto");
    }

    @Test
    void reCapturingThePlacedLecternLetsTheStashMergeOntoIt() {
        // After re-capture the chunk's block entities include the placed lectern, so the merge lands.
        ChunkSnapshotSource snapshot = SyntheticChunks.fullWithBlockEntities(registries, false,
                List.of(blockEntity("minecraft:lectern", LECTERN_X, LECTERN_Y, LECTERN_Z)));
        CompoundTag recapturedTag = codec.encode(snapshot, registries, false);

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(new BlockPos(LECTERN_X, LECTERN_Y, LECTERN_Z), stashBook("Recaptured", 2));

        int merged = ContainerMerge.mergeLecternChunkStash(sink, recapturedTag,
                ChunkPos.containing(new BlockPos(LECTERN_X, LECTERN_Y, LECTERN_Z)), stash).merged();

        assertEquals(1, merged, "the re-captured lectern block entity gives the stash something to merge onto");
        assertEquals("Recaptured", mergedTitle(recapturedTag), "the stashed book lands on the re-captured lectern");
    }

    @Test
    void repeatedRecaptureBeforeFlushMergesContentsExactlyOnce() {
        // Re-capture re-encodes the chunk many times while it is hot; each fresh tag carries the (book-less)
        // lectern block entity. The book comes from the stash and merges only at the single flush, so the
        // count and contents are stable no matter how many re-encodes preceded it.
        CompoundTag latestRecapture = null;
        for (int reencode = 0; reencode < 3; reencode++) {
            ChunkSnapshotSource snapshot = SyntheticChunks.fullWithBlockEntities(registries, false,
                    List.of(blockEntity("minecraft:lectern", LECTERN_X, LECTERN_Y, LECTERN_Z)));
            latestRecapture = codec.encode(snapshot, registries, false);
        }

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(new BlockPos(LECTERN_X, LECTERN_Y, LECTERN_Z), stashBook("Stable", 1));

        ChunkPos chunkPos = ChunkPos.containing(new BlockPos(LECTERN_X, LECTERN_Y, LECTERN_Z));
        int merged = ContainerMerge.mergeLecternChunkStash(sink, latestRecapture, chunkPos, stash).merged();
        assertEquals(1, merged, "the single flush merges the stash exactly once");
        assertTrue(stash.isEmpty(), "the stash entry is drained at flush, so it cannot merge again");

        // A second flush-time merge (the stash now drained) adds nothing: re-capture never re-stocks the stash.
        int mergedAgain = ContainerMerge.mergeLecternChunkStash(sink, latestRecapture, chunkPos, stash).merged();
        assertEquals(0, mergedAgain, "a drained stash contributes no further merges, so the count cannot double");
        assertEquals("Stable", mergedTitle(latestRecapture), "the contents remain intact");
    }
}
