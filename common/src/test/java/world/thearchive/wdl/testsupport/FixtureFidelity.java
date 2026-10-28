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
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import org.jspecify.annotations.Nullable;

public final class FixtureFidelity {
    public static final String KEEP_PACKED = "keepPacked";

    private static @Nullable Map<Identifier, BlockState> representativeStates;

    private FixtureFidelity() {}

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

    public static CompoundTag save(BlockEntity blockEntity) {
        return blockEntity.saveWithFullMetadata(TestRegistries.frozen());
    }

    public static CompoundTag blockEntityShape(String blockEntityId, int x, int y, int z) {
        return save(newBlockEntity(blockEntityId, x, y, z));
    }

    public static void assertBlockEntityShape(CompoundTag blockEntityTag) {
        CompoundTag subject = blockEntityTag.copy();
        subject.remove(KEEP_PACKED);

        String id = subject.getStringOr("id", "");
        BlockPos pos = new BlockPos(subject.getIntOr("x", 0), subject.getIntOr("y", 0), subject.getIntOr("z", 0));
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

    public static void assertItemsHolderShape(CompoundTag holderTag) {
        Tag rawItems = holderTag.get("Items");
        if (rawItems != null && !(rawItems instanceof ListTag)) {
            throw new AssertionError("Fixture fidelity: the holder's Items is " + rawItems
                    + ", which no producer writes; an empty read of it would pass this check silently");
        }
        ListTag items = holderTag.getListOrEmpty("Items");
        RegistryAccess registries = TestRegistries.frozen();

        NonNullList<ItemStack> stacks = NonNullList.withSize(containerSize(items), ItemStack.EMPTY);
        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, registries, holderTag);
        ContainerHelper.loadAllItems(input, stacks);

        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        ContainerHelper.saveAllItems(output, stacks);

        List<String> divergences = new ArrayList<>();
        diff("Items", output.buildResult().getListOrEmpty("Items"), items, divergences);
        if (!divergences.isEmpty()) {
            throw new AssertionError(message("items holder", divergences, output.buildResult(), holderTag));
        }
    }

    private static int containerSize(ListTag items) {
        int highest = -1;
        for (int i = 0; i < items.size(); i++) {
            highest = Math.max(highest, items.getCompoundOrEmpty(i).getByteOr("Slot", (byte) 0));
            highest = Math.max(highest, i);
        }
        return highest + 1;
    }

    private static BlockState representativeState(String blockEntityId) {
        Identifier id = Identifier.parse(blockEntityId);
        BlockState state = representativeStates().get(id);
        if (state == null) {
            throw new AssertionError("Fixture fidelity: no block hosts block-entity type " + blockEntityId
                    + "; a fixture naming a type vanilla does not register cannot carry a producer's shape");
        }
        return state;
    }

    private static synchronized Map<Identifier, BlockState> representativeStates() {
        if (representativeStates != null) {
            return representativeStates;
        }
        TestRegistries.frozen();
        Map<Identifier, BlockState> states = new HashMap<>();
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
            for (String key : producedCompound.keySet()) {
                diff(child(path, key), producedCompound.get(key), fixtureCompound.get(key), divergences);
            }
            for (String key : fixtureCompound.keySet()) {
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
