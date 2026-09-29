// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The outcome of merging a band's curated safe set with the user's {@code gamerule.*} overrides: the effective rules,
 * plus the two diagnostics the writer logs. {@link #droppedInvalidValues()} are valid ids whose value
 * {@link GameRuleSchema#acceptsValue} refuses; {@link #unknownIds()} are override ids that do not exist, or are not
 * enabled, at the running band. With the game-rule override master off all three are empty.
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

    public Map<String, String> effective() {
        return effective;
    }

    public List<String> droppedInvalidValues() {
        return droppedInvalidValues;
    }

    public List<String> unknownIds() {
        return unknownIds;
    }
}
