// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraftforge.client.ClientRegistry;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;
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
        // Below 1.19 there is no RegisterKeyMappingsEvent; keys register through ClientRegistry during client setup.
        modEventBus.addListener(
                (FMLClientSetupEvent event) -> event.enqueueWork(() -> ClientRegistry.registerKeyBinding(key)));
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
        // ScreenEvent.InitScreenEvent.Post fires on the game bus for every screen, and a Button is a renderable
        // GuiEventListener, so the pause-menu buttons inject as screen listeners with no mixin.
        MinecraftForge.EVENT_BUS.addListener((ScreenEvent.InitScreenEvent.Post event) -> {
            if (!(event.getScreen() instanceof PauseScreen)) {
                return;
            }
            List<AbstractWidget> widgets = new ArrayList<>();
            for (GuiEventListener listener : event.getScreen().children()) {
                if (listener instanceof AbstractWidget widget) {
                    widgets.add(widget);
                }
            }
            AbstractWidget anchor = lowest(widgets);
            if (anchor == null) {
                return;
            }
            buildPauseMenuRow(anchor, primaryLabelKey, primaryEnabled, onPrimary, onConfig)
                    .forEach(event::addListener);
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
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggedOutEvent event) -> callback.run());
    }

    @Override
    public void onServerJoin(Runnable callback) {
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggedInEvent event) -> callback.run());
    }

    @Override
    public Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
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
     * {@link RegisterClientCommandsEvent} fires on the GAME bus, so register it on {@link MinecraftForge#EVENT_BUS},
     * the same bus this bridge already uses for ticks and disconnect.
     */
    @Override
    public void registerCommands(WdlCommands commands) {
        MinecraftForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> {
            CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
            dispatcher.register(wdlCommandTree(commands, Commands::literal, Commands::argument));
        });
    }
}
