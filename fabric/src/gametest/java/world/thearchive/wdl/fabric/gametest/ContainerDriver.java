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
 * The look is set from the eye anchor because that is what the pick raycasts from.
 */
@SuppressWarnings("UnstableApiUsage")
final class ContainerDriver {
    static final String PLANTED_ITEM = "minecraft:diamond";

    private ContainerDriver() {}

    static void aimEyesAt(ClientGameTestContext context, Vec3 target) {
        context.runOnClient(client -> client.player.lookAt(EntityAnchorArgument.Anchor.EYES, target));
    }

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

    static void awaitMenuSlotItem(ClientGameTestContext context, CaptureDriver run, Item item) {
        awaitMenuReady(context, run, client -> client.player.containerMenu instanceof ChestMenu menu
                && menu.getSlot(0).getItem().is(item), "container's slot 0");
    }

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

    static boolean isLookingAt(Minecraft client, BlockPos pos) {
        return client.hitResult instanceof BlockHitResult blockHit
                && blockHit.getType() == HitResult.Type.BLOCK
                && blockHit.getBlockPos().equals(pos);
    }

    static Vec3 center(BlockPos block) {
        return new Vec3(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5);
    }

    static void placeFilledChest(TestServerContext server, BlockPos pos) {
        String at = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        server.runCommand("setblock " + at + " minecraft:chest");
        server.runCommand("item replace block " + at + " container.0 with " + PLANTED_ITEM + " 7");
    }

    static List<String> capturedChestItems(Path saveRoot, BlockPos pos) {
        CompoundTag chunk = CaptureReadback.readChunk(saveRoot, new ChunkPos(pos))
                .orElseThrow(() -> new AssertionError("captured chunk for chest " + pos + " is missing from the save"));
        return CaptureReadback.blockEntityAt(chunk, pos)
                .map(CaptureReadback::itemIds)
                .orElseThrow(() -> new AssertionError("no chest block entity at " + pos + " in the captured chunk"));
    }

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
        if (hit instanceof BlockHitResult blockHit) {
            where = "BLOCK@" + blockHit.getBlockPos();
        } else if (hit instanceof EntityHitResult entityHit) {
            where = "ENTITY@" + entityHit.getEntity().getType();
        } else {
            where = hit == null ? "null" : String.valueOf(hit.getType());
        }
        return "hitResult=" + where + " eyePos=" + client.player.getEyePosition()
                + " yRot=" + client.player.getYRot() + " xRot=" + client.player.getXRot();
    }
}
