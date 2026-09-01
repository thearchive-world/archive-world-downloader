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
 * Collects what one logger said, for a test that asserts on a diagnostic rather than on a return value: the level it
 * was said at, the rendered line, and whether a stack rode along. The accessors return those three rather than the
 * log4j event, so a test body asserts without naming a logging type.
 *
 * <p>Attaching mutates the process-wide log4j configuration, so a capture must be closed: a leaked one keeps swallowing
 * that logger's output for every test that runs after it in the same JVM. The events arrive on whichever thread logged
 * them, so the collection is thread-safe; a caller reading events produced on another thread still has to join it
 * first.
 *
 * <p>The attach goes through the logger rather than the configuration on this band. Minecraft carries log4j 2.0-beta9
 * here, where {@code Configuration} has no {@code addLogger} or {@code removeLogger} and appenders are added to a
 * {@code Logger} directly, so the additive flag is what stops the captured output also reaching the appenders it would
 * inherit. The three values are also copied out of each event as it arrives rather than held as an immutable copy of
 * the event, since {@code LogEvent.toImmutable} arrives later than this release.
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
     * nobody closed, since a second attach would otherwise collect a mixture and on close restore the wrong flags. The
     * test is against the configuration named for this logger, not against the logger's effective appenders, which are
     * the root's until something configures the name and so are never empty.
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
        // Adding an appender to a logger splits a configuration off for its name here, copied from the ancestor it
        // was inheriting, which is what makes the additive flag below the logger's own rather than the ancestor's.
        logger.addAppender(appender);
        logger.setAdditive(false);
        logger.setLevel(Level.ALL);
        return new LogCapture(logger, priorAdditive, priorLevel, appender);
    }

    /** How many events the logger has emitted since the attach. */
    public int count() {
        return appender.events.size();
    }

    /** The level of event {@code index}, as its name ("INFO", "WARN"). */
    public String level(int index) {
        return event(index).level;
    }

    /** Event {@code index}'s message with its placeholders filled in, the line a reader of the log sees. */
    public String rendered(int index) {
        return event(index).rendered;
    }

    /** The stack attached to event {@code index}, or null if the line carries none. */
    public @Nullable Throwable thrown(int index) {
        return event(index).thrown;
    }

    /**
     * Fails naming the shortfall rather than letting an index walk off the end: a regression that stops a line being
     * logged at all reaches these accessors before it reaches any assertion, and a bare bounds message would say
     * nothing about what the test was protecting.
     */
    private Said event(int index) {
        if (index >= appender.events.size()) {
            throw new AssertionError(logger.getName() + " emitted only " + appender.events.size()
                    + " events, so there is no event " + index);
        }
        return appender.events.get(index);
    }

    /**
     * Detach and restore. The split-off configuration itself stays, this log4j release having no way to remove one, but
     * it is left carrying no appender and the flags it was inheriting, so it routes exactly as it did before.
     */
    @Override
    public void close() {
        logger.removeAppender(appender);
        logger.setAdditive(priorAdditive);
        if (priorLevel != null) {
            logger.setLevel(priorLevel);
        }
        appender.stop();
    }

    /** One captured line, read off the event while it is still the live one. */
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
