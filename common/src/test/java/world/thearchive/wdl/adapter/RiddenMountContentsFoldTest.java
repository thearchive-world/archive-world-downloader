// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.adapter.impl.VersionAdapterImpl;
import world.thearchive.wdl.compat.bobby.BobbyChunkFilter;
import world.thearchive.wdl.core.CoveredChunkIndex;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.SavedChunkIndex;
import world.thearchive.wdl.core.SendRangeEstimator;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The finish-time fold of a seated player's mount, over a record whose root is not the mount. A mount that something
 * pushed itself under is written as a passenger of that carrier, since the record vanilla stores is the whole tree from
 * its root, so the entity whose chest the player opened is a nested node. A fold that asks its question about the root
 * misses it, and the mount reaches disk with an empty {@code "Items"} while the stash entry survives to the finish
 * warning; the record is the tree's only copy, so the contents are then on disk nowhere.
 *
 * <p>Both cases run the same fold over the same captured holder and differ only in whether the mount is the root, which
 * is the one axis the site reads.
 */
class RiddenMountContentsFoldTest {
    private final ContainerSink sink = new ContainerSinkImpl();

    private static final UUID CARRIER = UUID.fromString("6b1d5f2c-9a30-4e11-b8c7-5d0e3a71f402");
    private static final UUID MOUNT = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    @Test
    void theOpenedContentsLandOnTheNestedMountThatOwnsThem(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        stashEntityContainer(session, MOUNT, capturedItems(new ItemStack(Items.DIAMOND, 5)));

        CompoundTag folded = session.foldRidingVehicleContents(mountUnderCarrier());

        assertEquals(1, items(passengerOf(folded)).size(),
                "the mule whose chest the player opened carries what the open captured, nested or not");
        assertFalse(folded.contains("Items"),
                "and the minecart it was pushed under carries no contents of its own");
    }

    @Test
    void anUnnestedMountFoldsExactlyAsItAlwaysHas(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        stashEntityContainer(session, MOUNT, capturedItems(new ItemStack(Items.DIAMOND, 5)));

        CompoundTag folded = session.foldRidingVehicleContents(EntityFixtures.entity("minecraft:mule", MOUNT));

        CompoundTag expected = EntityFixtures.entity("minecraft:mule", MOUNT);
        expected.put("Items", capturedItems(new ItemStack(Items.DIAMOND, 5)).getList("Items", 10));
        assertEquals(expected, folded,
                "a mount standing on its own folds to the tag it always did, key for key");
    }

    @Test
    void aStashKeyedOnNoNodeOfTheRecordFoldsNothing(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        stashEntityContainer(session, UUID.fromString("11111111-2222-3333-4444-555555555555"),
                capturedItems(new ItemStack(Items.DIAMOND, 5)));

        CompoundTag folded = session.foldRidingVehicleContents(mountUnderCarrier());

        assertFalse(folded.contains("Items"), "a container captured for another entity is not this record's");
        assertFalse(passengerOf(folded).contains("Items"), "on neither node of it");
        assertTrue(stash(session).containsKey(UUID.fromString("11111111-2222-3333-4444-555555555555")),
                "and its stash entry is left for the chunk write that owns it");
    }

    /** The scenario shape: a chested mount that a plain minecart pushed itself under, so the mount is the passenger. */
    private static CompoundTag mountUnderCarrier() {
        return EntityFixtures.entityCarrying(EntityFixtures.entity("minecraft:minecart", CARRIER),
                EntityFixtures.entity("minecraft:mule", MOUNT));
    }

    private static CompoundTag passengerOf(CompoundTag entity) {
        return entity.getList("Passengers", 10).getCompound(0);
    }

    private static ListTag items(CompoundTag entity) {
        return entity.getList("Items", 10);
    }

    private CompoundTag capturedItems(ItemStack stack) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(0, stack);
        return sink.captureItems(items);
    }

    private static void stashEntityContainer(LiveCaptureSession session, UUID uuid, CompoundTag holder)
            throws Exception {
        stash(session).put(uuid, holder);
    }

    /**
     * The session's open-time container stash, to seed. Reflective because every production path that fills it runs
     * behind the client singleton (a menu open), and widening the field would publish mutable capture state to the
     * whole package for a test's convenience.
     */
    @SuppressWarnings("unchecked")
    private static Map<UUID, CompoundTag> stash(LiveCaptureSession session) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField("entityContainerStash");
        field.setAccessible(true);
        return (Map<UUID, CompoundTag>) field.get(session);
    }

    /** A session with no bound level, which is all the pure fold reads beside its own stash. */
    private static LiveCaptureSession session(Path configDirectory) {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        properties.setProperty("captureContainers", "false");
        WdlConfig config = WdlConfig.parse(properties);
        assertFalse(config.captureEntities(), "the fixture must not publish an entity capture");
        assertFalse(config.captureContainers(), "the fixture must not publish an interaction capture");
        return new LiveCaptureSession(new VersionAdapterImpl(), new HeadlessPlatformBridge(configDirectory),
                config, null, DimensionType.OVERWORLD, DimensionType.OVERWORLD,
                new DownloadTarget("headless", null, DownloadMode.NEW), new SavedChunkIndex(),
                new CoveredChunkIndex(), new SendRangeEstimator(), false, false, BobbyChunkFilter.INACTIVE,
                () -> {});
    }
}
