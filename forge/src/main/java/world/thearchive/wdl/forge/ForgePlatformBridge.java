// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.jspecify.annotations.Nullable;
import org.lwjgl.input.Keyboard;

import world.thearchive.wdl.client.WdlKeyBinds;
import world.thearchive.wdl.platform.AbstractPlatformBridge;
import world.thearchive.wdl.platform.WdlCommands;

/**
 * {@code MinecraftForge.EVENT_BUS} dispatches to {@code @SubscribeEvent}-annotated instance methods discovered by
 * {@code register(Object)}, so this bridge registers itself once.
 */
final class ForgePlatformBridge extends AbstractPlatformBridge {
    private final List<KeyBinding> keys = new ArrayList<>();
    private final List<Runnable> keyCallbacks = new ArrayList<>();
    private final List<Runnable> tickEndCallbacks = new ArrayList<>();
    private final List<Runnable> disconnectCallbacks = new ArrayList<>();
    private final List<Runnable> serverJoinCallbacks = new ArrayList<>();
    private final List<Runnable> levelTeardownCallbacks = new ArrayList<>();
    private boolean connected;
    private @Nullable Supplier<String> primaryLabelKey;
    private @Nullable BooleanSupplier primaryEnabled;
    private @Nullable Runnable onPrimary;
    private @Nullable Runnable onConfig;

    ForgePlatformBridge() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    protected void registerKeybind(String keyId, Runnable onPress) {
        KeyBinding key = new KeyBinding(keyId, Keyboard.KEY_NONE, WdlKeyBinds.CATEGORY);
        ClientRegistry.registerKeyBinding(key);
        keys.add(key);
        keyCallbacks.add(onPress);
    }

    @Override
    public void addPauseMenuButtons(Supplier<String> primaryLabelKey, BooleanSupplier primaryEnabled,
            Runnable onPrimary, Runnable onConfig) {
        this.primaryLabelKey = primaryLabelKey;
        this.primaryEnabled = primaryEnabled;
        this.onPrimary = onPrimary;
        this.onConfig = onConfig;
    }

    @SubscribeEvent
    public void onInitGuiPost(GuiScreenEvent.InitGuiEvent.Post event) {
        if (primaryLabelKey == null || !(event.getGui() instanceof GuiIngameMenu)) {
            return;
        }
        buildPauseMenuRow(event.getButtonList(), primaryLabelKey, primaryEnabled, onPrimary, onConfig)
                .forEach(event.getButtonList()::add);
    }

    @SubscribeEvent
    public void onActionPerformed(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (event.getButton() instanceof WdlMenuButton) {
            ((WdlMenuButton) event.getButton()).press();
        }
    }

    @Override
    public void onClientTickEnd(Runnable callback) {
        tickEndCallbacks.add(callback);
    }

    @Override
    public void onDisconnect(Runnable callback) {
        disconnectCallbacks.add(callback);
    }

    @Override
    public void onServerJoin(Runnable callback) {
        serverJoinCallbacks.add(callback);
    }

    @Override
    public void onLevelTeardown(Runnable callback) {
        levelTeardownCallbacks.add(callback);
    }

    /**
     * The one edge ahead of the loader's registry revert. {@code Minecraft.loadWorld} posts {@code WorldEvent.Unload}
     * at its head and calls {@code FMLClientHandler.handleClientWorldClosing}, which reverts the registries on any
     * non-local connection, later in that same method on that same thread, and nothing between the two runs a client
     * tick. So the disconnect callbacks above, which ride the tick edge, cannot reach a subscriber until the revert is
     * over, and this can.
     *
     * <p>Gated on the client's own world: with an integrated server running, the server thread posts this event for its
     * worlds too, and a callback taken there would reach the capture off the client thread it belongs to.
     */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (!event.getWorld().isRemote) {
            return;
        }
        for (Runnable callback : levelTeardownCallbacks) {
            callback.run();
        }
    }

    /**
     * The one client-tick-end listener this bridge needs: polls every registered keybind, runs the plain tick-end
     * callbacks, then detects the connection edge off {@code Minecraft.getMinecraft().getConnection()}, which is null
     * until the local player is assigned and stays non-null across a dimension change, so its null edge is the
     * once-per-connection join and disconnect signal.
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).isPressed()) {
                keyCallbacks.get(i).run();
            }
        }
        for (Runnable callback : tickEndCallbacks) {
            callback.run();
        }
        boolean now = Minecraft.getMinecraft().getConnection() != null;
        if (now != connected) {
            connected = now;
            for (Runnable callback : now ? serverJoinCallbacks : disconnectCallbacks) {
                callback.run();
            }
        }
    }

    @Override
    public Path configDirectory() {
        return Minecraft.getMinecraft().mcDataDir.toPath().resolve("config");
    }

    @Override
    public String loaderName() {
        return "Forge";
    }

    @Override
    public String loaderVersion() {
        try {
            ModContainer forge = Loader.instance().getIndexedModList().get("forge");
            return forge != null ? forge.getVersion() : "unknown";
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    @Override
    public String modVersion() {
        try {
            ModContainer wdl = Loader.instance().getIndexedModList().get("wdl");
            return wdl != null ? wdl.getVersion() : "unknown";
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    @Override
    public boolean isModLoaded(String modId) {
        return Loader.isModLoaded(modId);
    }

    @Override
    public boolean observesSpectatorBlockClick() {
        return true;
    }

    @Override
    public boolean observesSpectatorEntityClick() {
        return false;
    }

    @Override
    public void registerCommands(WdlCommands commands) {
        ClientCommandHandler.instance.registerCommand(wdlCommand(commands));
    }
}
