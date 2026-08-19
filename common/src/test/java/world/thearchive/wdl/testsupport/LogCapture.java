// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.jspecify.annotations.Nullable;

/**
 * Collects what one logger said, for a test that asserts on a diagnostic rather than on a return value: the level it
 * was said at, the rendered line, and whether a stack rode along. The accessors return those three rather than the
 * log4j event, so a test body asserts without naming a logging type.
 *
 * <p>Attaching mutates the process-wide log4j configuration, so a capture must be closed: a leaked one keeps swallowing
 * that logger's output for every test that runs after it in the same JVM. The events arrive on whichever thread logged
 * them, so the collection is thread-safe; a caller reading events produced on another thread still has to join it
 * first.
 */
public final class LogCapture implements AutoCloseable {
    private final String loggerName;
    private final CollectingAppender appender;

    private LogCapture(String loggerName, CollectingAppender appender) {
        this.loggerName = loggerName;
        this.appender = appender;
    }

    /**
     * Start collecting everything logged to {@code loggerName}, at every level, and stop it reaching the appenders it
     * would otherwise inherit from its ancestors.
     *
     * <p>Throws if that exact name is already configured, by a real logger or by a capture nobody closed. Log4j's own
     * add is put-if-absent and reports nothing, so a second attach would otherwise install nothing, collect no events,
     * read as a silent zero, and on close detach the configuration it never installed.
     */
    public static LogCapture attach(String loggerName) {
        CollectingAppender appender = new CollectingAppender(loggerName);
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        LoggerConfig loggerConfig = new LoggerConfig(loggerName, Level.ALL, false);
        loggerConfig.addAppender(appender, Level.ALL, null);
        configuration.addLogger(loggerName, loggerConfig);
        if (configuration.getLoggerConfig(loggerName) != loggerConfig) {
            appender.stop();
            throw new IllegalStateException("a log configuration already holds " + loggerName
                    + "; this capture would collect nothing");
        }
        context.updateLoggers();
        return new LogCapture(loggerName, appender);
    }

    /** How many events the logger has emitted since the attach. */
    public int count() {
        return appender.events.size();
    }

    /** The level of event {@code index}, as its name ("INFO", "WARN"). */
    public String level(int index) {
        return event(index).getLevel().name();
    }

    /** Event {@code index}'s message with its placeholders filled in, the line a reader of the log sees. */
    public String rendered(int index) {
        return event(index).getMessage().getFormattedMessage();
    }

    /** The stack attached to event {@code index}, or null if the line carries none. */
    public @Nullable Throwable thrown(int index) {
        return event(index).getThrown();
    }

    /**
     * Fails naming the shortfall rather than letting an index walk off the end: a regression that stops a line being
     * logged at all reaches these accessors before it reaches any assertion, and a bare bounds message would say
     * nothing about what the test was protecting.
     */
    private LogEvent event(int index) {
        if (index >= appender.events.size()) {
            throw new AssertionError(loggerName + " emitted only " + appender.events.size()
                    + " events, so there is no event " + index);
        }
        return appender.events.get(index);
    }

    @Override
    public void close() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        context.getConfiguration().removeLogger(loggerName);
        context.updateLoggers();
        appender.stop();
    }

    private static final class CollectingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        CollectingAppender(String name) {
            super(name, null, null, false);
            start();
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
