// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

/**
 * Collects the log4j events one logger emits during a capture, the only runtime channel on which the mod surfaces a
 * mid-run capture loss today. The half-failed axis ({@link WdlHalfFailedCaptureTest}) needs this because the per-item
 * breakdown is reported log-side only: the saved-world failed counters and the entity reconciliation are {@code LOGGER}
 * lines. The durable outcome is a separate channel and is assertable: the download report's completion record carries a
 * {@code clean} flag derived from the session's soft-failure tallies, landing as the record's status field, so a test
 * that wants the on-disk verdict reads that rather than scraping this one. Use this for which items were lost, that for
 * whether the download was. MC routes {@code
 * LogUtils.getLogger()} (SLF4J) through log4j-core, so an appender attached to the live {@link LoggerContext} sees the
 * mod's events; this is test-scope only and adds no shipped-jar footprint.
 *
 * <p>An {@link AutoCloseable} handle for a try-with-resources: {@link #close()} detaches the appender. Events are
 * filtered to {@code loggerName} as they arrive (so {@link #events()} is already scoped) and stored as immutable
 * copies, since log4j reuses the live event instance across calls. Appends arrive on whichever thread logged, so the
 * backing list is synchronized.
 */
final class LogCapture implements AutoCloseable {
    private static final String APPENDER_NAME = "wdlGametestLogCapture";

    private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final LoggerContext context;
    private final LoggerConfig loggerConfig;
    private final AbstractAppender appender;

    LogCapture(String loggerName) {
        this.context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        // getLoggerConfig resolves to the nearest ancestor (typically root) when loggerName has no own config;
        // the append-time name filter below keeps only the target logger's events regardless.
        this.loggerConfig = configuration.getLoggerConfig(loggerName);
        this.appender = new AbstractAppender(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (loggerName.equals(event.getLoggerName())) {
                    events.add(event.toImmutable());
                }
            }
        };
        appender.start();
        loggerConfig.addAppender(appender, Level.ALL, null);
        context.updateLoggers();
    }

    /** A snapshot of the events captured so far, safe to iterate while logging continues. */
    List<LogEvent> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    /** Drop everything captured so far, so a following capture is asserted on its own events. */
    void clear() {
        events.clear();
    }

    @Override
    public void close() {
        loggerConfig.removeAppender(APPENDER_NAME);
        context.updateLoggers();
        appender.stop();
    }
}
