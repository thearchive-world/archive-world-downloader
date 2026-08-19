// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Headless open-drive shared by the container axes. A container's contents reach the client only through its open menu,
 * and the bind in {@code LiveCaptureSession.captureOpenContainer} resolves the target the CLICK carried, so a test must
 * land the crosshair on the target before opening: the resolved hit result is what the drive hands the use call, and so
 * what the loader's use hook latches. In spectator, where no click is observed, the crosshair is the bind itself. The
 * pick is forced explicitly through {@link Minecraft#gameRenderer} rather than waiting for a render frame, so the
 * resolve does not depend on a frame landing between ticks, and the look is set from the eye anchor because that is
 * what the pick raycasts from.
 */
@SuppressWarnings("UnstableApiUsage")
final class ContainerDriver {
    static final String PLANTED_ITEM = "minecraft:diamond";

    private ContainerDriver() {}

    /** Aim the player's eyes at {@code target}; the forced pick then resolves {@code hitResult} from this look. */
    static void aimEyesAt(ClientGameTestContext context, Vec3 target) {
        context.runOnClient(client -> client.player.lookAt(EntityAnchorArgument.Anchor.EYES, target));
    }

    /**
     * Force the pick each poll and wait until {@code onTarget} holds. A timeout throws naming {@code target} and the
     * actual hit, so a red run points at where the crosshair went instead.
     */
    static void awaitCrosshair(ClientGameTestContext context, Predicate<Minecraft> onTarget, String target) {
        for (int tick = 0; tick <= ClientGameTestContext.DEFAULT_TIMEOUT; tick++) {
            boolean resolved = context.computeOnClient(client -> {
                client.gameRenderer.pick(1.0f);
                return onTarget.test(client);
            });
            if (resolved) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError("crosshair never landed on " + target + "; "
                + context.computeOnClient(ContainerDriver::describeAim));
    }

    /**
     * Pump {@code run} until the open menu's first slot holds {@code item}, so the next bound ticks stash a populated
     * container rather than an empty one (the contents sync a tick or two after the open). Keys on {@link ChestMenu}
     * slot 0, so it fits the chest-shaped kinds (single, double, ender chest); kinds whose menu is not a
     * {@code ChestMenu} (lectern, chested animal) wait on {@link #awaitMenuReady} instead.
     */
    static void awaitMenuSlotItem(ClientGameTestContext context, CaptureDriver run, Item item) {
        awaitMenuReady(context, run, client -> client.player.containerMenu instanceof ChestMenu menu
                && menu.getSlot(0).getItem().is(item), "container's slot 0");
    }

    /**
     * Pump {@code run} until {@code ready} holds on the open menu, so the next bound ticks stash populated contents
     * rather than an empty menu (the synced slots arrive a tick or two after the open). The kind-appropriate readiness
     * signal generalizes the chest-shaped slot-0 wait across menu types.
     */
    static void awaitMenuReady(ClientGameTestContext context, CaptureDriver run, Predicate<Minecraft> ready,
            String description) {
        for (int tick = 0; tick < 100; tick++) {
            if (context.computeOnClient(ready::test)) {
                return;
            }
            run.tick(1);
        }
        throw new AssertionError("the opened menu never synced its " + description);
    }

    /** Whether the client's crosshair is resolved onto the block at {@code pos} (the click precondition). */
    static boolean isLookingAt(Minecraft client, BlockPos pos) {
        return client.hitResult instanceof BlockHitResult blockHit
                && blockHit.getType() == HitResult.Type.BLOCK
                && blockHit.getBlockPos().equals(pos);
    }

    /** The center point of {@code block}, the aim target for the crosshair. */
    static Vec3 center(BlockPos block) {
        return new Vec3(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5);
    }

    /** Set a chest at {@code pos} on the server and plant the distinctive item in its first slot. */
    static void placeFilledChest(TestServerContext server, BlockPos pos) {
        String at = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        server.runCommand("setblock " + at + " minecraft:chest");
        server.runCommand("item replace block " + at + " container.0 with " + PLANTED_ITEM + " 7");
    }

    /** The item ids captured in the chest block entity at {@code pos} on disk (region {@code block_entities}). */
    static List<String> capturedChestItems(Path saveRoot, BlockPos pos) {
        CompoundTag chunk = CaptureReadback.readChunk(saveRoot, new ChunkPos(pos))
                .orElseThrow(() -> new AssertionError("captured chunk for chest " + pos + " is missing from the save"));
        return CaptureReadback.blockEntityAt(chunk, pos)
                .map(CaptureReadback::itemIds)
                .orElseThrow(() -> new AssertionError("no chest block entity at " + pos + " in the captured chunk"));
    }

    /** Item frames near the player that already carry a filled map client-side (so the frame's item has synced). */
    static long framedMapCount(Minecraft client) {
        return client.level.getEntities((Entity) null, client.player.getBoundingBox().inflate(48)).stream()
                .filter(ItemFrame.class::isInstance)
                .map(ItemFrame.class::cast)
                .filter(frame -> frame.getItem().is(Items.FILLED_MAP))
                .count();
    }

    static boolean contains(long[] values, long target) {
        for (long value : values) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }

    private static String describeAim(Minecraft client) {
        HitResult hit = client.hitResult;
        String where;
        if (hit instanceof BlockHitResult) {
            BlockHitResult blockHit = (BlockHitResult) hit;
            where = "BLOCK@" + blockHit.getBlockPos();
        } else if (hit instanceof EntityHitResult) {
            EntityHitResult entityHit = (EntityHitResult) hit;
            where = "ENTITY@" + entityHit.getEntity().getType();
        } else {
            where = hit == null ? "null" : String.valueOf(hit.getType());
        }
        return "hitResult=" + where + " eyePos=" + client.player.getEyePosition()
                + " yRot=" + client.player.getYRot() + " xRot=" + client.player.getXRot();
    }
}
