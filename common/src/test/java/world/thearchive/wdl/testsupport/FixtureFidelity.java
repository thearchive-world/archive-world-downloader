// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.NonNullList;
import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTBase;
import net.minecraft.util.ResourceLocation;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.block.state.IBlockState;
import org.jspecify.annotations.Nullable;

/**
 * The fixture-fidelity gate: proves a fixture tag carries the shape the vanilla producer that would have written it
 * actually emits.
 *
 * <p>The property gated is a fixed point of vanilla's own decode then encode. A block-entity tag is fed to
 * {@link TileEntity#create} and saved back through {@link TileEntity#writeToNBT}; an {@code "Items"} holder is fed to
 * {@link ItemStackHelper#loadAllItems} and saved back through {@link ItemStackHelper#saveAllItems}. A fixture built
 * from a mental model rather than from the producer differs from its own round trip, because vanilla writes keys
 * unconditionally that a hand-built tag omits ({@code "Slot"} and {@code "Count"} on every item entry, and each block
 * entity's own always-written state). Such a fixture collapses the cases a test means to distinguish, so the test
 * passes for a reason unrelated to the behavior it names.
 *
 * <p>Nothing here is a key list to maintain. The producer is called, so the expected shape follows the band the tests
 * compile against.
 */
public final class FixtureFidelity {
    /**
     * Written by the chunk layer around a saved block entity, not by the block entity itself, so it is invisible to the
     * block-entity round trip and is set aside before it and restored after.
     */
    public static final String KEEP_PACKED = "keepPacked";

    private static @Nullable Map<ResourceLocation, IBlockState> representativeStates;

    private FixtureFidelity() {}

    /** A freshly placed block entity of type {@code blockEntityId} at {@code x/y/z}, with no world behind it. */
    public static TileEntity newBlockEntity(String blockEntityId, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        IBlockState state = representativeState(blockEntityId);
        TileEntity blockEntity = state.getBlock().createTileEntity(null, state);
        if (blockEntity == null) {
            throw new AssertionError("Fixture fidelity: " + state.getBlock() + " hosts no block entity at " + pos);
        }
        // The fresh block entity carries no position, so it sits at the origin; set its position or save writes
        // x/y/z of 0,0,0 instead of the requested cell.
        blockEntity.setPos(pos);
        return blockEntity;
    }

    /** What vanilla writes for {@code blockEntity}, the same call the chunk save makes. */
    public static NBTTagCompound save(TileEntity blockEntity) {
        return blockEntity.writeToNBT(new NBTTagCompound());
    }

    /**
     * The tag vanilla writes for a freshly placed block entity of {@code blockEntityId} at {@code x/y/z}: every
     * always-written key at its default, and nothing else.
     */
    public static NBTTagCompound blockEntityShape(String blockEntityId, int x, int y, int z) {
        return save(newBlockEntity(blockEntityId, x, y, z));
    }

    /**
     * Fail unless {@code blockEntityTag} is exactly what vanilla would write for the state it describes.
     * {@link #KEEP_PACKED} is exempt, being written outside the block entity's own save.
     */
    public static void assertBlockEntityShape(NBTTagCompound blockEntityTag) {
        NBTTagCompound subject = blockEntityTag.copy();
        subject.removeTag(KEEP_PACKED);

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

    /**
     * Fail unless {@code holderTag}'s {@code "Items"} list is exactly what {@link ItemStackHelper#saveAllItems} emits
     * for the stacks it decodes to. Catches an entry missing {@code "Slot"}, whose load silently lands every such entry
     * on slot 0.
     */
    public static void assertItemsHolderShape(NBTTagCompound holderTag) {
        NBTBase rawItems = holderTag.getTag("Items");
        if (rawItems != null && !(rawItems instanceof NBTTagList)) {
            throw new AssertionError("Fixture fidelity: the holder's Items is " + rawItems
                    + ", which no producer writes; an empty read of it would pass this check silently");
        }
        NBTTagList items = holderTag.getTagList("Items", 10);

        NonNullList<ItemStack> stacks = NonNullList.withSize(containerSize(items), ItemStack.EMPTY);
        ItemStackHelper.loadAllItems(holderTag, stacks);

        NBTTagCompound output = ItemStackHelper.saveAllItems(new NBTTagCompound(), stacks);

        List<String> divergences = new ArrayList<>();
        diff("Items", output.getTagList("Items", 10), items, divergences);
        if (!divergences.isEmpty()) {
            throw new AssertionError(message("items holder", divergences, output, holderTag));
        }
    }

    /**
     * A container wide enough to hold every slot the list names, so the round trip is testing the entries' shape rather
     * than the load dropping an out-of-range one.
     */
    private static int containerSize(NBTTagList items) {
        int highest = -1;
        for (int i = 0; i < items.tagCount(); i++) {
            highest = Math.max(highest, items.getCompoundTagAt(i).getByte("Slot"));
            highest = Math.max(highest, i);
        }
        return highest + 1;
    }

    /** The default state of some block hosting {@code blockEntityId}, for the load side of the round trip. */
    private static IBlockState representativeState(String blockEntityId) {
        ResourceLocation id = new ResourceLocation(blockEntityId);
        IBlockState state = representativeStates().get(id);
        if (state == null) {
            throw new AssertionError("Fixture fidelity: no block hosts block-entity type " + blockEntityId
                    + "; a fixture naming a type vanilla does not register cannot carry a producer's shape");
        }
        return state;
    }

    private static synchronized Map<ResourceLocation, IBlockState> representativeStates() {
        if (representativeStates != null) {
            return representativeStates;
        }
        TestRegistries.bootstrap();
        Map<ResourceLocation, IBlockState> states = new HashMap<>();
        for (Block block : Block.REGISTRY) {
            if (!(block instanceof ITileEntityProvider)) {
                continue;
            }
            IBlockState state = block.getDefaultState();
            TileEntity blockEntity = block.createTileEntity(null, state);
            if (blockEntity == null) {
                continue;
            }
            ResourceLocation key = TileEntity.getKey(blockEntity.getClass());
            if (key == null) {
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
