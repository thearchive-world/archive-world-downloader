// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

/**
 * The Fabric half of the in-world outline render seam. On this band it does nothing: the Fabric world-render event API
 * (fabric-rendering-v1, {@code WorldRenderEvents}/{@code WorldRenderContext}) postdates 1.15, so a mixin-free draw hook
 * is unavailable and none ship. The unsaved-container outline therefore does not draw on 1.15.2 Fabric; the HUD overlay
 * and the JourneyMap coverage overlay remain.
 */
final class FabricOutlineRegistrar {
    void register() {
        // No world-render event to subscribe to at this band.
    }
}
