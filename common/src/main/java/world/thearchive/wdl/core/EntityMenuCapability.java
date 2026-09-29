// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later
package world.thearchive.wdl.core;

/** Whether a clicked entity is judged to open no server-driven container menu in vanilla. */
public final class EntityMenuCapability {
    private EntityMenuCapability() {}

    public static boolean isMenuIncapable(boolean vanillaNamespace, boolean isContainerEntity,
            boolean hasCustomInventoryScreen, boolean isAbstractVillager, boolean isVillager,
            boolean isBaby, boolean isNitwit) {
        if (vanillaNamespace && isVillager && (isBaby || isNitwit)) {
            return true; // a baby never trades; a nitwit's profession grants no trades
        }
        return vanillaNamespace && !isContainerEntity && !hasCustomInventoryScreen && !isAbstractVillager;
    }
}
