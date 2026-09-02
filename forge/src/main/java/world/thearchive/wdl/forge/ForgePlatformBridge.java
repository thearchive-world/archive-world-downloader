// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.class_385;
import net.minecraft.realms.class_356;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DeferredWorkQueue;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

import world.thearchive.wdl.client.WdlKeyBinds;
import world.thearchive.wdl.platform.AbstractPlatformBridge;
import world.thearchive.wdl.platform.WdlCommands;

/**
 * Forge implementation of {@link world.thearchive.wdl.platform.PlatformBridge PlatformBridge}, constructed by
 * {@link WdlForge} with the mod event bus it fetched from FMLJavaModLoadingContext. Forge, like NeoForge, splits
 * a <i>mod</i> event bus (registration and lifecycle, e.g. {@link FMLClientSetupEvent}) from the <i>game</i> event
 * bus ({@link MinecraftForge#EVENT_BUS}, gameplay, e.g. {@link TickEvent.ClientTickEvent}); a {@code KeyMapping} is
 * registered from the mod bus, so this bridge holds it for {@link #registerKeybind}. {@code isRemoteWorld}
 * and {@code sendChat} are pure-vanilla and inherited from {@link AbstractPlatformBridge}.
 */
final class ForgePlatformBridge extends AbstractPlatformBridge {
    private final IEventBus modEventBus;

    ForgePlatformBridge(IEventBus modEventBus) {
        this.modEventBus = Objects.requireNonNull(modEventBus, "modEventBus");
    }

    /** The mapping registers on the mod bus; its clicks poll on the game bus once per client tick. */
    @Override
    protected void registerKeybind(String keyId, Runnable onPress) {
        KeyMapping key = new KeyMapping(keyId, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN,
                WdlKeyBinds.CATEGORY);
        // Below 1.19 there is no RegisterKeyMappingsEvent; keys register through ClientRegistry during client
        // setup. At this band the setup event has no enqueueWork, so the main-thread registration is deferred through
        // DeferredWorkQueue instead.
        modEventBus.addListener((FMLClientSetupEvent event) -> DeferredWorkQueue
                .runLater(() -> ClientRegistry.registerKeyBinding(key)));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) {
                while (key.consumeClick()) {
                    onPress.run();
                }
            }
        });
    }

    @Override
    public void addPauseMenuButtons(Supplier<String> primaryLabelKey, BooleanSupplier primaryEnabled,
            Runnable onPrimary, Runnable onConfig) {
        // GuiScreenEvent.InitGuiEvent.Post fires on the game bus for every screen and exposes the screen's own
        // button list, so the pause-menu row injects through addButton with no mixin. This band has no addWidget
        // and no AbstractWidget: the buttons are the realms button base, so the list is read and added as those.
        MinecraftForge.EVENT_BUS.addListener((GuiScreenEvent.InitGuiEvent.Post event) -> {
            if (!(event.getGui() instanceof class_385)) {
                return;
            }
            buildPauseMenuRow(event.getButtonList(), primaryLabelKey, primaryEnabled, onPrimary, onConfig)
                    .forEach(event::addButton);
        });
    }

    @Override
    public void onClientTickEnd(Runnable callback) {
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) {
                callback.run();
            }
        });
    }

    @Override
    public void onDisconnect(Runnable callback) {
        onConnectionEdge(false, callback);
    }

    @Override
    public void onServerJoin(Runnable callback) {
        onConnectionEdge(true, callback);
    }

    /**
     * Fire {@code callback} on the client-tick edge where the play connection appears (when {@code fireOnConnect}) or
     * disappears (when not). ClientPlayerNetworkEvent, the login/logout event the higher bands listen on, does not
     * exist at this band; {@code Minecraft.getConnection()} is null until the local player is assigned and stays
     * non-null across a dimension change, so its null edge is the once-per-connection join and disconnect signal.
     * Disconnect must not fire on a dimension change (it stops an in-progress download), which a world load or unload
     * event would; the connection edge does not.
     */
    private void onConnectionEdge(boolean fireOnConnect, Runnable callback) {
        boolean[] connected = {false};
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent tick) -> {
            if (tick.phase != TickEvent.Phase.END) {
                return;
            }
            boolean now = Minecraft.getInstance().getConnection() != null;
            if (now != connected[0]) {
                connected[0] = now;
                if (now == fireOnConnect) {
                    callback.run();
                }
            }
        });
    }

    @Override
    public Path configDirectory() {
        // FMLPaths.CONFIGDIR lives in the fml.loading layer, which is outside the remapped Forge API compile view;
        // at this band it resolves to the game directory's config child, so it is resolved from there directly.
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config");
    }

    @Override
    public String loaderName() {
        return "Forge";
    }

    @Override
    public String loaderVersion() {
        try {
            return ModList.get().getModContainerById("forge")
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    @Override
    public String modVersion() {
        try {
            return ModList.get().getModContainerById("wdl")
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    /**
     * True. The patched {@code MultiPlayerGameMode.performUseItemOn} posts {@code RightClickBlock} before its
     * spectator return, and {@code ForgeHooks.onRightClickBlock} posts to the bus unconditionally.
     */
    @Override
    public boolean observesSpectatorBlockClick() {
        return true;
    }

    /**
     * False, the opposite of the block axis. {@code EntityInteract} is posted only from
     * {@code ForgeHooks.onInteractEntity}, inside the else of the spectator branch of the patched
     * {@code Player.interactOn}, and the client never reaches that method for a spectator anyway: both
     * {@code MultiPlayerGameMode.interact} and {@code interactAt} return PASS for one after sending the packet.
     */
    @Override
    public boolean observesSpectatorEntityClick() {
        return false;
    }

    /**
     * The /wdl client command is unavailable on this band's Forge jar: Forge 31 has no client-command event
     * (RegisterClientCommandsEvent is a 1.18.2 addition), and the server-side RegisterCommandsEvent cannot register a
     * command against a remote server. The command's actions are reached through the peek keybind and the pause-menu
     * buttons instead.
     */
    @Override
    public void registerCommands(WdlCommands commands) {}
}
