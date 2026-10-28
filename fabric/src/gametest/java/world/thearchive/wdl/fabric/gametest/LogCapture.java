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
 * Events are stored as immutable copies, since log4j reuses the live event instance across calls. Appends arrive on
 * whichever thread logged, so the backing list is synchronized.
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

    List<LogEvent> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

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
