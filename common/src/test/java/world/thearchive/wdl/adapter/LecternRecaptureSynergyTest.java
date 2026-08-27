// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;

import com.google.common.collect.ImmutableList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
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

    /** A written book with a custom {@code title}. */
    private static ItemStack writtenBook(String title) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.setTagCompound(new NBTTagCompound());
        NBTTagCompound tag = book.getTagCompound();
        tag.setString("title", title);
        tag.setString("author", "Author");
        tag.setInteger("generation", 0);
        tag.setBoolean("resolved", true);
        NBTTagList pages = new NBTTagList();
        pages.appendTag(new NBTTagString(ITextComponent.Serializer.componentToJson(new TextComponentString("page"))));
        tag.setTag("pages", pages);
        return book;
    }

    private NBTTagCompound stashBook(String title, int page) {
        return sink.captureBook(writtenBook(title), page);
    }

    private static String mergedTitle(NBTTagCompound chunkTag) {
        NBTTagList blockEntities = chunkTag.getCompoundTag("Level").getTagList("TileEntities", 10);
        NBTTagCompound lectern = blockEntities.getCompoundTagAt(0);
        ItemStack back = new ItemStack(lectern.getCompoundTag("Book"));
        assertTrue(!back.isEmpty(), "the re-captured lectern carries a decodable Book");
        return back.getTagCompound().getString("title");
    }

    @Test
    void withoutRecaptureNothingMergesOntoTheMissingLectern() {
        // The snapshot-once gap: the chunk was captured before the lectern was placed, so its tag has none.
        NBTTagCompound snapshotOnceTag = codec.encode(SyntheticChunks.full(false), false);

        Map<BlockPos, NBTTagCompound> stash = new LinkedHashMap<>();
        stash.put(new BlockPos(LECTERN_X, LECTERN_Y, LECTERN_Z), stashBook("Placed Late", 0));

        int merged = ContainerMerge.mergeLecternChunkStash(sink, snapshotOnceTag,
                new ChunkPos(new BlockPos(LECTERN_X, LECTERN_Y, LECTERN_Z)), stash).merged();

        assertEquals(0, merged, "with no re-captured lectern block entity there is nothing to merge onto");
    }

    @Test
    @Disabled("lecterns are a 1.14 block entity, absent at 1.12.2; a re-captured lectern block entity cannot be built "
            + "here, so this synergy is a documented limit at this band")
    void reCapturingThePlacedLecternLetsTheStashMergeOntoIt() {
        // After re-capture the chunk's block entities include the placed lectern, so the merge lands.
        ChunkSnapshotSource snapshot = SyntheticChunks.fullWithBlockEntities(false,
                ImmutableList.of(blockEntity("minecraft:lectern", LECTERN_X, LECTERN_Y, LECTERN_Z)));
        NBTTagCompound recapturedTag = codec.encode(snapshot, false);

        Map<BlockPos, NBTTagCompound> stash = new LinkedHashMap<>();
        stash.put(new BlockPos(LECTERN_X, LECTERN_Y, LECTERN_Z), stashBook("Recaptured", 2));

        int merged = ContainerMerge.mergeLecternChunkStash(sink, recapturedTag,
                new ChunkPos(new BlockPos(LECTERN_X, LECTERN_Y, LECTERN_Z)), stash).merged();

        assertEquals(1, merged, "the re-captured lectern block entity gives the stash something to merge onto");
        assertEquals("Recaptured", mergedTitle(recapturedTag), "the stashed book lands on the re-captured lectern");
    }

    @Test
    @Disabled("lecterns are a 1.14 block entity, absent at 1.12.2; a re-captured lectern block entity cannot be built "
            + "here, so this synergy is a documented limit at this band")
    void repeatedRecaptureBeforeFlushMergesContentsExactlyOnce() {
        // Re-capture re-encodes the chunk many times while it is hot; each fresh tag carries the (book-less)
        // lectern block entity. The book comes from the stash and merges only at the single flush, so the
        // count and contents are stable no matter how many re-encodes preceded it.
        NBTTagCompound latestRecapture = null;
        for (int reencode = 0; reencode < 3; reencode++) {
            ChunkSnapshotSource snapshot = SyntheticChunks.fullWithBlockEntities(false,
                    ImmutableList.of(blockEntity("minecraft:lectern", LECTERN_X, LECTERN_Y, LECTERN_Z)));
            latestRecapture = codec.encode(snapshot, false);
        }

        Map<BlockPos, NBTTagCompound> stash = new LinkedHashMap<>();
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
