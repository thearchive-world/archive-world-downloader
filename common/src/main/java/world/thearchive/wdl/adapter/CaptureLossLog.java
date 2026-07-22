// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The log voice for one kind of per-item capture loss. Every lost item gets its own line naming the item and its cause;
 * the stack is attached once per distinct cause type, not once per item.
 *
 * <p>One root cause fails every item of a kind alike, so a stack per line buries that cause under as many copies as the
 * download lost items, which for the map finish drain is five figures. Keying on the cause type rather than on this
 * voice is what keeps a second, unrelated cause arriving later in the same download from being represented only by the
 * first cause's stack.
 *
 * <p>An instance must be held for exactly one download: a shorter-lived one attaches a stack per item again, and one
 * shared across downloads silences the second download's first stack. Every voice today is raised from a single thread,
 * so the serialization below is not currently contended; it is there because the ordering it buys, that a stackless
 * line can never precede the line whose stack it shares, is what a voice raised from two threads would otherwise lose.
 */
final class CaptureLossLog {
    private final Logger logger;
    private final String pattern;
    private final Set<Class<?>> stackedCauseTypes = new HashSet<>();

    /**
     * {@code action} states the failure and carries exactly one {@code {}} for the item; {@code consequence} states
     * what the loss costs the reopened world. Assembled, they render as the action with the item filled in, then a
     * colon and the cause, then a semicolon and the consequence.
     *
     * <p>Rejects any action not carrying exactly one placeholder, or any consequence carrying one. A third would
     * consume the throwable as an argument, silently costing every stack on this voice for the whole download.
     */
    CaptureLossLog(Logger logger, String action, String consequence) {
        // Each side is checked on its own, not their sum: an action with no placeholder and a consequence with
        // one assembles to the right count while the item renders where the cause belongs and the cause lands
        // in the consequence clause. Backslash-escaped placeholders are not modeled; no message here has one.
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

    /**
     * Log one lost {@code item} and its {@code cause}, attaching the stack only if no loss of that cause's type has
     * been logged yet. A null {@code item} renders as "null" rather than suppressing the line, so an identifier the
     * capture could not read still leaves a record that something was lost.
     */
    synchronized void lost(@Nullable Object item, Throwable cause) {
        // The type, not the throwable: every throw is a fresh instance, so keying on the instance would put a
        // stack back on every line.
        if (stackedCauseTypes.add(cause.getClass())) {
            logger.info(pattern, item, cause.toString(), cause);
        } else {
            logger.info(pattern, item, cause.toString());
        }
    }
}
