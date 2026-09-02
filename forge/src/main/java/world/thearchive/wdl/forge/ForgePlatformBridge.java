// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
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
 * Forge implementation of {@link world.thearchive.wdl.platform.PlatformBridge PlatformBridge}. Below the ModLauncher
 * boundary Forge carries one event bus, not a mod bus split from a game bus: {@code MinecraftForge.EVENT_BUS} is the
 * pre-ModLauncher {@code net.minecraftforge.fml.common.eventhandler.EventBus}, which dispatches only to
 * {@code @SubscribeEvent}-annotated instance methods discovered by {@code register(Object)}, not to a lambda (there
 * is no functional {@code addListener} overload at this band), so this bridge registers itself once and every
 * {@code Runnable} the loader-agnostic caller hands it (one per keybind press, one per tick-end/connect/disconnect
 * subscriber) is polled from the single shared tick listener below rather than becoming its own listener.
 * {@code isRemoteWorld} and {@code sendChat} are pure-vanilla and inherited from {@link AbstractPlatformBridge}.
 */
final class ForgePlatformBridge extends AbstractPlatformBridge {
    private final List<KeyBinding> keys = new ArrayList<>();
    private final List<Runnable> keyCallbacks = new ArrayList<>();
    private final List<Runnable> tickEndCallbacks = new ArrayList<>();
    private final List<Runnable> disconnectCallbacks = new ArrayList<>();
    private final List<Runnable> serverJoinCallbacks = new ArrayList<>();
    private boolean connected;
    private @Nullable Supplier<String> primaryLabelKey;
    private @Nullable BooleanSupplier primaryEnabled;
    private @Nullable Runnable onPrimary;
    private @Nullable Runnable onConfig;

    ForgePlatformBridge() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** The mapping registers immediately; there is no separate client-setup event to defer it to at this band. */
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

    // The pause-menu row's buttons carry their own action; below the 1.13 GUI rewrite a GuiButton has no onPress
    // callback, so the click arrives here through the vanilla screen's action-performed path and is dispatched off
    // the button identity.
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

    /**
     * The one client-tick-end listener this bridge needs: polls every registered keybind, runs the plain
     * tick-end callbacks, then detects the connection edge off {@code Minecraft.getMinecraft().getConnection()},
     * which is null until the local player is assigned and stays non-null across a dimension change, so its null
     * edge is the once-per-connection join and disconnect signal. This band does carry the legacy-FML
     * ClientConnectedToServerEvent/ClientDisconnectionFromServerEvent pair, but the tick-edge probe is the
     * loader-uniform mechanism the shared wiring already relies on, so the join and disconnect callbacks ride it.
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
        return Minecraft.getMinecraft().gameDir.toPath().resolve("config");
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

    // The two spectator-click observation booleans below carry the fork parent's 1.16-era rationale (citing
    // MultiPlayerGameMode/ForgeHooks.onRightClickBlock, classes that do not exist at this band under those names)
    // and are unverified for this band's own PlayerControllerMP/ForgeHooks interact patch; left at their inherited
    // values rather than guessed, pending re-verification against this band's actual patched interact path once the seam is ported.
    @Override
    public boolean observesSpectatorBlockClick() {
        return true;
    }

    @Override
    public boolean observesSpectatorEntityClick() {
        return false;
    }

    /**
     * Register the client-side {@code /wdl} command through Forge's {@link ClientCommandHandler}, so it runs on a
     * foreign server: a client command dispatches locally and never reaches the remote server's command tree. Below the
     * 1.13 command rewrite the command is a classic {@code ICommand} rather than a Brigadier tree, built by the shared
     * bridge helper.
     */
    @Override
    public void registerCommands(WdlCommands commands) {
        ClientCommandHandler.instance.registerCommand(wdlCommand(commands));
    }
}
