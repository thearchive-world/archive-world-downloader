// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import world.thearchive.wdl.Wdl;

/**
 * Auto-saves a running download when the client tears down a world, through {@link Wdl#onClientDisconnect()}. This
 * band's fabric-api (0.28.5) fires {@code ClientPlayConnectionEvents.DISCONNECT} only from
 * {@code ClientPlayNetworkHandler.onDisconnected}, which the clean user-initiated disconnect never reaches, so the
 * bridge's disconnect hook alone misses it. {@code Minecraft.clearLevel} is the main-thread teardown funnel for every
 * disconnect kind (the no-argument overload delegates to this one) and nulls {@code player} and {@code level} only at
 * its tail, so a HEAD injection stops while the player is still alive, and the finish captures the player inventory and
 * ender chest.
 */
@Mixin(Minecraft.class)
abstract class MinecraftDisconnectMixin {
    @Inject(method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
    private void wdlOnClientDisconnect(Screen screen, CallbackInfo callbackInfo) {
        Wdl.onClientDisconnect();
    }
}
