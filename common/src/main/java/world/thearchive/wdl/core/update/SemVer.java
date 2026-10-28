// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.update;

import java.util.Optional;

public final class SemVer implements Comparable<SemVer> {
    private final int major;
    private final int minor;
    private final int patch;
    private final String prerelease;

    private SemVer(int major, int minor, int patch, String prerelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
    }

    /**
     * Build metadata is split off at the first {@code +} before the prerelease split: a shape like
     * {@code 3.9.5+26.1-fabric} carries a hyphen inside its build tail, so splitting on {@code -} first would mis-read
     * the tail as a prerelease.
     */
    public static Optional<SemVer> parse(String raw) {
        String remainder = stripLeadingV(raw);
        int plus = remainder.indexOf('+');
        if (plus >= 0) {
            remainder = remainder.substring(0, plus);
        }
        String prerelease = "";
        int hyphen = remainder.indexOf('-');
        if (hyphen >= 0) {
            prerelease = remainder.substring(hyphen + 1);
            if (prerelease.isEmpty()) {
                return Optional.empty();
            }
            remainder = remainder.substring(0, hyphen);
        }
        String[] parts = remainder.split("\\.", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = Integer.parseInt(parts[2]);
            if (major < 0 || minor < 0 || patch < 0) {
                return Optional.empty();
            }
            return Optional.of(new SemVer(major, minor, patch, prerelease));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * The shown form of a raw version string: a leading {@code v} and everything from the first {@code +} are stripped.
     */
    public static String display(String raw) {
        String cleaned = stripLeadingV(raw);
        int plus = cleaned.indexOf('+');
        return plus >= 0 ? cleaned.substring(0, plus) : cleaned;
    }

    private static String stripLeadingV(String raw) {
        return raw.startsWith("v") ? raw.substring(1) : raw;
    }

    /**
     * A prerelease sorts below its bare release.
     */
    @Override
    public int compareTo(SemVer other) {
        if (major != other.major) {
            return Integer.compare(major, other.major);
        }
        if (minor != other.minor) {
            return Integer.compare(minor, other.minor);
        }
        if (patch != other.patch) {
            return Integer.compare(patch, other.patch);
        }
        if (prerelease.isEmpty() != other.prerelease.isEmpty()) {
            return prerelease.isEmpty() ? 1 : -1;
        }
        return prerelease.compareTo(other.prerelease);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (prerelease.isEmpty() ? "" : "-" + prerelease);
    }
}
