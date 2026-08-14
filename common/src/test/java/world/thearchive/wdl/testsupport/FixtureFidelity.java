// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The fixture-fidelity gate: proves a fixture tag carries the shape the vanilla producer that would have written it
 * actually emits.
 *
 * <p>The property gated is a fixed point of vanilla's own decode then encode. A block-entity tag is fed to
 * {@link BlockEntity#loadStatic} and saved back through {@link BlockEntity#saveWithFullMetadata}; an {@code "Items"}
 * holder is fed to {@link ContainerHelper#loadAllItems} and saved back through {@link ContainerHelper#saveAllItems}. A
 * fixture built from a mental model rather than from the producer differs from its own round trip, because vanilla
 * writes keys unconditionally that a hand-built tag omits ({@code "Slot"} and {@code "count"} on every item entry,
 * {@code "components"} on every block entity, and each block entity's own always-written state). Such a fixture
 * collapses the cases a test means to distinguish, so the test passes for a reason unrelated to the behavior it names.
 *
 * <p>Nothing here is a key list to maintain. The producer is called, so the expected shape follows the band the tests
 * compile against.
 */
public final class FixtureFidelity {
    /**
     * Written by the chunk layer around a saved block entity ({@code LevelChunk.getBlockEntityNbtForSaving}), not by
     * the block entity itself, so it is invisible to the block-entity round trip and is set aside before it and
     * restored after.
     */
    public static final String KEEP_PACKED = "keepPacked";

    private static @Nullable Map<ResourceLocation, BlockState> representativeStates;

    private FixtureFidelity() {}

    /** A freshly placed block entity of type {@code blockEntityId} at {@code x/y/z}, with no level behind it. */
    public static BlockEntity newBlockEntity(String blockEntityId, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = representativeState(blockEntityId);
        EntityBlock block = (EntityBlock) state.getBlock();
        BlockEntity blockEntity = block.newBlockEntity(pos, state);
        if (blockEntity == null) {
            throw new AssertionError("Fixture fidelity: " + state.getBlock() + " hosts no block entity at " + pos);
        }
        return blockEntity;
    }

    /** What vanilla writes for {@code blockEntity}, the same call the chunk save makes. */
    public static CompoundTag save(BlockEntity blockEntity) {
        return blockEntity.saveWithFullMetadata(TestRegistries.frozen());
    }

    /**
     * The tag vanilla writes for a freshly placed block entity of {@code blockEntityId} at {@code x/y/z}: every
     * always-written key at its default, and nothing else.
     */
    public static CompoundTag blockEntityShape(String blockEntityId, int x, int y, int z) {
        return save(newBlockEntity(blockEntityId, x, y, z));
    }

    /**
     * Fail unless {@code blockEntityTag} is exactly what vanilla would write for the state it describes.
     * {@link #KEEP_PACKED} is exempt, being written outside the block entity's own save.
     */
    public static void assertBlockEntityShape(CompoundTag blockEntityTag) {
        CompoundTag subject = blockEntityTag.copy();
        subject.remove(KEEP_PACKED);

        String id = subject.getString("id");
        BlockPos pos = new BlockPos(subject.getInt("x"), subject.getInt("y"), subject.getInt("z"));
        BlockState state = representativeState(id);
        RegistryAccess registries = TestRegistries.frozen();

        BlockEntity blockEntity = BlockEntity.loadStatic(pos, state, subject, registries);
        if (blockEntity == null) {
            throw new AssertionError("Fixture fidelity: the fixture at " + pos
                    + " does not load as a block entity: " + subject);
        }

        CompoundTag produced = blockEntity.saveWithFullMetadata(registries);
        List<String> divergences = new ArrayList<>();
        diff("", produced, subject, divergences);
        if (!divergences.isEmpty()) {
            throw new AssertionError(message("block entity " + id + " at " + pos, divergences, produced, subject));
        }
    }

    /**
     * Fail unless {@code holderTag}'s {@code "Items"} list is exactly what {@link ContainerHelper#saveAllItems} emits
     * for the stacks it decodes to. Catches an entry missing {@code "Slot"}, whose {@code ItemStackWithSlot} decode
     * silently lands every such entry on slot 0.
     */
    public static void assertItemsHolderShape(CompoundTag holderTag) {
        Tag rawItems = holderTag.get("Items");
        if (rawItems != null && !(rawItems instanceof ListTag)) {
            throw new AssertionError("Fixture fidelity: the holder's Items is " + rawItems
                    + ", which no producer writes; an empty read of it would pass this check silently");
        }
        ListTag items = holderTag.getList("Items", Tag.TAG_COMPOUND);
        RegistryAccess registries = TestRegistries.frozen();

        NonNullList<ItemStack> stacks = NonNullList.withSize(containerSize(items), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(holderTag, stacks, registries);

        CompoundTag output = ContainerHelper.saveAllItems(new CompoundTag(), stacks, registries);

        List<String> divergences = new ArrayList<>();
        diff("Items", output.getList("Items", Tag.TAG_COMPOUND), items, divergences);
        if (!divergences.isEmpty()) {
            throw new AssertionError(message("items holder", divergences, output, holderTag));
        }
    }

    /**
     * A container wide enough to hold every slot the list names, so the round trip is testing the entries' shape rather
     * than {@code ItemStackWithSlot.isValidInContainer} dropping an out-of-range one.
     */
    private static int containerSize(ListTag items) {
        int highest = -1;
        for (int i = 0; i < items.size(); i++) {
            highest = Math.max(highest, items.getCompound(i).getByte("Slot"));
            highest = Math.max(highest, i);
        }
        return highest + 1;
    }

    /** The default state of some block hosting {@code blockEntityId}, for the load side of the round trip. */
    private static BlockState representativeState(String blockEntityId) {
        ResourceLocation id = new ResourceLocation(blockEntityId);
        BlockState state = representativeStates().get(id);
        if (state == null) {
            throw new AssertionError("Fixture fidelity: no block hosts block-entity type " + blockEntityId
                    + "; a fixture naming a type vanilla does not register cannot carry a producer's shape");
        }
        return state;
    }

    private static synchronized Map<ResourceLocation, BlockState> representativeStates() {
        if (representativeStates != null) {
            return representativeStates;
        }
        TestRegistries.frozen();
        Map<ResourceLocation, BlockState> states = new HashMap<>();
        BlockPos probe = new BlockPos(0, 0, 0);
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!(block instanceof EntityBlock entityBlock)) {
                continue;
            }
            BlockState state = block.defaultBlockState();
            BlockEntity blockEntity = entityBlock.newBlockEntity(probe, state);
            if (blockEntity == null) {
                continue;
            }
            BlockEntityType<?> type = blockEntity.getType();
            states.putIfAbsent(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type), state);
        }
        representativeStates = states;
        return states;
    }

    private static void diff(String path, Tag produced, @Nullable Tag fixture, List<String> divergences) {
        if (fixture == null) {
            divergences.add(path + ": the producer writes " + produced + ", the fixture omits it");
            return;
        }
        if (produced instanceof CompoundTag producedCompound && fixture instanceof CompoundTag fixtureCompound) {
            for (String key : producedCompound.getAllKeys()) {
                diff(child(path, key), producedCompound.get(key), fixtureCompound.get(key), divergences);
            }
            for (String key : fixtureCompound.getAllKeys()) {
                if (!producedCompound.contains(key)) {
                    divergences.add(child(path, key) + ": the fixture carries " + fixtureCompound.get(key)
                            + ", the producer writes no such key");
                }
            }
            return;
        }
        if (produced instanceof ListTag producedList && fixture instanceof ListTag fixtureList) {
            if (producedList.size() != fixtureList.size()) {
                divergences.add(path + ": the producer writes " + producedList.size() + " element(s), the fixture "
                        + fixtureList.size());
                return;
            }
            for (int i = 0; i < producedList.size(); i++) {
                diff(path + "[" + i + "]", producedList.get(i), fixtureList.get(i), divergences);
            }
            return;
        }
        if (!produced.equals(fixture)) {
            divergences.add(path + ": the producer writes " + produced + ", the fixture " + fixture);
        }
    }

    private static String child(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private static String message(String subject, List<String> divergences, CompoundTag produced,
            CompoundTag fixture) {
        StringBuilder text = new StringBuilder();
        text.append("Fixture fidelity: the ").append(subject)
                .append(" fixture is not the shape its vanilla producer emits.\n")
                .append("A fixture that omits a key the producer always writes collapses the cases the test means\n")
                .append("to distinguish, so the test can pass for a reason unrelated to the behavior it names.\n")
                .append("Build the fixture from the producer (BlockEntityFixtures / ItemFixtures), not by hand.\n");
        for (String divergence : divergences) {
            text.append("  - ").append(divergence).append('\n');
        }
        text.append("producer: ").append(produced).append('\n');
        text.append("fixture:  ").append(fixture);
        return text.toString();
    }
}
