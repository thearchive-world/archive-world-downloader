// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The pure camera-centered distance clamp: a container is a candidate for the outline only when it lies within the
 * configured outline distance of the camera, so the per-frame work stays bounded by what is near and the rim never
 * reaches into space the client has not rendered. MC-free and Java-8-clean.
 */
public final class OutlineClamp {
    private OutlineClamp() {}

    /** Whether the container center lies within {@code clampDistance} blocks of the camera, measured in 3D. */
    public static boolean isWithin(double cameraX, double cameraY, double cameraZ, double x, double y, double z,
            double clampDistance) {
        double dx = x - cameraX;
        double dy = y - cameraY;
        double dz = z - cameraZ;
        return dx * dx + dy * dy + dz * dz <= clampDistance * clampDistance;
    }
}
