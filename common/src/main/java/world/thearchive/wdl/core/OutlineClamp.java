// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

public final class OutlineClamp {
    private OutlineClamp() {}

    public static boolean isWithin(double cameraX, double cameraY, double cameraZ, double x, double y, double z,
            double clampDistance) {
        double dx = x - cameraX;
        double dy = y - cameraY;
        double dz = z - cameraZ;
        return dx * dx + dy * dy + dz * dz <= clampDistance * clampDistance;
    }
}
