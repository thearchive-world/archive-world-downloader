// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import org.jspecify.annotations.Nullable;
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
import world.thearchive.wdl.testsupport.BlockEntityFixtures;
import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the item-coordinate privacy scrub: {@link ItemLocationScrub} blanks the lodestone target and
 * the beehive bee flower positions on every item it reaches, over a serialized item list (the inventory or the ender
 * items), a block entity's own NBT (a chest, a jukebox), and a serialized entity (an item frame, mob equipment, an
 * inventory list, passengers), reaching items nested inside a shulker box ({@code tag.BlockEntityTag}) and inside a
 * bundle ({@code tag.Items}), while leaving the scrubbed item valid and every other item untouched. Real
 * {@link ItemStack}s serialized via the production {@link ContainerSink#captureItems} drive the round-trip, so neither
 * a live menu nor a {@code World} is needed; the scrub key strings are pinned by the assertions (a wrong key leaves the
 * coordinate and fails). Neither the lodestone nor the beehive exists at this band; the scrub is generic, name-driven
 * NBT surgery with no registry lookup, so the fixtures build the same key shapes vanilla's own writer would if the
 * blocks existed here, and a plain block stands in as their carrier.
 */
class ItemLocationScrubTest {
    private static final String LODESTONE_POS = "LodestonePos";
    private static final String LODESTONE_DIMENSION = "LodestoneDimension";
    private static final String LODESTONE_TRACKED = "LodestoneTracked";
    private static final String BEES = "Bees";
    private static final String ENTITY_DATA = "EntityData";
    private static final String FLOWER_POS = "FlowerPos";
    private static final String BLOCK_ENTITY_TAG = "BlockEntityTag";

    /** The overworld position the fixture lodestones target. */
    private static final BlockPos LODESTONE_TARGET = new BlockPos(128, 64, -512);

    private final ContainerSink sink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /**
     * A lodestone compass whose target is the raw {@code LodestonePos}/{@code LodestoneDimension} keys
     * {@link ItemLocationScrub} scrubs, the shape vanilla's own lodestone-tracking compass would carry them in at a
     * band that has one.
     */
    private static ItemStack lodestoneCompass() {
        ItemStack compass = new ItemStack(Items.COMPASS);
        compass.setTagCompound(new NBTTagCompound());
        NBTTagCompound tag = compass.getTagCompound();
        tag.setTag(LODESTONE_POS, NBTUtil.createPosTag(LODESTONE_TARGET));
        tag.setString(LODESTONE_DIMENSION, DimensionType.OVERWORLD.getName());
        tag.setBoolean(LODESTONE_TRACKED, true);
        return compass;
    }

    private static ItemStack shulkerHoldingLodestone() {
        ItemStack shulker = new ItemStack(Blocks.PURPLE_SHULKER_BOX);
        NBTTagCompound blockEntityTag = new NBTTagCompound();
        blockEntityTag.setTag("Items", ItemFixtures.items(lodestoneCompass()));
        shulker.setTagCompound(new NBTTagCompound());
        shulker.getTagCompound().setTag(BLOCK_ENTITY_TAG, blockEntityTag);
        return shulker;
    }

    /**
     * An item carrying beehive-shaped block-entity NBT with two flower positions the scrub must both blank: the hive's
     * own top-level {@code BlockEntityTag.FlowerPos} and one occupant's
     * {@code BlockEntityTag.Bees[].EntityData.FlowerPos}. No vanilla beehive exists at this band, so a plain block item
     * stands in as the foreign or modded carrier the scrub still has to reach.
     */
    private static ItemStack beehiveWithBeeFlowerPos() {
        ItemStack hive = new ItemStack(Blocks.CHEST);
        NBTTagCompound blockEntityTag = new NBTTagCompound();
        blockEntityTag.setTag(FLOWER_POS, NBTUtil.createPosTag(new BlockPos(130, 64, -510)));
        NBTTagCompound entityData = new NBTTagCompound();
        entityData.setTag(FLOWER_POS, NBTUtil.createPosTag(new BlockPos(128, 64, -512)));
        NBTTagCompound occupant = new NBTTagCompound();
        occupant.setTag(ENTITY_DATA, entityData);
        NBTTagList bees = new NBTTagList();
        bees.appendTag(occupant);
        blockEntityTag.setTag(BEES, bees);
        hive.setTagCompound(new NBTTagCompound());
        hive.getTagCompound().setTag(BLOCK_ENTITY_TAG, blockEntityTag);
        return hive;
    }

    /** Whether the hive's own top-level flower_pos (the pre-component block-entity copy) is present. */
    private static boolean hiveFlowerPosPresent(ItemStack hive) {
        NBTTagCompound tag = hive.getTagCompound();
        return tag != null && tag.getTag(BLOCK_ENTITY_TAG) instanceof NBTTagCompound
                && ((NBTTagCompound) tag.getTag(BLOCK_ENTITY_TAG)).hasKey(FLOWER_POS);
    }

    /** Whether the first occupant's flower_pos is present. */
    private static boolean beeFlowerPresent(ItemStack hive) {
        NBTTagList bees = beesOf(hive);
        return !bees.isEmpty() && bees.getCompoundTagAt(0).getTag(ENTITY_DATA) instanceof NBTTagCompound
                && ((NBTTagCompound) bees.getCompoundTagAt(0).getTag(ENTITY_DATA)).hasKey(FLOWER_POS);
    }

    /** The item's {@code LodestoneTracked} tag (kept, unlike the coordinate keys), or {@code null} when absent. */
    private static @Nullable NBTBase lodestoneTrackerOf(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return tag == null ? null : tag.getTag(LODESTONE_TRACKED);
    }

    /** The lodestone target position, present unless the scrub blanked it (or the item never carried one). */
    private static Optional<NBTBase> targetOf(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return tag == null ? Optional.empty() : Optional.ofNullable(tag.getTag(LODESTONE_POS));
    }

    /** The dimension the lodestone target names, blanked beside the position it is one half of. */
    private static Optional<NBTBase> targetDimensionOf(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return tag == null ? Optional.empty() : Optional.ofNullable(tag.getTag(LODESTONE_DIMENSION));
    }

    /** The hive item's {@code BlockEntityTag.Bees} list, or an empty list when the item carries none. */
    private static NBTTagList beesOf(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !(tag.getTag(BLOCK_ENTITY_TAG) instanceof NBTTagCompound)) {
            return new NBTTagList();
        }
        NBTTagCompound blockEntityTag = (NBTTagCompound) tag.getTag(BLOCK_ENTITY_TAG);
        return blockEntityTag.getTagList(BEES, 10);
    }

    /** The nested items inside a captured shulker box's {@code BlockEntityTag.Items}, or {@code null} when absent. */
    private static @Nullable NBTTagList containerItemsOf(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !(tag.getTag(BLOCK_ENTITY_TAG) instanceof NBTTagCompound)) {
            return null;
        }
        NBTTagCompound blockEntityTag = (NBTTagCompound) tag.getTag(BLOCK_ENTITY_TAG);
        return blockEntityTag.getTagList("Items", 10);
    }

    private NBTTagCompound holderOf(ItemStack... stacks) {
        NonNullList<ItemStack> items = NonNullList.withSize(stacks.length, ItemStack.EMPTY);
        for (int i = 0; i < stacks.length; i++) {
            items.set(i, stacks[i]);
        }
        return sink.captureItems(items);
    }

    private NonNullList<ItemStack> readBack(NBTTagCompound holder, int size) {
        NonNullList<ItemStack> back = NonNullList.withSize(size, ItemStack.EMPTY);
        ItemStackHelper.loadAllItems(holder, back);
        return back;
    }

    /**
     * A session with no bound level, which is all the scrub gate needs. Entity and container capture are off so the
     * constructor publishes no process-wide capture into the static activation slots, which only finish() clears; the
     * toggles are asserted rather than assumed, since an unrecognized key falls back to a default that is on.
     */
    private static LiveCaptureSession session(Path configDirectory, boolean saveItemCoordinates) {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        properties.setProperty("captureContainers", "false");
        properties.setProperty("saveItemCoordinates", Boolean.toString(saveItemCoordinates));
        WdlConfig config = WdlConfig.parse(properties);
        assertFalse(config.captureEntities(), "the fixture must not publish an entity capture");
        assertFalse(config.captureContainers(), "the fixture must not publish an interaction capture");
        assertEquals(saveItemCoordinates, config.saveItemCoordinates(), "the fixture must set the opt-out it names");
        return new LiveCaptureSession(new VersionAdapterImpl(), new HeadlessPlatformBridge(configDirectory),
                config, null, DimensionType.OVERWORLD, DimensionType.OVERWORLD,
                new DownloadTarget("headless", null, DownloadMode.NEW), new SavedChunkIndex(),
                new CoveredChunkIndex(), new SendRangeEstimator(), false, false, BobbyChunkFilter.INACTIVE,
                () -> {});
    }

    /** The gate is private and its production callers run behind the client singleton a headless test lacks. */
    private static void scrubAndRemapItems(LiveCaptureSession session, NBTTagCompound holder) {
        try {
            Method method = LiveCaptureSession.class
                    .getDeclaredMethod("scrubAndRemapItems", NBTTagCompound.class, Object.class);
            method.setAccessible(true);
            method.invoke(session, holder, "holder");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not drive scrubAndRemapItems", e);
        }
    }

    /**
     * A block entity storing one item under a foreign {@code item} key; the type-agnostic scrub must still reach it.
     */
    private NBTTagCompound blockEntityWithItem(ItemStack item) {
        // A jukebox stands in for the higher-band single-item carriers (a decorated pot); no bell exists at this band.
        NBTTagCompound blockEntity = BlockEntityFixtures.blockEntity("minecraft:jukebox", 0, 64, 0);
        blockEntity.setTag("item", ItemFixtures.itemTag(item));
        return blockEntity;
    }

    private ItemStack itemOf(NBTTagCompound blockEntity) {
        return new ItemStack(blockEntity.getCompoundTag("item"));
    }

    private NBTTagCompound itemNbt(ItemStack stack) {
        return stack.writeToNBT(new NBTTagCompound());
    }

    private ItemStack itemFrom(NBTBase itemNbt) {
        return new ItemStack((NBTTagCompound) itemNbt);
    }

    private NBTTagCompound entity(String id) {
        return EntityFixtures.entityTag(id);
    }

    private NBTTagCompound blockEntityWithItems(ItemStack... stacks) {
        // A chest stands in for the higher-band Items-list carriers (a campfire); no campfire exists at this band.
        NBTTagCompound blockEntity = BlockEntityFixtures.blockEntity("minecraft:chest", 0, 64, 0);
        blockEntity.setTag("Items", holderOf(stacks).getTagList("Items", 10));
        return blockEntity;
    }

    @Test
    void scrubBlanksTheLodestoneTargetButKeepsTheCompass() {
        NBTTagCompound holder = holderOf(lodestoneCompass(), new ItemStack(Items.DIAMOND, 3));
        NonNullList<ItemStack> before = readBack(holder, 2);
        assertTrue(targetOf(before.get(0)).isPresent(), "precondition: the fixture compass has a target");
        assertTrue(targetDimensionOf(before.get(0)).isPresent(),
                "precondition: and names the dimension that target is in");

        ItemLocationScrub.scrub(holder, "Items");

        NonNullList<ItemStack> back = readBack(holder, 2);
        assertTrue(!targetOf(back.get(0)).isPresent(), "the lodestone target is blanked");
        assertTrue(!targetDimensionOf(back.get(0)).isPresent(),
                "and so is the dimension it named, which alone still narrows the base to one world");
        assertNotNull(lodestoneTrackerOf(back.get(0)),
                "the lodestone_tracker component is kept: the compass stays a valid compass pointing nowhere");
        assertEquals(Items.COMPASS, back.get(0).getItem(), "still a compass");
        assertEquals(Items.DIAMOND, back.get(1).getItem(), "a non-lodestone item is untouched");
        assertEquals(3, back.get(1).getCount());
    }

    @Test
    void scrubItemBlanksLodestoneTargetOnOneItem() {
        NBTTagCompound item = itemNbt(lodestoneCompass());
        assertTrue(targetOf(itemFrom(item)).isPresent(), "precondition: the single item has a target");

        ItemLocationScrub.scrubItem(item);

        assertTrue(!targetOf(itemFrom(item)).isPresent(), "the single-item scrub blanks the lodestone target");
    }

    @Test
    void scrubReachesLodestonesNestedInShulker() {
        NBTTagCompound holder = holderOf(shulkerHoldingLodestone());

        ItemLocationScrub.scrub(holder, "Items");

        NonNullList<ItemStack> back = readBack(holder, 1);
        NBTTagList container = containerItemsOf(back.get(0));
        assertNotNull(container, "the shulker keeps its container component");
        ItemStack nestedInShulker = new ItemStack(container.getCompoundTagAt(0));
        assertTrue(!targetOf(nestedInShulker).isPresent(), "a lodestone nested in a shulker box is blanked");
    }

    @Test
    void theHolderCallSiteScrubsByDefault(@TempDir Path configDirectory) {
        NBTTagCompound holder = holderOf(lodestoneCompass());
        assertTrue(targetOf(readBack(holder, 1).get(0)).isPresent(), "precondition: the fixture compass has a target");

        scrubAndRemapItems(session(configDirectory, false), holder);

        assertTrue(!targetOf(readBack(holder, 1).get(0)).isPresent(), "the default blanks the target at the call site");
    }

    @Test
    void theHolderCallSiteKeepsTheTargetWhenTheUserOptsIn(@TempDir Path configDirectory) {
        NBTTagCompound holder = holderOf(lodestoneCompass());
        assertTrue(targetOf(readBack(holder, 1).get(0)).isPresent(), "precondition: the fixture compass has a target");

        scrubAndRemapItems(session(configDirectory, true), holder);

        assertTrue(targetOf(readBack(holder, 1).get(0)).isPresent(), "the opt-in keeps the target at the call site");
    }

    @Test
    void scrubBlockEntityBlanksLodestoneStoredUnderItem() {
        NBTTagCompound pot = blockEntityWithItem(lodestoneCompass());
        assertTrue(targetOf(itemOf(pot)).isPresent(), "precondition: the pot's compass has a target");

        ItemLocationScrub.scrubBlockEntity(pot);

        ItemStack back = itemOf(pot);
        assertTrue(!targetOf(back).isPresent(), "the lodestone target under the block entity's item key is blanked");
        assertNotNull(lodestoneTrackerOf(back),
                "the lodestone_tracker component is kept: the compass stays a valid compass pointing nowhere");
        assertEquals(Items.COMPASS, back.getItem(), "still a compass");
    }

    @Test
    void scrubBlockEntityBlanksLodestoneInItemsList() {
        NBTTagCompound blockEntity = blockEntityWithItems(lodestoneCompass(), new ItemStack(Items.DIAMOND, 3));

        ItemLocationScrub.scrubBlockEntity(blockEntity);

        NonNullList<ItemStack> back = readBack(blockEntity, 2);
        assertTrue(!targetOf(back.get(0)).isPresent(), "a lodestone in the block entity's Items list is blanked");
        assertEquals(Items.DIAMOND, back.get(1).getItem(), "a non-lodestone item is untouched");
        assertEquals(3, back.get(1).getCount());
    }

    @Test
    void scrubBlockEntityReachesLodestonesNestedInShulker() {
        NBTTagCompound potWithShulker = blockEntityWithItem(shulkerHoldingLodestone());
        ItemLocationScrub.scrubBlockEntity(potWithShulker);
        NBTTagList containerPot = containerItemsOf(itemOf(potWithShulker));
        assertNotNull(containerPot, "the shulker keeps its container component");
        assertTrue(!targetOf(new ItemStack(containerPot.getCompoundTagAt(0))).isPresent(),
                "a lodestone nested in a shulker stored as the block entity's item is blanked");
    }

    @Test
    void scrubBlockEntityLeavesNonItemBlockEntityUnchanged() {
        NBTTagCompound blockEntity = BlockEntityFixtures.blockEntityWithForeignKey("minecraft:sign", 1, 2, 3,
                "wdl_test_marker", "urn");
        NBTTagList sherds = new NBTTagList();
        NBTTagCompound sherd = new NBTTagCompound();
        sherd.setString("front", "minecraft:brick");
        sherds.appendTag(sherd);
        blockEntity.setTag("sherds", sherds);

        NBTTagCompound before = blockEntity.copy();
        ItemLocationScrub.scrubBlockEntity(blockEntity);

        assertEquals(before, blockEntity, "a block entity carrying no real item round-trips unchanged");
    }

    @Test
    void scrubBlanksBeeFlowerPosButKeepsTheOccupant() {
        NBTTagCompound holder = holderOf(beehiveWithBeeFlowerPos(), new ItemStack(Items.DIAMOND, 3));
        ItemStack precondition = readBack(holder, 2).get(0);
        assertTrue(hiveFlowerPosPresent(precondition), "precondition: the fixture hive has its own flower_pos");
        assertTrue(beeFlowerPresent(precondition), "precondition: the fixture bee has a flower_pos");

        ItemLocationScrub.scrub(holder, "Items");

        NonNullList<ItemStack> back = readBack(holder, 2);
        assertFalse(hiveFlowerPosPresent(back.get(0)), "the hive's own flower_pos is blanked too");
        NBTTagList bees = beesOf(back.get(0));
        assertFalse(bees.isEmpty(), "the bees component is kept");
        assertEquals(1, bees.tagCount(), "the occupant is kept");
        assertFalse(
                bees.getCompoundTagAt(0).getTag(ENTITY_DATA) instanceof NBTTagCompound
                        && ((NBTTagCompound) bees.getCompoundTagAt(0).getTag(ENTITY_DATA)).hasKey(FLOWER_POS),
                "the bee flower_pos is blanked");
        assertEquals(Item.getItemFromBlock(Blocks.CHEST), back.get(0).getItem(), "the carrier item is unchanged");
        assertEquals(Items.DIAMOND, back.get(1).getItem(), "an item carrying no such NBT is untouched");
    }

    @Test
    void scrubReachesBeeFlowerPosNestedInShulker() {
        ItemStack shulker = new ItemStack(Blocks.PURPLE_SHULKER_BOX);
        NBTTagCompound blockEntityTag = new NBTTagCompound();
        blockEntityTag.setTag("Items", ItemFixtures.items(beehiveWithBeeFlowerPos()));
        shulker.setTagCompound(new NBTTagCompound());
        shulker.getTagCompound().setTag(BLOCK_ENTITY_TAG, blockEntityTag);
        NBTTagCompound holder = holderOf(shulker);

        ItemLocationScrub.scrub(holder, "Items");

        NBTTagList container = containerItemsOf(readBack(holder, 1).get(0));
        assertNotNull(container, "the shulker keeps its container component");
        assertFalse(beeFlowerPresent(new ItemStack(container.getCompoundTagAt(0))),
                "a beehive nested in a shulker box has its bee flower_pos blanked");
    }

    @Test
    void scrubOnAnEmptyBeehiveIsNoop() {
        NBTTagCompound holder = holderOf(new ItemStack(Blocks.CHEST));
        NBTTagCompound before = holder.copy();
        ItemLocationScrub.scrub(holder, "Items");
        assertEquals(before, holder, "an item carrying no location NBT round-trips unchanged");
    }

    @Test
    void scrubEntityBlanksItemFrameAndItemDisplay() {
        NBTTagCompound frame = entity("minecraft:item_frame");
        frame.setTag("Item", itemNbt(lodestoneCompass()));
        NBTTagCompound display = entity("minecraft:item_display");
        display.setTag("item", itemNbt(lodestoneCompass())); // lowercase key: the case-agnostic walk must reach it

        ItemLocationScrub.scrubEntity(frame);
        ItemLocationScrub.scrubEntity(display);

        assertTrue(!targetOf(itemFrom(frame.getTag("Item"))).isPresent(), "framed compass (Item) blanked");
        assertTrue(!targetOf(itemFrom(display.getTag("item"))).isPresent(), "item display (item) blanked");
    }

    @Test
    void scrubEntityBlanksEquipmentAndInventory() {
        NBTTagCompound zombie = entity("minecraft:zombie");
        NBTTagCompound equipment = new NBTTagCompound();
        equipment.setTag("mainhand", itemNbt(lodestoneCompass()));
        zombie.setTag("equipment", equipment);

        NBTTagCompound allay = entity("minecraft:allay");
        NBTTagList inventory = new NBTTagList();
        inventory.appendTag(itemNbt(lodestoneCompass()));
        allay.setTag("Inventory", inventory);

        ItemLocationScrub.scrubEntity(zombie);
        ItemLocationScrub.scrubEntity(allay);

        assertTrue(!targetOf(itemFrom(((NBTTagCompound) zombie.getTag("equipment")).getTag("mainhand"))).isPresent(),
                "equipment slot-map item blanked");
        assertTrue(!targetOf(itemFrom(((NBTTagList) allay.getTag("Inventory")).get(0))).isPresent(),
                "Inventory list item blanked");
    }

    @Test
    void scrubEntityBlanksPre1215HandAndArmorItems() {
        // A ListTag-shaped mob equipment: HandItems/ArmorItems lists of item NBT (empty slots are {}), a shape a
        // modded or foreign server can still hand a 1.12.2 client. The generic name-agnostic NBTTagList walk must
        // reach them, or a captured mob's held lodestone leaks its target.
        NBTTagCompound zombie = entity("minecraft:zombie");
        NBTTagList handItems = new NBTTagList();
        handItems.appendTag(itemNbt(lodestoneCompass())); // mainhand
        handItems.appendTag(itemNbt(shulkerHoldingLodestone())); // offhand: nested lodestone must also be reached
        zombie.setTag("HandItems", handItems);
        NBTTagList armorItems = new NBTTagList();
        armorItems.appendTag(new NBTTagCompound()); // feet, empty as vanilla writes empty slots
        armorItems.appendTag(new NBTTagCompound()); // legs
        armorItems.appendTag(new NBTTagCompound()); // chest
        armorItems.appendTag(itemNbt(lodestoneCompass())); // head slot holding a lodestone compass
        zombie.setTag("ArmorItems", armorItems);
        assertTrue(targetOf(itemFrom(handItems.get(0))).isPresent(),
                "precondition: the mainhand compass has a target");
        assertTrue(targetOf(itemFrom(armorItems.get(3))).isPresent(), "precondition: the armor compass has a target");

        ItemLocationScrub.scrubEntity(zombie);

        assertTrue(!targetOf(itemFrom(((NBTTagList) zombie.getTag("HandItems")).get(0))).isPresent(),
                "a lodestone in a HandItems mainhand slot is blanked");
        NBTTagList offhandContainer = containerItemsOf(itemFrom(((NBTTagList) zombie.getTag("HandItems")).get(1)));
        assertNotNull(offhandContainer, "the offhand shulker keeps its container component");
        assertTrue(!targetOf(new ItemStack(offhandContainer.getCompoundTagAt(0))).isPresent(),
                "a lodestone nested in a shulker in a HandItems slot is blanked");
        assertTrue(!targetOf(itemFrom(((NBTTagList) zombie.getTag("ArmorItems")).get(3))).isPresent(),
                "a lodestone in an ArmorItems slot is blanked");
    }

    @Test
    void scrubEntityBlanksBeehiveHeldByAnEntity() {
        NBTTagCompound frame = entity("minecraft:item_frame");
        frame.setTag("Item", itemNbt(beehiveWithBeeFlowerPos()));

        ItemLocationScrub.scrubEntity(frame);

        ItemStack held = itemFrom(frame.getTag("Item"));
        assertFalse(hiveFlowerPosPresent(held), "a beehive held in an item frame has its own flower_pos blanked too");
        assertFalse(beeFlowerPresent(held), "a beehive held in an item frame has its bee flower_pos blanked");
    }

    @Test
    void scrubEntityRecursesIntoPassengers() {
        NBTTagCompound boat = entity("minecraft:boat");
        NBTTagCompound rider = entity("minecraft:zombie");
        NBTTagCompound riderEquip = new NBTTagCompound();
        riderEquip.setTag("mainhand", itemNbt(lodestoneCompass()));
        rider.setTag("equipment", riderEquip);
        NBTTagList passengers = new NBTTagList();
        passengers.appendTag(rider);
        boat.setTag("Passengers", passengers);

        ItemLocationScrub.scrubEntity(boat);

        NBTTagCompound scrubbedRider = (NBTTagCompound) ((NBTTagList) boat.getTag("Passengers")).get(0);
        assertTrue(
                !targetOf(itemFrom(((NBTTagCompound) scrubbedRider.getTag("equipment")).getTag("mainhand")))
                        .isPresent(),
                "a compass on a passenger mob's equipment is blanked");
    }

    @Test
    void scrubEntityWithoutCoordinateItemsIsNoop() {
        NBTTagCompound zombie = entity("minecraft:zombie");
        NBTTagCompound equipment = new NBTTagCompound();
        equipment.setTag("mainhand", itemNbt(new ItemStack(Items.DIAMOND_SWORD)));
        zombie.setTag("equipment", equipment);
        NBTTagCompound before = zombie.copy();

        ItemLocationScrub.scrubEntity(zombie);

        assertEquals(before, zombie, "an entity holding no coordinate item round-trips unchanged");
    }
}
