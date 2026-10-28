// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAddExperienceOrbPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.SendRangeEstimator;
import world.thearchive.wdl.core.SendRangeSampler;

/**
 * {@code Respawn} and {@code Login} are the two inbound packets that announce the world the stream has moved to, so
 * both are routed to the accumulator's dimension marker and to the sampler, whose id book the rebuilt level invalidates
 * either way. Reading the client's live level here instead would be wrong by a tick: the tee sees the packet on the
 * Netty thread before the main thread applies it, so the entity spawns that follow it on the wire would be stamped with
 * the world the player has left.
 */
final class EntityPacketCapture
        extends
        EntityPacketAccumulator<Packet<?>, SynchedEntityData.DataValue<?>, EquipmentEntry> {
    private static volatile @Nullable EntityPacketCapture active;

    private static final Set<EntityType<?>> DECORATION_TYPES = Set.of(
            EntityType.ITEM_FRAME, EntityType.GLOW_ITEM_FRAME, EntityType.PAINTING, EntityType.ARMOR_STAND);

    /**
     * The player-mountables: a riding player raises their broadcast range to the player's own (vanilla's tracked range
     * takes the max over indirect passengers), and outside a bundle passenger state is not visible at spawn time, so a
     * boosted sample would over-claim the range. The Display types, where the version has them: category-configured
     * servers commonly track their display category farther than the decorations' categories (a plugin hologram near
     * the player would over-claim teal). Interaction, where the version has it, is range 10, non-mountable, and absent
     * from vanilla worlds; its real-world use is plugin frameworks that teleport interaction and display pairs to
     * follow players, the hazard profile that excluded the Displays.
     */
    private static final Set<EntityType<?>> RANGE_SAMPLING_EXCLUSIONS = Set.of(
            EntityType.BOAT, EntityType.CHEST_BOAT,
            EntityType.HORSE, EntityType.DONKEY, EntityType.SKELETON_HORSE, EntityType.ZOMBIE_HORSE,
            EntityType.CAMEL, EntityType.LLAMA, EntityType.TRADER_LLAMA,
            EntityType.PIG, EntityType.STRIDER,
            EntityType.BLOCK_DISPLAY, EntityType.ITEM_DISPLAY, EntityType.TEXT_DISPLAY,
            EntityType.INTERACTION);

    private final Set<String> receivedFrames = ConcurrentHashMap.newKeySet();

    private final boolean dumpReceivedFrames;

    private final SendRangeEstimator sendRange;

    private final SendRangeSampler sampler;

    EntityPacketCapture(boolean dumpReceivedFrames, SendRangeEstimator sendRange, SendRangeSampler sampler,
            String dimensionId) {
        super(dimensionId);
        this.dumpReceivedFrames = dumpReceivedFrames;
        this.sendRange = sendRange;
        this.sampler = sampler;
    }

    SendRangeSampler sampler() {
        return sampler;
    }

    static void activate(EntityPacketCapture capture) {
        active = capture;
    }

    static void deactivate(EntityPacketCapture capture) {
        if (active == capture) {
            active = null;
        }
    }

    public static @Nullable EntityPacketCapture active() {
        return active;
    }

    public void accept(Packet<?> packet, IntSet bundleNamedIds) {
        if (packet instanceof ClientboundAddEntityPacket add) {
            onAdd(add, bundleNamedIds);
        } else if (packet instanceof ClientboundAddExperienceOrbPacket orb) {
            onAddExperienceOrb(orb);
        } else if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
            onRemove(remove);
        } else if (packet instanceof ClientboundPlayerPositionPacket) {
            sampler.onAnomalyPacket();
        } else if (packet instanceof ClientboundRespawnPacket respawn) {
            enterDimension(respawn.getDimension().location().toString());
            sampler.onRespawn();
        } else if (packet instanceof ClientboundLoginPacket login) {
            enterDimension(login.dimension().location().toString());
            sampler.onRespawn();
        } else if (packet instanceof ClientboundSetCameraPacket) {
            sampler.onSetCamera();
        } else if (packet instanceof ClientboundSetEntityDataPacket synced) {
            onSetData(synced);
        } else if (packet instanceof ClientboundSetEquipmentPacket equip) {
            onSetEquipment(equip);
        } else if (packet instanceof ClientboundSetPassengersPacket passengers) {
            onSetPassengers(passengers);
        } else if (packet instanceof ClientboundSetEntityLinkPacket link) {
            onSetLink(link);
        } else if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            onTeleport(teleport);
        }
    }

    private void onAdd(ClientboundAddEntityPacket add, IntSet bundleNamedIds) {
        observeSendRange(add, bundleNamedIds);
        if (add.getType() == EntityType.PLAYER) {
            return; // the client builds a RemotePlayer for PLAYER, and players are not saved as entities
        }
        EntityPos pos = new EntityPos(add.getX(), add.getY(), add.getZ(), add.getYRot(), add.getXRot());
        spawn(add.getId(), add.getUUID(), chunkKey(add.getX(), add.getZ()), pos, add);
        if (dumpReceivedFrames
                && (add.getType() == EntityType.ITEM_FRAME || add.getType() == EntityType.GLOW_ITEM_FRAME)) {
            receivedFrames.add(Mth.floor(add.getX()) + " " + Mth.floor(add.getY()) + " " + Mth.floor(add.getZ())
                    + " " + add.getData());
        }
    }

    private void onAddExperienceOrb(ClientboundAddExperienceOrbPacket orb) {
        EntityPos pos = new EntityPos(orb.getX(), orb.getY(), orb.getZ(), 0.0F, 0.0F);
        spawn(orb.getId(), syntheticUuid(orb.getId()), chunkKey(orb.getX(), orb.getZ()), pos, orb);
    }

    private static UUID syntheticUuid(int id) {
        return new UUID(0L, id & 0xFFFFFFFFL);
    }

    static boolean qualifies(EntityType<?> type) {
        return DECORATION_TYPES.contains(type)
                || (type.clientTrackingRange() == 10 && !RANGE_SAMPLING_EXCLUSIONS.contains(type));
    }

    /**
     * The commit rule drops an arrival any same-bundle {@code SetPassengers} names anywhere: a ridden vehicle pairs at
     * its passenger's boosted range, so its arrival distance over-claims. Reads the player and dimension from the
     * client singleton at netty time; the render-distance bound caps what a stale read can commit.
     */
    private void observeSendRange(ClientboundAddEntityPacket add, IntSet bundleNamedIds) {
        if (!qualifies(add.getType())) {
            return;
        }
        int id = add.getId();
        sampler.registerArrival(id, add.getX(), add.getZ());
        if (bundleNamedIds.contains(id)) {
            sampler.markRidden(id);
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        int distanceBlocks = sampler.arrivalSample(player.getX(), player.getZ(), add.getX(), add.getZ());
        if (distanceBlocks == SendRangeSampler.NO_SAMPLE) {
            return;
        }
        int plausibleMaxBlocks = SendRangeSampler.plausibleMaxBlocks(minecraft.options.getEffectiveRenderDistance());
        if (distanceBlocks > plausibleMaxBlocks) {
            return;
        }
        sendRange.observe(level.dimension().location().toString(), distanceBlocks);
    }

    /**
     * The live passenger-chain check gates SAMPLING AND REGISTRATION both (a pre-start mount is invisible to the packet
     * stream, so a frozen boarding position must never enter the book). The anchor is the position codec base, the
     * packet-visible position, not the lerped render position: a lerped read would bake a permanent offset into the
     * moved-bit compare.
     */
    void primeSeed(Entity entity, double playerX, double playerZ, int plausibleMaxBlocks, String dimensionId) {
        if (!qualifies(entity.getType())) {
            return;
        }
        if (entity.isPassenger() || entity.isVehicle()) {
            return;
        }
        Vec3 base = entity.getPositionCodec().decode(0L, 0L, 0L);
        int id = entity.getId();
        sampler.registerSeed(id, base.x, base.z);
        int distanceBlocks = sampler.seedSample(id, playerX, playerZ);
        if (distanceBlocks != SendRangeSampler.NO_SAMPLE && distanceBlocks <= plausibleMaxBlocks) {
            sendRange.observe(dimensionId, distanceBlocks);
        }
    }

    static @Nullable Entity createSpawnEntity(Packet<?> spawn, Level level) {
        if (spawn instanceof ClientboundAddEntityPacket add) {
            Entity entity = add.getType().create(level);
            if (entity != null) {
                entity.recreateFromPacket(add);
            }
            return entity;
        }
        if (spawn instanceof ClientboundAddExperienceOrbPacket orb) {
            return new ExperienceOrb(level, orb.getX(), orb.getY(), orb.getZ(), orb.getValue());
        }
        return null;
    }

    private void onRemove(ClientboundRemoveEntitiesPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        int plausibleMaxBlocks = SendRangeSampler.plausibleMaxBlocks(minecraft.options.getEffectiveRenderDistance());
        String dimensionId = level.dimension().location().toString();
        IntList ids = packet.getEntityIds();
        for (int i = 0; i < ids.size(); i++) {
            int distanceBlocks = sampler.removalSample(ids.getInt(i), player.getX(), player.getZ());
            if (distanceBlocks != SendRangeSampler.NO_SAMPLE && distanceBlocks <= plausibleMaxBlocks) {
                sendRange.observe(dimensionId, distanceBlocks);
            }
        }
    }

    public Set<String> receivedFrames() {
        return receivedFrames;
    }

    private void onSetData(ClientboundSetEntityDataPacket packet) {
        if (tracks(packet.id())) {
            for (SynchedEntityData.DataValue<?> value : packet.packedItems()) {
                recordData(packet.id(), value.id(), value);
            }
        }
    }

    private void onSetEquipment(ClientboundSetEquipmentPacket packet) {
        if (tracks(packet.getEntity())) {
            for (Pair<EquipmentSlot, ItemStack> slot : packet.getSlots()) {
                recordEquipment(packet.getEntity(), slot.getFirst().ordinal(),
                        new EquipmentEntry(slot.getFirst(), slot.getSecond()));
            }
        }
    }

    private void onSetPassengers(ClientboundSetPassengersPacket packet) {
        // The ridden bit is fed unconditionally: the existing gate below tracks only packet-spawned
        // vehicles, and a seeded vehicle would otherwise dodge the mark.
        sampler.markRidden(packet.getVehicle());
        for (int passenger : packet.getPassengers()) {
            sampler.markRidden(passenger);
        }
        if (tracks(packet.getVehicle())) {
            recordPassengers(packet.getVehicle(), packet.getPassengers());
        }
    }

    private void onSetLink(ClientboundSetEntityLinkPacket packet) {
        if (tracks(packet.getSourceId())) {
            recordLeash(packet.getSourceId(), packet.getDestId());
        }
    }

    private void onTeleport(ClientboundTeleportEntityPacket packet) {
        int id = packet.getId();
        double x = packet.getX();
        double y = packet.getY();
        double z = packet.getZ();
        sampler.markMovedAbsolute(id, x, z);
        EntityPos current = positionOf(id);
        if (current != null) {
            reposition(id, chunkKey(x, z),
                    new EntityPos(x, y, z, decodeAngle(packet.getyRot()), decodeAngle(packet.getxRot())));
        }
    }

    /**
     * Vanilla's tracker sends a rider rotation but never a position, so a rider's held coordinates stay frozen at the
     * last position the server sent; a chunk home derived from them would revert the re-home onto its vehicle and split
     * the pair across two drain batches. A rotation-only update therefore leaves the home alone.
     */
    public void onMove(int id, ClientboundMoveEntityPacket move) {
        sampler.markMovedRelative(id, move.hasPosition()
                && (move.getXa() != 0 || move.getYa() != 0 || move.getZa() != 0));
        EntityPos current = positionOf(id);
        if (current == null) {
            return;
        }
        float yRot = move.hasRotation() ? decodeAngle(move.getyRot()) : current.yRot();
        float xRot = move.hasRotation() ? decodeAngle(move.getxRot()) : current.xRot();
        if (!move.hasPosition()) {
            recordRotation(id, yRot, xRot);
            return;
        }
        double x = decodeAxis(current.x(), move.getXa());
        double y = decodeAxis(current.y(), move.getYa());
        double z = decodeAxis(current.z(), move.getZa());
        reposition(id, chunkKey(x, z), new EntityPos(x, y, z, yRot, xRot));
    }

    private static long chunkKey(double x, double z) {
        return ChunkPos.asLong(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
    }

    private static double decodeAxis(double base, short delta) {
        return delta == 0 ? base : (Math.round(base * 4096.0) + delta) / 4096.0;
    }

    private static float decodeAngle(byte packed) {
        return packed * 360 / 256.0F;
    }
}
