// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

public final class SpectatorCrosshairFallback {
    public enum Axis {
        NONE,
        BLOCK,
        ENTITY
    }

    private SpectatorCrosshairFallback() {}

    public static Axis axisFor(boolean spectator, boolean loaderObservesBlockClick,
            boolean loaderObservesEntityClick, boolean crosshairOnBlock, boolean crosshairOnEntity,
            boolean blockOpensForSpectator) {
        if (!spectator) {
            return Axis.NONE;
        }
        if (!loaderObservesBlockClick && crosshairOnBlock && blockOpensForSpectator) {
            return Axis.BLOCK;
        }
        if (!loaderObservesEntityClick && crosshairOnEntity) {
            return Axis.ENTITY;
        }
        return Axis.NONE;
    }
}
