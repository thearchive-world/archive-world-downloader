// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerFurnace;
import net.minecraft.profiler.Profiler;
import net.minecraft.stats.RecipeBook;
import net.minecraft.stats.StatisticsManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.VersionAdapterImpl;
import world.thearchive.wdl.compat.bobby.BobbyChunkFilter;
import world.thearchive.wdl.core.ContainerAssociation;
import world.thearchive.wdl.core.CoveredChunkIndex;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.SavedChunkIndex;
import world.thearchive.wdl.core.SendRangeEstimator;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The furnace-capture regression guard. {@code bindOpenedContainer}'s size read gated on
 * {@code TileEntityLockableLoot}, so a furnace (a {@code TileEntityLockable} that carries no loot table) reported a
 * block-container size of 0 and the size-match bind guard in {@link ContainerAssociation#open} refused it, the same way
 * it refuses a double chest's mismatched half; no furnace, brewing stand, or beacon could bind, and their contents were
 * dropped instead of captured. This drives the real bind method against a real {@link TileEntityFurnace} and its real
 * {@link ContainerFurnace} menu (3 non-player slots), the confident size-matched pair {@link ContainerAssociation#open}
 * is supposed to bind.
 */
class LiveCaptureSessionLockableContainerBindTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /**
     * A do-nothing {@code WorldClient} for the headless bind path: the {@code TileEntityLockable} gate reads
     * {@code level().getTileEntity(target)}, so this reports one fixed block entity at any position and needs no real
     * chunk storage behind it. Built through the same constructor {@code NetHandlerPlayClient.handleJoinGame} uses in
     * production, since {@code WorldClient} has no lighter constructor.
     */
    private static final class HeadlessWorldClient extends WorldClient {
        private final TileEntity fixedBlockEntity;

        HeadlessWorldClient(NetHandlerPlayClient connection, TileEntity fixedBlockEntity) {
            super(connection, new WorldSettings(0L, GameType.SURVIVAL, false, false, WorldType.DEFAULT),
                    0, EnumDifficulty.NORMAL, new Profiler());
            this.fixedBlockEntity = fixedBlockEntity;
        }

        @Override
        public TileEntity getTileEntity(BlockPos pos) {
            return fixedBlockEntity;
        }
    }

    /** A headless {@code EntityPlayerSP}, guarded against the two abstract members that reach the client singleton. */
    private static final class HeadlessPlayerSp extends EntityPlayerSP {
        HeadlessPlayerSp(WorldClient level, NetHandlerPlayClient connection) {
            super(null, level, connection, new StatisticsManager(), new RecipeBook());
        }

        @Override
        public boolean isSpectator() {
            return false;
        }

        @Override
        public boolean isCreative() {
            return false;
        }
    }

    /** A session with no capture toggles on, since the reflective drive below bypasses the tick gate entirely. */
    private static LiveCaptureSession session(Path configDirectory, WorldClient level) {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        properties.setProperty("captureContainers", "false");
        WdlConfig config = WdlConfig.parse(properties);
        return new LiveCaptureSession(new VersionAdapterImpl(), new HeadlessPlatformBridge(configDirectory),
                config, level, DimensionType.OVERWORLD, DimensionType.OVERWORLD,
                new DownloadTarget("headless", null, DownloadMode.NEW), new SavedChunkIndex(),
                new CoveredChunkIndex(), new SendRangeEstimator(), false, false, BobbyChunkFilter.INACTIVE,
                () -> {});
    }

    @Test
    void aFurnaceOpenBindsAsContainerNotDroppedByLootOnlyGate(@TempDir Path temporary) throws Exception {
        TileEntityFurnace furnace = new TileEntityFurnace();
        assertEquals(3, furnace.getSizeInventory(), "the fixture furnace reports the vanilla 3-slot inventory");
        NetHandlerPlayClient connection = new NetHandlerPlayClient(null, null, null,
                new GameProfile(UUID.randomUUID(), "wdl-test"));
        HeadlessWorldClient level = new HeadlessWorldClient(connection, furnace);
        HeadlessPlayerSp player = new HeadlessPlayerSp(level, connection);
        ContainerFurnace menu = new ContainerFurnace(player.inventory, furnace); // vanilla: 3 furnace slots + 36 player
        BlockPos target = new BlockPos(100, 65, 200);
        LiveCaptureSession session = session(temporary, level);

        drive(session, menu, player, target);

        ContainerAssociation association = association(session);
        assertEquals(ContainerAssociation.BindKind.CONTAINER, association.boundKind(),
                "a furnace (TileEntityLockable, not TileEntityLockableLoot) must still bind as a CONTAINER");
        assertTrue(association.boundPos().isPresent(), "the bind must persist a position for the later stash");
        assertEquals(target.toLong(), association.boundPos().getAsLong(),
                "the bind must be at the furnace's own position, not dropped");
    }

    private static void drive(LiveCaptureSession session, Container menu, EntityPlayerSP player, BlockPos target)
            throws ReflectiveOperationException {
        Method method = LiveCaptureSession.class.getDeclaredMethod("bindOpenedContainer", Container.class,
                EntityPlayerSP.class, BlockPos.class);
        method.setAccessible(true);
        method.invoke(session, menu, player, target);
    }

    private static ContainerAssociation association(LiveCaptureSession session) throws ReflectiveOperationException {
        Field field = LiveCaptureSession.class.getDeclaredField("association");
        field.setAccessible(true);
        return (ContainerAssociation) field.get(session);
    }
}
