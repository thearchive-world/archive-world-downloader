// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DeferredWorkQueue;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lwjgl.glfw.GLFW;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.adapter.InteractionCapture;
import world.thearchive.wdl.adapter.MountMenuReader;
import world.thearchive.wdl.adapter.OpenClickTracker;
import world.thearchive.wdl.client.WdlHudOverlay;
import world.thearchive.wdl.client.WdlKeyBinds;

@Mod("wdl")
public final class WdlForge {
    public WdlForge() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        Wdl.initialize(new ForgePlatformBridge(modEventBus));
        MountMenuReader.install(new ForgeMountMenuReader());
        // Forge 25.x grays the mods-list config button out for every mod, gating it on ModInfo.hasConfigUI, a hardcoded
        // false; the press path itself already resolves this registration.
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.CONFIGGUIFACTORY,
                () -> (minecraft, modListScreen) -> Wdl.createSettingsScreen(modListScreen));
        boolean[] teeConnected = {false};
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent tick) -> {
            if (tick.phase != TickEvent.Phase.END) {
                return;
            }
            ClientPacketListener listener = Minecraft.getInstance().getConnection();
            boolean nowConnected = listener != null;
            if (nowConnected && !teeConnected[0]) {
                ForgeConnectionTee.install(listener.getConnection());
            }
            teeConnected[0] = nowConnected;
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock event) -> {
            BlockPos clicked = event.getPos();
            HitResult hit = new HitResult(
                    new Vec3(clicked.getX() + 0.5, clicked.getY() + 0.5, clicked.getZ() + 0.5),
                    event.getFace(), clicked);
            InteractionCapture.dispatchUseBlock(event.getEntityPlayer(), event.getWorld(), event.getHand(), hit);
            OpenClickTracker.dispatchUseBlock(event.getEntityPlayer(), event.getWorld(), hit);
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteract event) -> OpenClickTracker
                .dispatchUseEntity(event.getEntityPlayer(), event.getWorld(), event.getTarget()));

        KeyMapping peekKey = new KeyMapping(
                "key.wdl.peek_hud", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, WdlKeyBinds.CATEGORY);
        modEventBus.addListener((FMLClientSetupEvent event) -> DeferredWorkQueue
                .runLater(() -> ClientRegistry.registerKeyBinding(peekKey)));
        WdlHudOverlay.bindPeekKey(peekKey);
        // Post with ElementType.ALL fires once after the whole vanilla HUD, so the overlay draws above every element in
        // every gamemode.
        MinecraftForge.EVENT_BUS.addListener((RenderGameOverlayEvent.Post event) -> {
            if (event.getType() == RenderGameOverlayEvent.ElementType.ALL) {
                WdlHudOverlay.render(event.getPartialTicks());
            }
        });
        new ForgeOutlineRegistrar().register();
    }
}
