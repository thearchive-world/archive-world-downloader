// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

/**
 * The Fabric world-render events ({@code WorldRenderEvents} with {@code WorldRenderContext}, in fabric-rendering-v1)
 * arrive with the Fabric API for 1.16, so a mixin-free draw hook is unavailable below it and none ships. The
 * unsaved-container outline therefore does not draw on Fabric.
 */
final class FabricOutlineRegistrar {
    void register() {}
}
