// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Bees;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
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
 * items), a block entity's own NBT (a decorated pot, a campfire), and a serialized entity (an item frame, mob
 * equipment, an inventory list, passengers), reaching items nested inside a shulker box (the
 * {@code minecraft:container} component) and inside a bundle (the {@code minecraft:bundle_contents} component), while
 * leaving the scrubbed item valid and every other item untouched. Real {@link ItemStack}s serialized via the production
 * {@link ContainerSink#captureItems} drive the round-trip, so neither a live menu nor a {@code Level} is needed; the
 * scrub key strings are pinned by the assertions (a wrong key leaves the coordinate and fails).
 */
class ItemLocationScrubTest {
    private static RegistryAccess registries;
    private final ContainerSink sink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen();
    }

    private static GlobalPos basePos() {
        return GlobalPos.of(Level.OVERWORLD, new BlockPos(128, 64, -512));
    }

    private static ItemStack lodestoneCompass() {
        ItemStack compass = new ItemStack(Items.COMPASS);
        compass.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(basePos()), true));
        return compass;
    }

    private static ItemStack shulkerHoldingLodestone() {
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(lodestoneCompass())));
        return shulker;
    }

    private static ItemStack bundleHoldingLodestone() {
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS,
                new BundleContents(List.of(ItemStackTemplate.fromNonEmptyStack(lodestoneCompass()))));
        return bundle;
    }

    private static ItemStack beehiveWithBeeFlowerPos() {
        ItemStack hive = new ItemStack(Items.BEEHIVE);
        CompoundTag beeNbt = new CompoundTag();
        beeNbt.put("flower_pos",
                BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, new BlockPos(128, 64, -512)).getOrThrow());
        BeehiveBlockEntity.Occupant occupant = new BeehiveBlockEntity.Occupant(
                TypedEntityData.of(EntityType.BEE, beeNbt), 0, 600);
        hive.set(DataComponents.BEES, new Bees(List.of(occupant)));
        return hive;
    }

    private static boolean beeFlowerPresent(ItemStack hive) {
        Bees bees = hive.get(DataComponents.BEES);
        return bees != null && !bees.bees().isEmpty()
                && bees.bees().get(0).entityData().contains("flower_pos");
    }

    private CompoundTag holderOf(ItemStack... stacks) {
        NonNullList<ItemStack> items = NonNullList.withSize(stacks.length, ItemStack.EMPTY);
        for (int i = 0; i < stacks.length; i++) {
            items.set(i, stacks[i]);
        }
        return sink.captureItems(items, registries);
    }

    private NonNullList<ItemStack> readBack(CompoundTag holder, int size) {
        NonNullList<ItemStack> back = NonNullList.withSize(size, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(TagValueInput.create(ProblemReporter.DISCARDING, registries, holder), back);
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
                config, null, Level.OVERWORLD, Level.OVERWORLD, TestRegistries.frozen(),
                new DownloadTarget("headless", null, DownloadMode.NEW), new SavedChunkIndex(),
                new CoveredChunkIndex(), new SendRangeEstimator(), false, false, BobbyChunkFilter.INACTIVE,
                () -> {});
    }

    /** The gate is private and its production callers run behind the client singleton a headless test lacks. */
    private static void scrubAndRemapItems(LiveCaptureSession session, CompoundTag holder) {
        try {
            Method method = LiveCaptureSession.class
                    .getDeclaredMethod("scrubAndRemapItems", CompoundTag.class, Object.class);
            method.setAccessible(true);
            method.invoke(session, holder, "holder");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not drive scrubAndRemapItems", e);
        }
    }

    private static Optional<GlobalPos> targetOf(ItemStack stack) {
        LodestoneTracker tracker = stack.get(DataComponents.LODESTONE_TRACKER);
        return tracker == null ? Optional.empty() : tracker.target();
    }

    private CompoundTag blockEntityWithItem(ItemStack item) {
        CompoundTag blockEntity = BlockEntityFixtures.blockEntity("minecraft:decorated_pot", 0, 64, 0);
        blockEntity.put("item", ItemFixtures.itemTag(item));
        return blockEntity;
    }

    private ItemStack itemOf(CompoundTag blockEntity) {
        return TagValueInput.create(ProblemReporter.DISCARDING, registries, blockEntity)
                .read("item", ItemStack.CODEC).orElse(ItemStack.EMPTY);
    }

    private CompoundTag itemNbt(ItemStack stack) {
        return (CompoundTag) ItemStack.CODEC
                .encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack).getOrThrow();
    }

    private ItemStack itemFrom(Tag itemNbt) {
        return ItemStack.CODEC
                .parse(registries.createSerializationContext(NbtOps.INSTANCE), itemNbt).getOrThrow();
    }

    private CompoundTag entity(String id) {
        return EntityFixtures.entityTag(id);
    }

    private CompoundTag blockEntityWithItems(ItemStack... stacks) {
        CompoundTag blockEntity = BlockEntityFixtures.blockEntity("minecraft:campfire", 0, 64, 0);
        blockEntity.put("Items", holderOf(stacks).getListOrEmpty("Items"));
        return blockEntity;
    }

    @Test
    void scrubBlanksTheLodestoneTargetButKeepsTheCompass() {
        CompoundTag holder = holderOf(lodestoneCompass(), new ItemStack(Items.DIAMOND, 3));
        assertTrue(targetOf(readBack(holder, 2).get(0)).isPresent(), "precondition: the fixture compass has a target");

        ItemLocationScrub.scrub(holder, "Items");

        NonNullList<ItemStack> back = readBack(holder, 2);
        assertTrue(targetOf(back.get(0)).isEmpty(), "the lodestone target is blanked");
        assertNotNull(back.get(0).get(DataComponents.LODESTONE_TRACKER),
                "the lodestone_tracker component is kept: the compass stays a valid compass pointing nowhere");
        assertEquals(Items.COMPASS, back.get(0).getItem(), "still a compass");
        assertEquals(Items.DIAMOND, back.get(1).getItem(), "a non-lodestone item is untouched");
        assertEquals(3, back.get(1).getCount());
    }

    @Test
    void scrubReachesLodestonesNestedInShulkerAndBundle() {
        CompoundTag holder = holderOf(shulkerHoldingLodestone(), bundleHoldingLodestone());

        ItemLocationScrub.scrub(holder, "Items");

        NonNullList<ItemStack> back = readBack(holder, 2);
        ItemContainerContents container = back.get(0).get(DataComponents.CONTAINER);
        assertNotNull(container, "the shulker keeps its container component");
        ItemStack nestedInShulker = container.nonEmptyItemCopyStream().toList().get(0);
        assertTrue(targetOf(nestedInShulker).isEmpty(), "a lodestone nested in a shulker box is blanked");

        BundleContents bundle = back.get(1).get(DataComponents.BUNDLE_CONTENTS);
        assertNotNull(bundle, "the bundle keeps its contents component");
        ItemStack nestedInBundle = bundle.itemCopyStream().toList().get(0);
        assertTrue(targetOf(nestedInBundle).isEmpty(), "a lodestone nested in a bundle is blanked");
    }

    @Test
    void theHolderCallSiteScrubsByDefault(@TempDir Path configDirectory) {
        CompoundTag holder = holderOf(lodestoneCompass());
        assertTrue(targetOf(readBack(holder, 1).get(0)).isPresent(), "precondition: the fixture compass has a target");

        scrubAndRemapItems(session(configDirectory, false), holder);

        assertTrue(targetOf(readBack(holder, 1).get(0)).isEmpty(), "the default blanks the target at the call site");
    }

    @Test
    void theHolderCallSiteKeepsTheTargetWhenTheUserOptsIn(@TempDir Path configDirectory) {
        CompoundTag holder = holderOf(lodestoneCompass());
        assertTrue(targetOf(readBack(holder, 1).get(0)).isPresent(), "precondition: the fixture compass has a target");

        scrubAndRemapItems(session(configDirectory, true), holder);

        assertTrue(targetOf(readBack(holder, 1).get(0)).isPresent(), "the opt-in keeps the target at the call site");
    }

    @Test
    void scrubBlockEntityBlanksLodestoneStoredUnderItem() {
        CompoundTag pot = blockEntityWithItem(lodestoneCompass());
        assertTrue(targetOf(itemOf(pot)).isPresent(), "precondition: the pot's compass has a target");

        ItemLocationScrub.scrubBlockEntity(pot);

        ItemStack back = itemOf(pot);
        assertTrue(targetOf(back).isEmpty(), "the lodestone target under the block entity's item key is blanked");
        assertNotNull(back.get(DataComponents.LODESTONE_TRACKER),
                "the lodestone_tracker component is kept: the compass stays a valid compass pointing nowhere");
        assertEquals(Items.COMPASS, back.getItem(), "still a compass");
    }

    @Test
    void scrubBlockEntityBlanksLodestoneInItemsList() {
        CompoundTag campfire = blockEntityWithItems(lodestoneCompass(), new ItemStack(Items.DIAMOND, 3));

        ItemLocationScrub.scrubBlockEntity(campfire);

        NonNullList<ItemStack> back = readBack(campfire, 2);
        assertTrue(targetOf(back.get(0)).isEmpty(), "a lodestone in the block entity's Items list is blanked");
        assertEquals(Items.DIAMOND, back.get(1).getItem(), "a non-lodestone item is untouched");
        assertEquals(3, back.get(1).getCount());
    }

    @Test
    void scrubBlockEntityReachesLodestonesNestedInShulkerAndBundle() {
        CompoundTag potWithShulker = blockEntityWithItem(shulkerHoldingLodestone());
        ItemLocationScrub.scrubBlockEntity(potWithShulker);
        ItemContainerContents container = itemOf(potWithShulker).get(DataComponents.CONTAINER);
        assertNotNull(container, "the shulker keeps its container component");
        assertTrue(targetOf(container.nonEmptyItemCopyStream().toList().get(0)).isEmpty(),
                "a lodestone nested in a shulker stored as the block entity's item is blanked");

        CompoundTag potWithBundle = blockEntityWithItem(bundleHoldingLodestone());
        ItemLocationScrub.scrubBlockEntity(potWithBundle);
        BundleContents bundle = itemOf(potWithBundle).get(DataComponents.BUNDLE_CONTENTS);
        assertNotNull(bundle, "the bundle keeps its contents component");
        assertTrue(targetOf(bundle.itemCopyStream().toList().get(0)).isEmpty(),
                "a lodestone nested in a bundle stored as the block entity's item is blanked");
    }

    @Test
    void scrubBlockEntityLeavesNonItemBlockEntityUnchanged() {
        CompoundTag blockEntity = BlockEntityFixtures.namedBlockEntity("minecraft:decorated_pot", 1, 2, 3, "urn");
        ListTag sherds = new ListTag();
        CompoundTag sherd = new CompoundTag();
        sherd.putString("front", "minecraft:brick");
        sherds.add(sherd);
        blockEntity.put("sherds", sherds);

        CompoundTag before = blockEntity.copy();
        ItemLocationScrub.scrubBlockEntity(blockEntity);

        assertEquals(before, blockEntity, "a block entity carrying no real item round-trips unchanged");
    }

    @Test
    void scrubBlanksBeeFlowerPosButKeepsTheOccupant() {
        CompoundTag holder = holderOf(beehiveWithBeeFlowerPos(), new ItemStack(Items.DIAMOND, 3));
        assertTrue(beeFlowerPresent(readBack(holder, 2).get(0)), "precondition: the fixture bee has a flower_pos");

        ItemLocationScrub.scrub(holder, "Items");

        NonNullList<ItemStack> back = readBack(holder, 2);
        Bees bees = back.get(0).get(DataComponents.BEES);
        assertNotNull(bees, "the bees component is kept");
        assertEquals(1, bees.bees().size(), "the occupant is kept");
        assertFalse(bees.bees().get(0).entityData().contains("flower_pos"),
                "the bee flower_pos is blanked");
        assertEquals(Items.BEEHIVE, back.get(0).getItem(), "still a beehive");
        assertEquals(Items.DIAMOND, back.get(1).getItem(), "a non-beehive item is untouched");
    }

    @Test
    void scrubReachesBeeFlowerPosNestedInShulker() {
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(beehiveWithBeeFlowerPos())));
        CompoundTag holder = holderOf(shulker);

        ItemLocationScrub.scrub(holder, "Items");

        ItemContainerContents container = readBack(holder, 1).get(0).get(DataComponents.CONTAINER);
        assertNotNull(container, "the shulker keeps its container component");
        assertFalse(beeFlowerPresent(container.nonEmptyItemCopyStream().toList().get(0)),
                "a beehive nested in a shulker box has its bee flower_pos blanked");
    }

    @Test
    void scrubOnAnEmptyBeehiveIsNoop() {
        CompoundTag holder = holderOf(new ItemStack(Items.BEEHIVE));
        CompoundTag before = holder.copy();
        ItemLocationScrub.scrub(holder, "Items");
        assertEquals(before, holder, "an empty beehive round-trips unchanged");
    }

    @Test
    void scrubEntityBlanksItemFrameAndItemDisplay() {
        CompoundTag frame = entity("minecraft:item_frame");
        frame.put("Item", itemNbt(lodestoneCompass()));
        CompoundTag display = entity("minecraft:item_display");
        display.put("item", itemNbt(lodestoneCompass())); // lowercase key: the case-agnostic walk must reach it

        ItemLocationScrub.scrubEntity(frame);
        ItemLocationScrub.scrubEntity(display);

        assertTrue(targetOf(itemFrom(frame.get("Item"))).isEmpty(), "framed compass (Item) blanked");
        assertTrue(targetOf(itemFrom(display.get("item"))).isEmpty(), "item display (item) blanked");
    }

    @Test
    void scrubEntityBlanksEquipmentAndInventory() {
        CompoundTag zombie = entity("minecraft:zombie");
        CompoundTag equipment = new CompoundTag();
        equipment.put("mainhand", itemNbt(lodestoneCompass()));
        zombie.put("equipment", equipment);

        CompoundTag allay = entity("minecraft:allay");
        ListTag inventory = new ListTag();
        inventory.add(itemNbt(lodestoneCompass()));
        allay.put("Inventory", inventory);

        ItemLocationScrub.scrubEntity(zombie);
        ItemLocationScrub.scrubEntity(allay);

        assertTrue(targetOf(itemFrom(((CompoundTag) zombie.get("equipment")).get("mainhand"))).isEmpty(),
                "equipment slot-map item blanked");
        assertTrue(targetOf(itemFrom(((ListTag) allay.get("Inventory")).get(0))).isEmpty(),
                "Inventory list item blanked");
    }

    @Test
    void scrubEntityBlanksPre1215HandAndArmorItems() {
        // 1.21.4-shape mob equipment: HandItems/ArmorItems ListTags of item NBT (empty slots are {}), the
        // pre-1.21.5 home, before equipment moved to the slot-map "equipment" compound. The generic
        // name-agnostic ListTag walk must reach them, or a captured 1.21.4 mob's held lodestone leaks its target.
        CompoundTag zombie = entity("minecraft:zombie");
        ListTag handItems = new ListTag();
        handItems.add(itemNbt(lodestoneCompass())); // mainhand
        handItems.add(itemNbt(shulkerHoldingLodestone())); // offhand: nested lodestone must also be reached
        zombie.put("HandItems", handItems);
        ListTag armorItems = new ListTag();
        armorItems.add(new CompoundTag()); // feet, empty as vanilla writes empty slots
        armorItems.add(new CompoundTag()); // legs
        armorItems.add(new CompoundTag()); // chest
        armorItems.add(itemNbt(lodestoneCompass())); // head slot holding a lodestone compass
        zombie.put("ArmorItems", armorItems);
        assertTrue(targetOf(itemFrom(handItems.get(0))).isPresent(), "precondition: the mainhand compass has a target");
        assertTrue(targetOf(itemFrom(armorItems.get(3))).isPresent(), "precondition: the armor compass has a target");

        ItemLocationScrub.scrubEntity(zombie);

        assertTrue(targetOf(itemFrom(((ListTag) zombie.get("HandItems")).get(0))).isEmpty(),
                "a lodestone in a pre-1.21.5 HandItems mainhand slot is blanked");
        ItemContainerContents offhandShulker = itemFrom(((ListTag) zombie.get("HandItems")).get(1))
                .get(DataComponents.CONTAINER);
        assertNotNull(offhandShulker, "the offhand shulker keeps its container component");
        assertTrue(targetOf(offhandShulker.nonEmptyItemCopyStream().toList().get(0)).isEmpty(),
                "a lodestone nested in a shulker in a pre-1.21.5 HandItems slot is blanked");
        assertTrue(targetOf(itemFrom(((ListTag) zombie.get("ArmorItems")).get(3))).isEmpty(),
                "a lodestone in a pre-1.21.5 ArmorItems slot is blanked");
    }

    @Test
    void scrubEntityBlanksBeehiveHeldByAnEntity() {
        CompoundTag frame = entity("minecraft:item_frame");
        frame.put("Item", itemNbt(beehiveWithBeeFlowerPos()));

        ItemLocationScrub.scrubEntity(frame);

        assertFalse(beeFlowerPresent(itemFrom(frame.get("Item"))),
                "a beehive held in an item frame has its bee flower_pos blanked");
    }

    @Test
    void scrubEntityRecursesIntoPassengers() {
        CompoundTag boat = entity("minecraft:boat");
        CompoundTag rider = entity("minecraft:zombie");
        CompoundTag riderEquip = new CompoundTag();
        riderEquip.put("mainhand", itemNbt(lodestoneCompass()));
        rider.put("equipment", riderEquip);
        ListTag passengers = new ListTag();
        passengers.add(rider);
        boat.put("Passengers", passengers);

        ItemLocationScrub.scrubEntity(boat);

        CompoundTag scrubbedRider = (CompoundTag) ((ListTag) boat.get("Passengers")).get(0);
        assertTrue(targetOf(itemFrom(((CompoundTag) scrubbedRider.get("equipment")).get("mainhand"))).isEmpty(),
                "a compass on a passenger mob's equipment is blanked");
    }

    @Test
    void scrubEntityWithoutCoordinateItemsIsNoop() {
        CompoundTag zombie = entity("minecraft:zombie");
        CompoundTag equipment = new CompoundTag();
        equipment.put("mainhand", itemNbt(new ItemStack(Items.DIAMOND_SWORD)));
        zombie.put("equipment", equipment);
        CompoundTag before = zombie.copy();

        ItemLocationScrub.scrubEntity(zombie);

        assertEquals(before, zombie, "an entity holding no coordinate item round-trips unchanged");
    }
}
