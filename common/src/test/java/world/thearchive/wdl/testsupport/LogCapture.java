// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.jspecify.annotations.Nullable;

/**
 * Attaching mutates the process-wide log4j configuration, so a capture must be closed: a leaked one keeps swallowing
 * that logger's output for every test that runs after it in the same JVM. The events arrive on whichever thread logged
 * them, so the collection is thread-safe; a caller reading events produced on another thread still has to join it
 * first.
 */
public final class LogCapture implements AutoCloseable {
    private final Logger logger;
    private final boolean priorAdditive;
    private final @Nullable Level priorLevel;
    private final CollectingAppender appender;

    private LogCapture(Logger logger, boolean priorAdditive, @Nullable Level priorLevel,
            CollectingAppender appender) {
        this.logger = logger;
        this.priorAdditive = priorAdditive;
        this.priorLevel = priorLevel;
        this.appender = appender;
    }

    /**
     * Start collecting everything logged to {@code loggerName}, at every level, and stop it reaching the appenders it
     * would otherwise inherit from its ancestors.
     *
     * <p>Throws if that exact name already carries an appender of its own, by a real configuration or by a capture
     * nobody closed, since a second attach would otherwise on close restore the wrong flags.
     */
    public static LogCapture attach(String loggerName) {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        LoggerConfig existing = context.getConfiguration().getLoggerConfig(loggerName);
        if (existing.getName().equals(loggerName) && !existing.getAppenders().isEmpty()) {
            throw new IllegalStateException(loggerName + " already carries an appender; this capture would collect "
                    + "a mixture and restore the wrong flags on close");
        }
        Logger logger = context.getLogger(loggerName);
        CollectingAppender appender = new CollectingAppender(loggerName);
        boolean priorAdditive = logger.isAdditive();
        Level priorLevel = logger.getLevel();
        // Adding an appender to a logger splits a configuration off for its name here, copied from the ancestor it was
        // inheriting.
        logger.addAppender(appender);
        logger.setAdditive(false);
        logger.setLevel(Level.ALL);
        return new LogCapture(logger, priorAdditive, priorLevel, appender);
    }

    /** How many events the logger has emitted since the attach, up to the close. */
    public int count() {
        return appender.events.size();
    }

    /** The level of event {@code index}, as its name ("INFO", "WARN"). */
    public String level(int index) {
        return event(index).level;
    }

    /** Event {@code index}'s message with its placeholders filled in. */
    public String rendered(int index) {
        return event(index).rendered;
    }

    /** The stack attached to event {@code index}, or null if the line carries none. */
    public @Nullable Throwable thrown(int index) {
        return event(index).thrown;
    }

    private Said event(int index) {
        if (index >= appender.events.size()) {
            throw new AssertionError(logger.getName() + " emitted only " + appender.events.size()
                    + " events, so there is no event " + index);
        }
        return appender.events.get(index);
    }

    @Override
    public void close() {
        logger.removeAppender(appender);
        logger.setAdditive(priorAdditive);
        if (priorLevel != null) {
            logger.setLevel(priorLevel);
        }
        appender.stop();
    }

    private static final class Said {
        private final String level;
        private final String rendered;
        private final @Nullable Throwable thrown;

        Said(LogEvent event) {
            this.level = event.getLevel().name();
            this.rendered = event.getMessage().getFormattedMessage();
            this.thrown = event.getThrown();
        }
    }

    private static final class CollectingAppender extends AbstractAppender {
        private final List<Said> events = new CopyOnWriteArrayList<>();

        CollectingAppender(String name) {
            super(name, null, null, false);
            start();
        }

        @Override
        public void append(LogEvent event) {
            events.add(new Said(event));
        }
    }
}
