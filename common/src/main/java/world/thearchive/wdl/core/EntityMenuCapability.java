// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later
package world.thearchive.wdl.core;

/** MC-free decision: whether a clicked entity provably opens no server-driven container menu in vanilla. */
public final class EntityMenuCapability {
    private EntityMenuCapability() {}

    public static boolean isMenuIncapable(boolean vanillaNamespace, boolean isContainerEntity,
            boolean hasCustomInventoryScreen, boolean isAbstractVillager, boolean isVillager,
            boolean isBaby, boolean isNitwit) {
        if (vanillaNamespace && isVillager && (isBaby || isNitwit)) {
            return true; // race-proof tradeless villager (baby / nitwit can never have offers)
        }
        return vanillaNamespace && !isContainerEntity && !hasCustomInventoryScreen && !isAbstractVillager;
    }
}
