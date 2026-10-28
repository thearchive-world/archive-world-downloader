// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.math.RayTraceResult;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.adapter.InteractionCapture;
import world.thearchive.wdl.adapter.MountMenuReader;
import world.thearchive.wdl.adapter.OpenClickTracker;
import world.thearchive.wdl.client.WdlHudOverlay;
import world.thearchive.wdl.client.WdlKeyBinds;
import world.thearchive.wdl.client.WdlToastOverlay;

/**
 * {@code FMLModContainer.bindMetadata} reads {@code acceptedMinecraftVersions} from the annotation descriptor
 * unconditionally (mcmod.info carries no such field in this FML release, so a JSON key there is silently dropped), and
 * reads {@code dependencies} from the annotation too unless mcmod.info opts a mod into
 * {@code useDependencyInformation}, which this one does not.
 */
@Mod(
        modid = "wdl",
        useMetadata = true,
        guiFactory = "world.thearchive.wdl.forge.ForgeConfigGuiFactory",
        dependencies = "required-after:forge@[13.20.0.2262,);",
        acceptedMinecraftVersions = "[1.11.2]")
public final class WdlForge {
    private boolean teeConnected;

    public WdlForge() {
        MinecraftForge.EVENT_BUS.register(this);
        Wdl.initialize(new ForgePlatformBridge());
        MountMenuReader.install(new ForgeMountMenuReader());

        KeyBinding peekKey = new KeyBinding("key.wdl.peek_hud", Keyboard.KEY_LMENU, WdlKeyBinds.CATEGORY);
        ClientRegistry.registerKeyBinding(peekKey);
        WdlHudOverlay.bindPeekKey(peekKey);

        new ForgeOutlineRegistrar().register();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent tick) {
        if (tick.phase != TickEvent.Phase.END) {
            return;
        }
        NetHandlerPlayClient listener = Minecraft.getMinecraft().getConnection();
        boolean nowConnected = listener != null;
        if (nowConnected && !teeConnected) {
            ForgeConnectionTee.install(listener.getNetworkManager());
        }
        teeConnected = nowConnected;
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        RayTraceResult hit = new RayTraceResult(event.getHitVec(), event.getFace(), event.getPos());
        InteractionCapture.dispatchUseBlock(event.getEntityPlayer(), event.getWorld(), event.getHand(), hit);
        OpenClickTracker.dispatchUseBlock(event.getEntityPlayer(), event.getWorld(), hit);
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        OpenClickTracker.dispatchUseEntity(event.getEntityPlayer(), event.getWorld(), event.getTarget());
    }

    // Post with ElementType.ALL fires once after the whole vanilla HUD, so the overlay draws above every element in
    // every gamemode.
    @SubscribeEvent
    public void onRenderGameOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() == RenderGameOverlayEvent.ElementType.ALL) {
            WdlHudOverlay.render(event.getPartialTicks());
            // Guarded on the same blocking-screen test the HUD overlay applies internally, because this pass runs
            // before the screen; a WDL screen draws the tray from its own render instead.
            if (!Wdl.platformBridge().isBlockingScreenOpen()) {
                WdlToastOverlay.render();
            }
        }
    }
}
