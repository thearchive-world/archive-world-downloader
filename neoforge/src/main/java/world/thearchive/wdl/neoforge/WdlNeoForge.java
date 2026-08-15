// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.ConfigScreenHandler;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiOverlaysEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.glfw.GLFW;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.adapter.InteractionCapture;
import world.thearchive.wdl.adapter.MountMenuReader;
import world.thearchive.wdl.adapter.OpenClickTracker;
import world.thearchive.wdl.client.WdlHudOverlay;
import world.thearchive.wdl.client.WdlKeyBinds;

/**
 * NeoForge client entrypoint (the analog of {@code WdlFabricClient#onInitializeClient}).
 *
 * <p>Runs at registration time: the {@code @Mod} constructor fires <i>after</i> all mod constructors but <i>before</i>
 * {@link RegisterKeyMappingsEvent} and {@link RegisterGuiOverlaysEvent}, so it is the right (and only correct) point to
 * add the mod-bus listeners that register the keybinds and the HUD layer. We hand the injected mod event bus to a new
 * {@link NeoForgePlatformBridge} and pass it to the loader-agnostic {@link Wdl#initialize}.
 *
 * <p>It also installs the connection packet tee on each play connection, the analog of the Fabric
 * {@code ClientPlayConnectionEvents.JOIN} hook, and registers the capture HUD overlay plus its peek keybind. The HUD
 * layer registers above all others rather than relative to a vanilla layer, so it inherits no gamemode render condition
 * and stays visible in creative and spectator; the render body is shared. The tee is always installed (entity packet
 * capture is the default mechanism) and no-ops while no download is running.
 *
 * <p>NeoForge 20.4's {@code @Mod} has no {@code dist} element (20.6's {@code dist = Dist.CLIENT} does not exist here);
 * {@code mods.toml} declares {@code side = "CLIENT"} on this mod's dependency entries.
 */
@Mod("wdl")
public final class WdlNeoForge {
    public WdlNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        Wdl.initialize(new NeoForgePlatformBridge(modEventBus));
        MountMenuReader.install(new NeoForgeMountMenuReader());
        // The mods-list config button: opens the same settings screen the pause-menu button does, with the
        // mod list as its back target. First-party NeoForge, no soft dependency.
        modContainer.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, modListScreen) -> Wdl.createSettingsScreen(modListScreen)));
        NeoForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingIn event) -> NeoForgeConnectionTee.install(event.getConnection()));
        // Observe the local player's right-clicks for interaction-prediction capture and the open-container
        // bind (which seeds from the clicked block, not the drifting crosshair), on the game bus beside the
        // connection listener. Never cancels the interaction, and no-ops outside a download (unpublished).
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock event) -> {
            InteractionCapture.dispatchUseBlock(
                    event.getEntity(), event.getLevel(), event.getHand(), event.getHitVec());
            OpenClickTracker.dispatchUseBlock(event.getEntity(), event.getLevel(), event.getHitVec());
        });
        // Observe entity right-clicks so a chest minecart/boat or chested animal opened by aiming at it binds to
        // the clicked entity rather than the drifting crosshair. No-ops outside a download.
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteract event) -> OpenClickTracker
                .dispatchUseEntity(event.getEntity(), event.getLevel(), event.getTarget()));

        KeyMapping peekKey = new KeyMapping(
                "key.wdl.peek_hud", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, WdlKeyBinds.CATEGORY);
        modEventBus.addListener(RegisterKeyMappingsEvent.class, event -> event.register(peekKey));
        WdlHudOverlay.bindPeekKey(peekKey);
        // Register above all layers rather than relative to a gamemode-gated vanilla layer (the experience bar is
        // hidden in creative, the hotbar in spectator), so the overlay draws in every gamemode; it self-gates F1
        // and blocking screens itself.
        modEventBus.addListener(RegisterGuiOverlaysEvent.class, event -> event.registerAboveAll(
                new ResourceLocation("wdl", "hud"),
                (gui, guiGraphics, partialTick, screenWidth, screenHeight) -> WdlHudOverlay.render(guiGraphics,
                        partialTick)));
        new NeoForgeOutlineRegistrar().register();
    }
}
