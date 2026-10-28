// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.impl.ItemListNbt;

public final class FixtureFidelity {
    private static @Nullable Map<String, IBlockState> representativeStates;

    private FixtureFidelity() {}

    public static TileEntity newBlockEntity(String blockEntityId, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        IBlockState state = representativeState(blockEntityId);
        TileEntity blockEntity = state.getBlock().createTileEntity(null, state);
        if (blockEntity == null) {
            throw new AssertionError("Fixture fidelity: " + state.getBlock() + " hosts no block entity at " + pos);
        }
        blockEntity.setPos(pos);
        return blockEntity;
    }

    public static NBTTagCompound save(TileEntity blockEntity) {
        return blockEntity.writeToNBT(new NBTTagCompound());
    }

    public static NBTTagCompound blockEntityShape(String blockEntityId, int x, int y, int z) {
        return save(newBlockEntity(blockEntityId, x, y, z));
    }

    public static void assertBlockEntityShape(NBTTagCompound blockEntityTag) {
        NBTTagCompound subject = blockEntityTag.copy();

        String id = subject.getString("id");
        BlockPos pos = new BlockPos(subject.getInteger("x"), subject.getInteger("y"), subject.getInteger("z"));
        representativeState(id);

        TileEntity blockEntity = TileEntity.create(null, subject);
        if (blockEntity == null) {
            throw new AssertionError("Fixture fidelity: the fixture at " + pos
                    + " does not load as a block entity: " + subject);
        }

        NBTTagCompound produced = blockEntity.writeToNBT(new NBTTagCompound());
        List<String> divergences = new ArrayList<>();
        diff("", produced, subject, divergences);
        if (!divergences.isEmpty()) {
            throw new AssertionError(message("block entity " + id + " at " + pos, divergences, produced, subject));
        }
    }

    public static void assertItemsHolderShape(NBTTagCompound holderTag) {
        NBTBase rawItems = holderTag.getTag("Items");
        if (rawItems != null && !(rawItems instanceof NBTTagList)) {
            throw new AssertionError("Fixture fidelity: the holder's Items is " + rawItems
                    + ", which no producer writes; an empty read of it would pass this check silently");
        }
        NBTTagList items = holderTag.getTagList("Items", 10);

        ItemStack[] stacks = new ItemStack[containerSize(items)];
        ItemListNbt.loadAllItems(holderTag, stacks);

        NBTTagCompound output = new NBTTagCompound();
        ItemListNbt.saveAllItems(output, stacks);

        List<String> divergences = new ArrayList<>();
        diff("Items", output.getTagList("Items", 10), items, divergences);
        if (!divergences.isEmpty()) {
            throw new AssertionError(message("items holder", divergences, output, holderTag));
        }
    }

    private static int containerSize(NBTTagList items) {
        int highest = -1;
        for (int i = 0; i < items.tagCount(); i++) {
            highest = Math.max(highest, items.getCompoundTagAt(i).getByte("Slot"));
            highest = Math.max(highest, i);
        }
        return highest + 1;
    }

    private static IBlockState representativeState(String blockEntityId) {
        IBlockState state = representativeStates().get(blockEntityId);
        if (state == null) {
            throw new AssertionError("Fixture fidelity: no block hosts block-entity type " + blockEntityId
                    + "; a fixture naming a type vanilla does not register cannot carry a producer's shape");
        }
        return state;
    }

    private static synchronized Map<String, IBlockState> representativeStates() {
        if (representativeStates != null) {
            return representativeStates;
        }
        TestRegistries.bootstrap();
        Map<String, IBlockState> states = new HashMap<>();
        for (Block block : Block.REGISTRY) {
            if (!(block instanceof ITileEntityProvider)) {
                continue;
            }
            IBlockState state = block.getDefaultState();
            TileEntity blockEntity = block.createTileEntity(null, state);
            if (blockEntity == null) {
                continue;
            }
            String key;
            try {
                key = save(blockEntity).getString("id");
            } catch (RuntimeException unmapped) {
                continue;
            }
            states.putIfAbsent(key, state);
        }
        representativeStates = states;
        return states;
    }

    private static void diff(String path, NBTBase produced, @Nullable NBTBase fixture, List<String> divergences) {
        if (fixture == null) {
            divergences.add(path + ": the producer writes " + produced + ", the fixture omits it");
            return;
        }
        if (produced instanceof NBTTagCompound && fixture instanceof NBTTagCompound) {
            NBTTagCompound producedCompound = (NBTTagCompound) produced;
            NBTTagCompound fixtureCompound = (NBTTagCompound) fixture;
            for (String key : producedCompound.getKeySet()) {
                diff(child(path, key), producedCompound.getTag(key), fixtureCompound.getTag(key), divergences);
            }
            for (String key : fixtureCompound.getKeySet()) {
                if (!producedCompound.hasKey(key)) {
                    divergences.add(child(path, key) + ": the fixture carries " + fixtureCompound.getTag(key)
                            + ", the producer writes no such key");
                }
            }
            return;
        }
        if (produced instanceof NBTTagList && fixture instanceof NBTTagList) {
            NBTTagList producedList = (NBTTagList) produced;
            NBTTagList fixtureList = (NBTTagList) fixture;
            if (producedList.tagCount() != fixtureList.tagCount()) {
                divergences.add(path + ": the producer writes " + producedList.tagCount() + " element(s), the fixture "
                        + fixtureList.tagCount());
                return;
            }
            for (int i = 0; i < producedList.tagCount(); i++) {
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

    private static String message(String subject, List<String> divergences, NBTTagCompound produced,
            NBTTagCompound fixture) {
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
