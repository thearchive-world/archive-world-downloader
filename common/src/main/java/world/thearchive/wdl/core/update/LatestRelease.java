// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.update;

import java.util.List;
import java.util.Optional;

/** The pure strictly-newer decision of the update check. */
public final class LatestRelease {
    private LatestRelease() {}

    /**
     * The raw version number of the newest release the running client should update to, or empty. Keeps only listed
     * release rows matching the running loader and MC version (a client-side re-filter, even though the index also
     * filters), picks the newest by publish date with a version tie-break, and reports it only when strictly newer than
     * {@code running}.
     */
    public static Optional<String> newerThan(String loaderId, String mcVersion, SemVer running,
            List<Release> rows) {
        Release newest = null;
        for (Release row : rows) {
            if (!"listed".equals(row.status()) || !"release".equals(row.versionType())
                    || !row.loaders().contains(loaderId) || !row.gameVersions().contains(mcVersion)) {
                continue;
            }
            if (newest == null || publishedAfter(row, newest)) {
                newest = row;
            }
        }
        if (newest == null || newest.version().compareTo(running) <= 0) {
            return Optional.empty();
        }
        return Optional.of(newest.versionNumber());
    }

    private static boolean publishedAfter(Release candidate, Release current) {
        int byDate = candidate.datePublished().compareTo(current.datePublished());
        if (byDate != 0) {
            return byDate > 0;
        }
        return candidate.version().compareTo(current.version()) > 0;
    }
}
