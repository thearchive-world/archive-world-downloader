// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

final class EntityPos {
    private final double x;
    private final double y;
    private final double z;
    private final float yRot;
    private final float xRot;

    EntityPos(double x, double y, double z, float yRot, float xRot) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yRot = yRot;
        this.xRot = xRot;
    }

    double x() {
        return x;
    }

    double y() {
        return y;
    }

    double z() {
        return z;
    }

    float yRot() {
        return yRot;
    }

    float xRot() {
        return xRot;
    }
}
