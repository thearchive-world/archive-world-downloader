// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

/**
 * The Fabric half of the in-world outline render seam, which does nothing: the Fabric world-render event API
 * (fabric-rendering-v1, {@code WorldRenderEvents}/{@code WorldRenderContext}) postdates 1.15, so a mixin-free draw hook
 * is unavailable and none ship. The unsaved-container outline therefore does not draw on Fabric.
 */
final class FabricOutlineRegistrar {
    void register() {}
}
