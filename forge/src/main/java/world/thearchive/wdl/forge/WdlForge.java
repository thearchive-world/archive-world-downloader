// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lwjgl.glfw.GLFW;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.adapter.InteractionCapture;
import world.thearchive.wdl.adapter.MountMenuReader;
import world.thearchive.wdl.adapter.OpenClickTracker;
import world.thearchive.wdl.client.WdlHudOverlay;
import world.thearchive.wdl.client.WdlKeyBinds;

/**
 * Forge client entrypoint, the analog of {@code WdlFabricClient} and the NeoForge {@code WdlNeoForge}. Unlike
 * NeoForge, Forge's {@code @Mod} constructor takes no arguments, so the mod event bus is fetched from
 * {@link FMLJavaModLoadingContext} rather than injected; keybind and HUD-layer registration listen on that bus,
 * while the connection tee, interaction observers and outline draw listen on {@link MinecraftForge#EVENT_BUS}.
 * Forge's {@code IEventBus} has no {@code addListener(Class, Consumer)} overload, so each listener is a typed
 * lambda whose parameter names the event. Client-only is declared in mods.toml's {@code side = "CLIENT"} entries.
 */
@Mod("wdl")
public final class WdlForge {
    public WdlForge() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        Wdl.initialize(new ForgePlatformBridge(modEventBus));
        MountMenuReader.install(new ForgeMountMenuReader());
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, modListScreen) -> Wdl.createSettingsScreen(modListScreen)));
        MinecraftForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingIn event) -> ForgeConnectionTee.install(event.getConnection()));
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock event) -> {
            InteractionCapture.dispatchUseBlock(
                    event.getEntity(), event.getLevel(), event.getHand(), event.getHitVec());
            OpenClickTracker.dispatchUseBlock(event.getEntity(), event.getLevel(), event.getHitVec());
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteract event) -> OpenClickTracker
                .dispatchUseEntity(event.getEntity(), event.getLevel(), event.getTarget()));

        KeyMapping peekKey = new KeyMapping(
                "key.wdl.peek_hud", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, WdlKeyBinds.CATEGORY);
        modEventBus.addListener((RegisterKeyMappingsEvent event) -> event.register(peekKey));
        WdlHudOverlay.bindPeekKey(peekKey);
        // Register above all layers rather than relative to a gamemode-gated vanilla layer, so the overlay draws
        // in every gamemode; it self-gates F1 and blocking screens. The id is namespaced under this mod (wdl:hud).
        modEventBus.addListener((RegisterGuiOverlaysEvent event) -> event.registerAboveAll("hud",
                (gui, guiGraphics, partialTick, screenWidth, screenHeight) -> WdlHudOverlay.render(guiGraphics,
                        partialTick)));
        new ForgeOutlineRegistrar().register();
    }
}
