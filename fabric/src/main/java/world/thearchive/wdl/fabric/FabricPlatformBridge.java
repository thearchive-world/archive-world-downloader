// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.AbstractWidget;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import world.thearchive.wdl.client.WdlKeyBinds;
import world.thearchive.wdl.platform.AbstractPlatformBridge;
import world.thearchive.wdl.platform.WdlCommands;

/**
 * Fabric {@link AbstractPlatformBridge}: maps the loader-specific hooks (keybind, ticks, disconnect, config directory,
 * command registration) to Fabric's APIs. Constructed directly by {@code WdlFabricClient} and handed to
 * {@code Wdl.initialize}; every Fabric / vanilla call happens inside the methods (run from the client thread), never
 * the constructor.
 */
public final class FabricPlatformBridge extends AbstractPlatformBridge {
    // This band's fabric-api ships no fabric-screen-api-v1 (ScreenEvents), so the pause-menu row has no non-mixin
    // screen hook; PauseScreenMixin injects it at the tail of PauseScreen.init() and reads this factory, which
    // addPauseMenuButtons binds to the live callbacks once at startup. Set on the client thread before any pause
    // screen opens and read on that same thread, so no synchronization is needed.
    private static @Nullable Function<List<AbstractWidget>, List<AbstractWidget>> pauseMenuRowFactory;

    @Override
    protected void registerKeybind(String keyId, Runnable onPress) {
        KeyMapping key = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                keyId, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, WdlKeyBinds.CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (key.consumeClick()) {
                onPress.run();
            }
        });
    }

    @Override
    public void addPauseMenuButtons(Supplier<String> primaryLabelKey, BooleanSupplier primaryEnabled,
            Runnable onPrimary, Runnable onConfig) {
        pauseMenuRowFactory = buttons -> {
            AbstractWidget anchor = lowest(buttons);
            return anchor == null ? Collections.emptyList()
                    : buildPauseMenuRow(anchor, primaryLabelKey, primaryEnabled, onPrimary, onConfig);
        };
    }

    /**
     * The wdl pause-menu row for {@code PauseScreenMixin} to add at the tail of {@code PauseScreen.init()}, built from
     * the callbacks {@link #addPauseMenuButtons} stored above {@code existingButtons}. Empty before that runs, and in
     * the user's own local world, where the row is hidden.
     */
    public static List<AbstractWidget> pauseMenuRow(List<AbstractWidget> existingButtons) {
        Function<List<AbstractWidget>, List<AbstractWidget>> factory = pauseMenuRowFactory;
        return factory == null ? Collections.emptyList() : factory.apply(existingButtons);
    }

    @Override
    public void onClientTickEnd(Runnable callback) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> callback.run());
    }

    @Override
    public void onDisconnect(Runnable callback) {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> callback.run());
    }

    @Override
    public void onServerJoin(Runnable callback) {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> callback.run());
    }

    @Override
    public Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public String loaderName() {
        return "Fabric";
    }

    @Override
    public String loaderVersion() {
        return FabricLoader.getInstance().getModContainer("fabricloader")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public String modVersion() {
        return FabricLoader.getInstance().getModContainer("wdl")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    /**
     * False. The mixin backing {@code UseBlockCallback} is injected into {@code MultiPlayerGameMode.useItemOn} above
     * vanilla's own spectator return, but its handler body opens with its own spectator return, ahead of the invoker
     * call, and says so ("vanilla spectator check happens later, repeat it before the event to avoid false
     * invocations"), so no listener runs. The injection site is not what decides this; the handler body is.
     */
    @Override
    public boolean observesSpectatorBlockClick() {
        return false;
    }

    /**
     * True, the opposite of the block axis. {@code UseEntityCallback} is invoked as the first statement of a mixin on
     * {@code Minecraft.startUseItem}, whose handler body carries no gamemode test, and vanilla reaches that call site
     * for a spectator.
     */
    @Override
    public boolean observesSpectatorEntityClick() {
        return true;
    }

    @Override
    public void registerCommands(WdlCommands commands) {
        // No-op on this band: the Fabric client-command API (fabric-command-api-v1, ClientCommandManager) postdates
        // 1.15, so the /wdl command tree cannot be registered without a mixin, and none ship. The keybinds and the
        // ModMenu config button remain the entry points here.
    }
}
