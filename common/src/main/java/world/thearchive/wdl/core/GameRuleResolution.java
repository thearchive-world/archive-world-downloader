// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The outcome of merging a band's curated safe set with the user's {@code gamerule.*} overrides: the effective rules to
 * write (band id to raw value), plus the two diagnostics the writer logs and the session surfaces.
 * {@link #droppedInvalidValues()} are valid ids whose value did not parse for the rule's type; {@link #unknownIds()}
 * are override ids that do not exist at the running band (the cross-band override loss). With the master off all three
 * are empty.
 */
public final class GameRuleResolution {
    private final Map<String, String> effective;
    private final List<String> droppedInvalidValues;
    private final List<String> unknownIds;

    GameRuleResolution(Map<String, String> effective, List<String> droppedInvalidValues,
            List<String> unknownIds) {
        this.effective = Collections.unmodifiableMap(effective);
        this.droppedInvalidValues = Collections.unmodifiableList(droppedInvalidValues);
        this.unknownIds = Collections.unmodifiableList(unknownIds);
    }

    /** The rules to write, by band id to raw value: the curated set with valid overrides applied on top. */
    public Map<String, String> effective() {
        return effective;
    }

    /** Override ids whose value did not parse for the rule's type; dropped and logged, never written. */
    public List<String> droppedInvalidValues() {
        return droppedInvalidValues;
    }

    /** Override ids that do not exist at the running band; surfaced so the cross-band loss is not hidden. */
    public List<String> unknownIds() {
        return unknownIds;
    }
}
