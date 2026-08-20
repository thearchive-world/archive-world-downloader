// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DeferredWorkQueue;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
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
 * {@link FMLJavaModLoadingContext} rather than injected; the keybind registers on that bus through
 * {@link FMLClientSetupEvent}, while the connection tee, interaction observers, HUD overlay and outline draw
 * listen on {@link MinecraftForge#EVENT_BUS}. Forge's {@code IEventBus} has no {@code addListener(Class, Consumer)}
 * overload, so each listener is a typed lambda whose parameter names the event. Client-only is declared in
 * mods.toml's {@code side = "CLIENT"} entries.
 */
@Mod("wdl")
public final class WdlForge {
    public WdlForge() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        Wdl.initialize(new ForgePlatformBridge(modEventBus));
        MountMenuReader.install(new ForgeMountMenuReader());
        // At 1.15.2 the config-screen factory is an ExtensionPoint carrying a BiFunction, not the
        // ConfigGuiHandler.ConfigGuiFactory wrapper the 1.17-and-above bands register.
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.CONFIGGUIFACTORY,
                () -> (minecraft, modListScreen) -> Wdl.createSettingsScreen(modListScreen));
        MinecraftForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggedInEvent event) -> ForgeConnectionTee.install(event.getNetworkManager()));
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock event) -> {
            // At 1.15.2 RightClickBlock carries no hit vector; getHitVec is a 1.16 addition. The consumers read
            // only the clicked block position, so the hit is rebuilt from the event's position and face, with the
            // block center standing in for the precise location the event does not provide.
            BlockPos clicked = event.getPos();
            BlockHitResult hit = new BlockHitResult(
                    new Vec3(clicked.getX() + 0.5, clicked.getY() + 0.5, clicked.getZ() + 0.5),
                    event.getFace(), clicked, false);
            InteractionCapture.dispatchUseBlock(event.getPlayer(), event.getWorld(), event.getHand(), hit);
            OpenClickTracker.dispatchUseBlock(event.getPlayer(), event.getWorld(), hit);
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteract event) -> OpenClickTracker
                .dispatchUseEntity(event.getPlayer(), event.getWorld(), event.getTarget()));

        KeyMapping peekKey = new KeyMapping(
                "key.wdl.peek_hud", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, WdlKeyBinds.CATEGORY);
        // Below 1.19 there is no RegisterKeyMappingsEvent; keys register through ClientRegistry during client
        // setup. At 1.15.2 the setup event has no enqueueWork, so the main-thread registration is deferred through
        // DeferredWorkQueue instead.
        modEventBus.addListener((FMLClientSetupEvent event) -> DeferredWorkQueue
                .runLater(() -> ClientRegistry.registerKeyBinding(peekKey)));
        WdlHudOverlay.bindPeekKey(peekKey);
        // At 1.15.2 there is no OverlayRegistry (a 1.17 addition) or RegisterGuiOverlaysEvent (1.19); the HUD host
        // is RenderGameOverlayEvent on the game bus. Post with ElementType.ALL fires once after the whole vanilla
        // HUD, so the overlay draws above every element in every gamemode; it self-gates F1 and blocking screens.
        MinecraftForge.EVENT_BUS.addListener((RenderGameOverlayEvent.Post event) -> {
            if (event.getType() == RenderGameOverlayEvent.ElementType.ALL) {
                WdlHudOverlay.render(event.getPartialTicks());
            }
        });
        new ForgeOutlineRegistrar().register();
    }
}
