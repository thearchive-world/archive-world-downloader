// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.HashSet;
import java.util.Set;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * One root cause fails every item of a kind alike, so a stack per line buries that cause under as many copies as the
 * download lost items. Keying on the cause type rather than on this voice is what keeps a second cause of another type
 * arriving later in the same download from being represented only by the first cause's stack.
 *
 * <p>An instance must be held for exactly one download: a shorter-lived one attaches a stack per item again, and one
 * shared across downloads silences the second download's first stack. The serialization below is there because the
 * ordering it buys, that a stackless line can never precede the line whose stack it shares, is what a voice raised from
 * two threads would otherwise lose.
 */
final class CaptureLossLog {
    private final Logger logger;
    private final String pattern;
    private final Set<Class<?>> stackedCauseTypes = new HashSet<>();

    /**
     * Rejects any action not carrying exactly one placeholder, or any consequence carrying one. A third would consume
     * the throwable as an argument, silently costing every stack on this voice for the whole download.
     */
    CaptureLossLog(Logger logger, String action, String consequence) {
        // Each side is checked on its own, not their sum: an action with no placeholder and a consequence with one
        // assembles to the right count while the item renders where the cause belongs and the cause lands in the
        // consequence clause.
        if (countPlaceholders(action) != 1 || countPlaceholders(consequence) != 0) {
            throw new IllegalArgumentException("the action needs exactly one {} for the item and the consequence "
                    + "none: " + action + " / " + consequence);
        }
        this.logger = logger;
        this.pattern = action + ": {}; " + consequence;
    }

    private static int countPlaceholders(String pattern) {
        int count = 0;
        for (int i = pattern.indexOf("{}"); i >= 0; i = pattern.indexOf("{}", i + 2)) {
            count++;
        }
        return count;
    }

    synchronized void lost(@Nullable Object item, Throwable cause) {
        if (stackedCauseTypes.add(cause.getClass())) {
            logger.info(pattern, item, cause.toString(), cause);
        } else {
            logger.info(pattern, item, cause.toString());
        }
    }
}
