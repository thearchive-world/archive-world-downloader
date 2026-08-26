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

/**
 * Forge client entrypoint, the analog of {@code WdlFabricClient} and the NeoForge {@code WdlNeoForge}. This band
 * predates ModLauncher: there is one event bus, not a mod bus split from a game bus, so every listener below
 * registers on {@link MinecraftForge#EVENT_BUS} via {@code @SubscribeEvent} rather than a lambda (there is no
 * functional {@code addListener} overload here), and the mod is instantiated by a bare no-arg constructor FML
 * discovers from the {@link Mod} annotation, not handed a mod-bus reference. {@code useMetadata = true} reads
 * the display metadata, name/version-fallback/description/url/authorList/logoFile, from {@code mcmod.info}.
 * {@code FMLModContainer.bindMetadata} reads {@code acceptedMinecraftVersions} from the annotation descriptor
 * unconditionally (mcmod.info carries no such field in this FML release, so a JSON key there is silently
 * dropped), and reads {@code dependencies} from the annotation too unless mcmod.info opts a mod into
 * {@code useDependencyInformation}, which this one does not; both stay on the annotation rather than
 * mcmod.info. Client-only is declared by this jar shipping no dedicated-server entrypoint; a client-only mod
 * needs no {@code side} attribute at this band.
 *
 * <p>The mods-list config-GUI hook ({@code IModGuiFactory} plus the {@code @Mod(guiFactory = ...)} attribute,
 * this band's form of the {@code ExtensionPoint}/{@code ConfigGuiHandler} mechanisms newer bands use) is left
 * unwired: the in-mod settings screen {@link Wdl#createSettingsScreen} builds is already reached through the
 * pause-menu settings button and {@code /wdl config}, so the mods-list entry point is a redundant second path this
 * band does without.
 */
@Mod(
        modid = "wdl",
        useMetadata = true,
        dependencies = "required-after:forge@[14.23.5.2768,);",
        acceptedMinecraftVersions = "[1.12.2]")
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

    /**
     * Installs the connection tee on the client-tick edge where the play connection appears, the once-per-connection
     * join signal this band detects off {@code Minecraft.getMinecraft().getConnection()}, which is null until the
     * local player is assigned and stays non-null across a dimension change. The tick-edge detection is this band's
     * own FML mechanics; the legacy-FML ClientConnectedToServerEvent could hook it instead, but the tick edge keeps
     * the install path uniform with the shared connect/disconnect wiring.
     */
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
        // The shared interaction hooks take the block-hit as a RayTraceResult (the higher bands pass a
        // BlockHitResult); this band's event exposes the hit as its position, face, and hit vector, so the result
        // is rebuilt here from those.
        RayTraceResult hit = new RayTraceResult(event.getHitVec(), event.getFace(), event.getPos());
        InteractionCapture.dispatchUseBlock(event.getEntityPlayer(), event.getWorld(), event.getHand(), hit);
        OpenClickTracker.dispatchUseBlock(event.getEntityPlayer(), event.getWorld(), hit);
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        OpenClickTracker.dispatchUseEntity(event.getEntityPlayer(), event.getWorld(), event.getTarget());
    }

    // Post with ElementType.ALL fires once after the whole vanilla HUD, so the overlay draws above every element
    // in every gamemode; it self-gates F1 and blocking screens.
    @SubscribeEvent
    public void onRenderGameOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() == RenderGameOverlayEvent.ElementType.ALL) {
            WdlHudOverlay.render(event.getPartialTicks());
        }
    }
}
