// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import java.util.Locale;

/**
 * Renders a byte count as a human-readable size (B, KiB, MiB, GiB), the presentation half of the size pipeline whose
 * compute half is {@link FolderSize}. Binary units, per-magnitude decimals, trailing zeros trimmed, and no TiB. MC-free
 * and band-agnostic: the number is grouped under {@link Locale#ROOT} so it reads the same on every band, and the unit
 * is carried as a wdl translation key the display seam resolves.
 */
public final class SizeFormatter {
    private SizeFormatter() {}

    /** A formatted size split into its display number and the wdl translation key of its unit ({@code %s B}). */
    public static final class Size {
        private final String number;
        private final String unitKey;

        private Size(String number, String unitKey) {
            this.number = number;
            this.unitKey = unitKey;
        }

        public String number() {
            return number;
        }

        public String unitKey() {
            return unitKey;
        }
    }

    public static Size format(long bytes) {
        if (bytes < 1024) {
            return new Size(Long.toString(bytes), "wdl.size.b");
        }
        double kib = bytes / 1024.0;
        if (roundsBelow1024(kib, 0)) {
            return new Size(trim(kib, 0), "wdl.size.kib");
        }
        double mib = kib / 1024.0;
        if (roundsBelow1024(mib, 1)) {
            return new Size(trim(mib, 1), "wdl.size.mib");
        }
        return new Size(trim(mib / 1024.0, 2), "wdl.size.gib"); // no TiB
    }

    // Roll to the next unit when the value would round up to 1024 at this branch's decimals: the guard must test
    // the number trim renders, not the raw ratio, so a size a hair under the next unit does not display as
    // 1024 KiB in place of 1 MiB.
    private static boolean roundsBelow1024(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale < 1024.0;
    }

    private static String trim(double value, int decimals) {
        String text = String.format(Locale.ROOT, "%." + decimals + "f", value);
        if (text.indexOf('.') >= 0) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text;
    }
}
