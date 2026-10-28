// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.IThreadListener;
import net.minecraft.util.datafix.DataFixesManager;
import net.minecraftforge.common.util.CompoundDataFixer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.IFMLSidedHandler;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.StartupQuery;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import net.minecraftforge.fml.relauncher.Side;

public final class TestRegistries {
    private static boolean bootstrapped;

    private TestRegistries() {}

    /** Run the vanilla bootstrap once, populating the static built-in registries the tests read. */
    public static synchronized void bootstrap() {
        if (bootstrapped) {
            return;
        }
        Bootstrap.register();
        loadSidedHandler();
        bootstrapped = true;
    }

    private static void loadSidedHandler() {
        try {
            Field sidedDelegate = FMLCommonHandler.class.getDeclaredField("sidedDelegate");
            sidedDelegate.setAccessible(true);
            sidedDelegate.set(FMLCommonHandler.instance(), new HeadlessSidedHandler());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot install the headless FML sided handler", e);
        }
    }

    private static final class HeadlessSidedHandler implements IFMLSidedHandler {
        private final CompoundDataFixer dataFixer = new CompoundDataFixer(DataFixesManager.createFixer());

        @Override
        public CompoundDataFixer getDataFixer() {
            return dataFixer;
        }

        @Override
        public List<String> getAdditionalBrandingInformation() {
            return Collections.emptyList();
        }

        @Override
        public Side getSide() {
            throw new UnsupportedOperationException("headless test sided handler: getSide unreached");
        }

        @Override
        public void haltGame(String message, Throwable cause) {
            throw new UnsupportedOperationException("headless test sided handler: haltGame unreached");
        }

        @Override
        public void showGuiScreen(Object clientGuiElement) {
            throw new UnsupportedOperationException("headless test sided handler: showGuiScreen unreached");
        }

        @Override
        public void queryUser(StartupQuery query) {
            throw new UnsupportedOperationException("headless test sided handler: queryUser unreached");
        }

        @Override
        public void beginServerLoading(MinecraftServer server) {
            throw new UnsupportedOperationException("headless test sided handler: beginServerLoading unreached");
        }

        @Override
        public void finishServerLoading() {
            throw new UnsupportedOperationException("headless test sided handler: finishServerLoading unreached");
        }

        @Override
        public File getSavesDirectory() {
            throw new UnsupportedOperationException("headless test sided handler: getSavesDirectory unreached");
        }

        @Override
        public MinecraftServer getServer() {
            throw new UnsupportedOperationException("headless test sided handler: getServer unreached");
        }

        @Override
        public boolean isDisplayCloseRequested() {
            throw new UnsupportedOperationException("headless test sided handler: isDisplayCloseRequested unreached");
        }

        @Override
        public boolean shouldServerShouldBeKilledQuietly() {
            throw new UnsupportedOperationException(
                    "headless test sided handler: shouldServerShouldBeKilledQuietly unreached");
        }

        @Override
        public void addModAsResource(ModContainer container) {
            throw new UnsupportedOperationException("headless test sided handler: addModAsResource unreached");
        }

        @Override
        public String getCurrentLanguage() {
            throw new UnsupportedOperationException("headless test sided handler: getCurrentLanguage unreached");
        }

        @Override
        public void serverStopped() {
            throw new UnsupportedOperationException("headless test sided handler: serverStopped unreached");
        }

        @Override
        public NetworkManager getClientToServerNetworkManager() {
            throw new UnsupportedOperationException(
                    "headless test sided handler: getClientToServerNetworkManager unreached");
        }

        @Override
        public INetHandler getClientPlayHandler() {
            throw new UnsupportedOperationException("headless test sided handler: getClientPlayHandler unreached");
        }

        @Override
        public void fireNetRegistrationEvent(EventBus bus, NetworkManager manager, Set<String> channels,
                String modId, Side side) {
            throw new UnsupportedOperationException(
                    "headless test sided handler: fireNetRegistrationEvent unreached");
        }

        @Override
        public boolean shouldAllowPlayerLogins() {
            throw new UnsupportedOperationException("headless test sided handler: shouldAllowPlayerLogins unreached");
        }

        @Override
        public void allowLogins() {
            throw new UnsupportedOperationException("headless test sided handler: allowLogins unreached");
        }

        @Override
        public IThreadListener getWorldThread(INetHandler netHandler) {
            throw new UnsupportedOperationException("headless test sided handler: getWorldThread unreached");
        }

        @Override
        public void processWindowMessages() {
            throw new UnsupportedOperationException("headless test sided handler: processWindowMessages unreached");
        }

        @Override
        public String stripSpecialChars(String text) {
            throw new UnsupportedOperationException("headless test sided handler: stripSpecialChars unreached");
        }

        @Override
        public void reloadRenderers() {
            throw new UnsupportedOperationException("headless test sided handler: reloadRenderers unreached");
        }

        @Override
        public void fireSidedRegistryEvents() {
            throw new UnsupportedOperationException("headless test sided handler: fireSidedRegistryEvents unreached");
        }

        @Override
        public boolean isDisplayVSyncForced() {
            throw new UnsupportedOperationException("headless test sided handler: isDisplayVSyncForced unreached");
        }
    }
}
