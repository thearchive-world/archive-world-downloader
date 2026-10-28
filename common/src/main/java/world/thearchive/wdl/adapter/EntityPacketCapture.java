// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAddExperienceOrbPacket;
import net.minecraft.network.protocol.game.ClientboundAddMobPacket;
import net.minecraft.network.protocol.game.ClientboundAddPaintingPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquippedItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
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
        EntityPacketAccumulator<Packet<?>, SynchedEntityData.DataItem<?>, EquipmentEntry> {
    private static volatile @Nullable EntityPacketCapture active;

    private static final Set<EntityType<?>> DECORATION_TYPES = ImmutableSet.of(
            EntityType.ITEM_FRAME, EntityType.PAINTING, EntityType.ARMOR_STAND);

    private static final int SPAWN_OBJECT_MINECART = 10;
    private static final int SPAWN_OBJECT_ITEM_FRAME = 71;
    private static final int SPAWN_OBJECT_LEASH_KNOT = 77;

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
        if (packet instanceof ClientboundAddEntityPacket) {
            ClientboundAddEntityPacket add = (ClientboundAddEntityPacket) packet;
            onAdd(add, bundleNamedIds);
        } else if (packet instanceof ClientboundAddMobPacket) {
            ClientboundAddMobPacket mob = (ClientboundAddMobPacket) packet;
            onAddMob(mob);
        } else if (packet instanceof ClientboundAddPaintingPacket) {
            ClientboundAddPaintingPacket painting = (ClientboundAddPaintingPacket) packet;
            onAddPainting(painting);
        } else if (packet instanceof ClientboundAddExperienceOrbPacket) {
            ClientboundAddExperienceOrbPacket orb = (ClientboundAddExperienceOrbPacket) packet;
            onAddExperienceOrb(orb);
        } else if (packet instanceof ClientboundRemoveEntitiesPacket) {
            ClientboundRemoveEntitiesPacket remove = (ClientboundRemoveEntitiesPacket) packet;
            onRemove(remove);
        } else if (packet instanceof ClientboundPlayerPositionPacket) {
            sampler.onAnomalyPacket();
        } else if (packet instanceof ClientboundRespawnPacket) {
            ClientboundRespawnPacket respawn = (ClientboundRespawnPacket) packet;
            enterDimension(DimensionType.getName(respawn.getDimension()).toString());
            sampler.onRespawn();
        } else if (packet instanceof ClientboundLoginPacket) {
            ClientboundLoginPacket login = (ClientboundLoginPacket) packet;
            enterDimension(DimensionType.getName(login.getDimension()).toString());
            sampler.onRespawn();
        } else if (packet instanceof ClientboundSetCameraPacket) {
            sampler.onSetCamera();
        } else if (packet instanceof ClientboundSetEntityDataPacket) {
            ClientboundSetEntityDataPacket synced = (ClientboundSetEntityDataPacket) packet;
            onSetData(synced);
        } else if (packet instanceof ClientboundSetEquippedItemPacket) {
            ClientboundSetEquippedItemPacket equip = (ClientboundSetEquippedItemPacket) packet;
            onSetEquipment(equip);
        } else if (packet instanceof ClientboundSetPassengersPacket) {
            ClientboundSetPassengersPacket passengers = (ClientboundSetPassengersPacket) packet;
            onSetPassengers(passengers);
        } else if (packet instanceof ClientboundSetEntityLinkPacket) {
            ClientboundSetEntityLinkPacket link = (ClientboundSetEntityLinkPacket) packet;
            onSetLink(link);
        } else if (packet instanceof ClientboundTeleportEntityPacket) {
            ClientboundTeleportEntityPacket teleport = (ClientboundTeleportEntityPacket) packet;
            onTeleport(teleport);
        }
    }

    private void onAdd(ClientboundAddEntityPacket add, IntSet bundleNamedIds) {
        observeSendRange(add, bundleNamedIds);
        EntityPos pos = new EntityPos(add.getX(), add.getY(), add.getZ(),
                decodeAngle((byte) add.getyRot()), decodeAngle((byte) add.getxRot()));
        spawn(add.getId(), add.getUUID(), chunkKey(add.getX(), add.getZ()), pos, add);
        if (dumpReceivedFrames
                && add.method_7626() == SPAWN_OBJECT_ITEM_FRAME) {
            receivedFrames.add(Mth.floor(add.getX()) + " " + Mth.floor(add.getY()) + " " + Mth.floor(add.getZ())
                    + " " + add.getData());
        }
    }

    private void onAddMob(ClientboundAddMobPacket mob) {
        EntityPos pos = new EntityPos(mob.getX(), mob.getY(), mob.getZ(),
                decodeAngle(mob.getyRot()), decodeAngle(mob.getxRot()));
        spawn(mob.getId(), mob.getUUID(), chunkKey(mob.getX(), mob.getZ()), pos, mob);
        List<SynchedEntityData.DataItem<?>> items = mob.getUnpackedData();
        if (items != null) {
            for (SynchedEntityData.DataItem<?> value : items) {
                recordData(mob.getId(), value.getAccessor().getId(), value);
            }
        }
    }

    private void onAddPainting(ClientboundAddPaintingPacket painting) {
        BlockPos block = painting.getPos();
        EntityPos pos = new EntityPos(block.getX(), block.getY(), block.getZ(), 0.0F, 0.0F);
        spawn(painting.getId(), painting.getUUID(), chunkKey(block.getX(), block.getZ()), pos, painting);
    }

    private void onAddExperienceOrb(ClientboundAddExperienceOrbPacket orb) {
        EntityPos pos = new EntityPos(orb.getX(), orb.getY(), orb.getZ(), 0.0F, 0.0F);
        spawn(orb.getId(), syntheticUuid(orb.getId()), chunkKey(orb.getX(), orb.getZ()), pos, orb);
    }

    private static UUID syntheticUuid(int id) {
        return new UUID(0L, id & 0xFFFFFFFFL);
    }

    static boolean qualifies(EntityType<?> type) {
        return DECORATION_TYPES.contains(type);
    }

    /**
     * Reads the player and dimension from the client singleton at netty time; the render-distance bound caps what a
     * stale read can commit.
     */
    private void observeSendRange(ClientboundAddEntityPacket add, IntSet bundleNamedIds) {
        EntityType<?> type = objectEntityType(add.method_7626());
        if (type == null || !qualifies(type)) {
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
        MultiPlayerLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        int distanceBlocks = sampler.arrivalSample(player.x, player.z, add.getX(), add.getZ());
        if (distanceBlocks == SendRangeSampler.NO_SAMPLE) {
            return;
        }
        int plausibleMaxBlocks = SendRangeSampler.plausibleMaxBlocks(minecraft.options.renderDistance);
        if (distanceBlocks > plausibleMaxBlocks) {
            return;
        }
        sendRange.observe(DimensionType.getName(level.getDimension().getType()).toString(), distanceBlocks);
    }

    /**
     * The live passenger-chain check gates SAMPLING AND REGISTRATION both (a pre-start mount is invisible to the packet
     * stream, so a frozen boarding position must never enter the book).
     */
    void primeSeed(Entity entity, double playerX, double playerZ, int plausibleMaxBlocks, String dimensionId) {
        if (!qualifies(entity.getType())) {
            return;
        }
        if (entity.isPassenger() || entity.isVehicle()) {
            return;
        }
        Vec3 base = new Vec3(entity.x, entity.y, entity.z);
        int id = entity.getId();
        sampler.registerSeed(id, base.x, base.z);
        int distanceBlocks = sampler.seedSample(id, playerX, playerZ);
        if (distanceBlocks != SendRangeSampler.NO_SAMPLE && distanceBlocks <= plausibleMaxBlocks) {
            sendRange.observe(dimensionId, distanceBlocks);
        }
    }

    private void onRemove(ClientboundRemoveEntitiesPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        MultiPlayerLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        int plausibleMaxBlocks = SendRangeSampler.plausibleMaxBlocks(minecraft.options.renderDistance);
        String dimensionId = DimensionType.getName(level.getDimension().getType()).toString();
        int[] ids = packet.getEntityIds();
        for (int i = 0; i < ids.length; i++) {
            int distanceBlocks = sampler.removalSample(ids[i], player.x, player.z);
            if (distanceBlocks != SendRangeSampler.NO_SAMPLE && distanceBlocks <= plausibleMaxBlocks) {
                sendRange.observe(dimensionId, distanceBlocks);
            }
        }
    }

    public Set<String> receivedFrames() {
        return receivedFrames;
    }

    private void onSetData(ClientboundSetEntityDataPacket packet) {
        if (tracks(packet.getId())) {
            List<SynchedEntityData.DataItem<?>> items = packet.getUnpackedData();
            if (items != null) {
                for (SynchedEntityData.DataItem<?> value : items) {
                    recordData(packet.getId(), value.getAccessor().getId(), value);
                }
            }
        }
    }

    private void onSetEquipment(ClientboundSetEquippedItemPacket packet) {
        if (tracks(packet.getEntity())) {
            EquipmentSlot slot = packet.getSlot();
            recordEquipment(packet.getEntity(), slot.ordinal(), new EquipmentEntry(slot, packet.getItem()));
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
    public void onMove(int id, short xa, short ya, short za, ClientboundMoveEntityPacket move) {
        // The Pos and PosRot position-bearing subclasses are intermediary-named at this band (class_2028 is Pos,
        // class_2029 is PosRot); a plain relative move (class_2030, Rot only) carries no position.
        boolean hasPosition = move instanceof ClientboundMoveEntityPacket.class_2028
                || move instanceof ClientboundMoveEntityPacket.class_2029;
        sampler.markMovedRelative(id, hasPosition && (xa != 0 || ya != 0 || za != 0));
        EntityPos current = positionOf(id);
        if (current == null) {
            return;
        }
        float yRot = move.hasRotation() ? decodeAngle(move.getyRot()) : current.yRot();
        float xRot = move.hasRotation() ? decodeAngle(move.getxRot()) : current.xRot();
        if (!hasPosition) {
            recordRotation(id, yRot, xRot);
            return;
        }
        double x = decodeAxis(current.x(), xa);
        double y = decodeAxis(current.y(), ya);
        double z = decodeAxis(current.z(), za);
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

    /**
     * The minecart id defers its subtype to the packet data, so {@link #createSpawnObject} resolves the real subtype
     * when it reconstructs.
     */
    static @Nullable EntityType<?> objectEntityType(int spawnObjectType) {
        switch (spawnObjectType) {
            case 1:
                return EntityType.BOAT;
            case 2:
                return EntityType.ITEM;
            case 3:
                return EntityType.AREA_EFFECT_CLOUD;
            case SPAWN_OBJECT_MINECART:
                return EntityType.MINECART;
            case 50:
                return EntityType.TNT;
            case 51:
                return EntityType.END_CRYSTAL;
            case 60:
                return EntityType.ARROW;
            case 61:
                return EntityType.SNOWBALL;
            case 62:
                return EntityType.EGG;
            case 63:
                return EntityType.FIREBALL;
            case 64:
                return EntityType.SMALL_FIREBALL;
            case 65:
                return EntityType.ENDER_PEARL;
            case 66:
                return EntityType.WITHER_SKULL;
            case 67:
                return EntityType.SHULKER_BULLET;
            case 68:
                return EntityType.LLAMA_SPIT;
            case 70:
                return EntityType.FALLING_BLOCK;
            case SPAWN_OBJECT_ITEM_FRAME:
                return EntityType.ITEM_FRAME;
            case 72:
                return EntityType.EYE_OF_ENDER;
            case 73:
                return EntityType.POTION;
            case 75:
                return EntityType.EXPERIENCE_BOTTLE;
            case 76:
                return EntityType.FIREWORK_ROCKET;
            case 77:
                return EntityType.LEASH_KNOT;
            case 78:
                return EntityType.ARMOR_STAND;
            case 79:
                return EntityType.EVOKER_FANGS;
            case 90:
                return EntityType.FISHING_BOBBER;
            case 91:
                return EntityType.SPECTRAL_ARROW;
            case 93:
                return EntityType.DRAGON_FIREBALL;
            case 94:
                return EntityType.TRIDENT;
            default:
                return null;
        }
    }

    /**
     * The item frame is a hanging entity built through its positioned constructor, which sets the facing its save reads
     * (a bare create leaves it null and there is no {@code recreateFromPacket} at this band, so {@code Entity.save}
     * would throw).
     */
    static @Nullable Entity createSpawnObject(ClientboundAddEntityPacket add, Level level) {
        int spawnObjectType = add.method_7626();
        double x = add.getX();
        double y = add.getY();
        double z = add.getZ();
        if (spawnObjectType == SPAWN_OBJECT_MINECART) {
            return AbstractMinecart.createMinecart(level, x, y, z,
                    AbstractMinecart.Type.method_11173(add.getData()));
        }
        if (spawnObjectType == SPAWN_OBJECT_ITEM_FRAME) {
            return new ItemFrame(level, new BlockPos(x, y, z), Direction.from3DDataValue(add.getData()));
        }
        if (spawnObjectType == SPAWN_OBJECT_LEASH_KNOT) {
            return new LeashFenceKnotEntity(level, new BlockPos(Mth.floor(x), Mth.floor(y), Mth.floor(z)));
        }
        EntityType<?> type = objectEntityType(spawnObjectType);
        return type == null ? null : type.create(level);
    }

    static @Nullable Entity createSpawnEntity(Packet<?> spawn, Level level) {
        if (spawn instanceof ClientboundAddEntityPacket) {
            return createSpawnObject((ClientboundAddEntityPacket) spawn, level);
        }
        if (spawn instanceof ClientboundAddMobPacket) {
            return EntityType.create(((ClientboundAddMobPacket) spawn).getType(), level);
        }
        if (spawn instanceof ClientboundAddPaintingPacket) {
            ClientboundAddPaintingPacket painting = (ClientboundAddPaintingPacket) spawn;
            return new Painting(level, painting.getPos(), painting.getDirection(), painting.getMotive());
        }
        if (spawn instanceof ClientboundAddExperienceOrbPacket) {
            ClientboundAddExperienceOrbPacket orb = (ClientboundAddExperienceOrbPacket) spawn;
            return new ExperienceOrb(level, orb.getX(), orb.getY(), orb.getZ(), orb.getValue());
        }
        return null;
    }
}
