// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TickEvent;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import world.thearchive.wdl.client.WdlKeyBinds;
import world.thearchive.wdl.platform.AbstractPlatformBridge;
import world.thearchive.wdl.platform.WdlCommands;

/**
 * A {@code KeyMapping} can only be registered by listening to {@link RegisterKeyMappingsEvent} on the mod bus, and the
 * loader injects that bus into the {@code @Mod} constructor, which runs before the event fires.
 */
final class NeoForgePlatformBridge extends AbstractPlatformBridge {
    private final IEventBus modEventBus;

    NeoForgePlatformBridge(IEventBus modEventBus) {
        this.modEventBus = Objects.requireNonNull(modEventBus, "modEventBus");
    }

    @Override
    protected void registerKeybind(String keyId, Runnable onPress) {
        KeyMapping key = new KeyMapping(keyId, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN,
                WdlKeyBinds.CATEGORY);
        modEventBus.addListener(RegisterKeyMappingsEvent.class, event -> event.register(key));
        NeoForge.EVENT_BUS.addListener(TickEvent.ClientTickEvent.class, event -> {
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
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, event -> {
            if (!(event.getScreen() instanceof PauseScreen)) {
                return;
            }
            List<AbstractWidget> widgets = new ArrayList<>();
            for (GuiEventListener listener : event.getScreen().children()) {
                if (listener instanceof AbstractWidget widget) {
                    widgets.add(widget);
                }
            }
            buildPauseMenuRow(event.getScreen(), widgets, primaryLabelKey, primaryEnabled, onPrimary, onConfig)
                    .forEach(event::addListener);
        });
    }

    @Override
    protected @Nullable AbstractWidget disconnectButton(Screen pauseScreen) {
        return pauseScreen instanceof PauseScreen pause ? pause.disconnectButton : null;
    }

    @Override
    public void onClientTickEnd(Runnable callback) {
        NeoForge.EVENT_BUS.addListener(TickEvent.ClientTickEvent.class, event -> {
            if (event.phase == TickEvent.Phase.END) {
                callback.run();
            }
        });
    }

    @Override
    public void onDisconnect(Runnable callback) {
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> callback.run());
    }

    @Override
    public void onServerJoin(Runnable callback) {
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, event -> callback.run());
    }

    @Override
    public Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public String loaderName() {
        return "NeoForge";
    }

    @Override
    public String loaderVersion() {
        try {
            return ModList.get().getModContainerById("neoforge")
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
     * True. The patched {@code MultiPlayerGameMode.performUseItemOn} posts {@code RightClickBlock} before its spectator
     * return, and {@code CommonHooks.onRightClickBlock} posts to the bus unconditionally.
     */
    @Override
    public boolean observesSpectatorBlockClick() {
        return true;
    }

    /**
     * False, the opposite of the block axis. {@code EntityInteract} is posted only from
     * {@code CommonHooks.onInteractEntity}, called inside the else of the spectator branch of the patched
     * {@code Player.interactOn}, and the client never reaches that method for a spectator anyway:
     * {@code MultiPlayerGameMode.interact} returns PASS for one after sending the packet.
     */
    @Override
    public boolean observesSpectatorEntityClick() {
        return false;
    }

    /**
     * {@link RegisterClientCommandsEvent} fires on the GAME bus, so register it on {@link NeoForge#EVENT_BUS}.
     */
    @Override
    public void registerCommands(WdlCommands commands) {
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, event -> {
            CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
            dispatcher.register(wdlCommandTree(commands, Commands::literal, Commands::argument));
        });
    }
}
