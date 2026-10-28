// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import java.util.logging.ErrorManager;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/**
 * Scoped on purpose: it forwards only our own namespace, never the JVM-global JUL stream, so it cannot capture another
 * mod's logging in the shared client.
 */
final class CoreLogHandler extends Handler {
    static final String NAMESPACE = "world.thearchive.wdl";

    enum Severity {
        ERROR,
        WARN,
        INFO,
        DEBUG
    }

    @FunctionalInterface
    interface Emitter {
        void emit(Severity severity, String loggerName, String message, @Nullable Throwable thrown);
    }

    // Substitutes any {0}-style parameters into the record's message exactly as JUL would, without the timestamp/level
    // wrapper SimpleFormatter.format adds.
    private static final Formatter messageFormatter = new SimpleFormatter();

    // A strong reference to the namespace logger so the LogManager (which may hold loggers weakly) cannot collect it
    // and drop our handler.
    @Nullable
    private static Logger namespaceLogger;

    private final Emitter emitter;

    CoreLogHandler(Emitter emitter) {
        this.emitter = emitter;
    }

    static synchronized void install() {
        if (namespaceLogger != null) {
            return;
        }
        Logger target = Logger.getLogger(NAMESPACE);
        attach(target, CoreLogHandler::emitToSlf4j);
        namespaceLogger = target;
    }

    static CoreLogHandler attach(Logger target, Emitter emitter) {
        CoreLogHandler handler = new CoreLogHandler(emitter);
        handler.setLevel(Level.ALL);
        // Forward every record under the namespace, so a sub-INFO core log is not silently swallowed by
        // java.util.logging's own level gate.
        target.setLevel(Level.ALL);
        // Our handler is the sole destination for the namespace: routing only through slf4j avoids a stray duplicate on
        // stderr.
        target.setUseParentHandlers(false);
        target.addHandler(handler);
        return handler;
    }

    @Override
    public void publish(LogRecord record) {
        // The whole body is fail-safe: a logging bridge must never break the call site that logged, because core logs
        // from inside fail-soft catch blocks, so a throw here would escape and could skip downstream fail-soft
        // handling. An Error (not a RuntimeException) is left to propagate.
        try {
            Severity severity = severityFor(record.getLevel());
            String loggerName = record.getLoggerName();
            String message = messageFormatter.formatMessage(record);
            emitter.emit(severity, loggerName != null ? loggerName : NAMESPACE,
                    message != null ? message : "", record.getThrown());
        } catch (RuntimeException e) {
            reportError(null, e, ErrorManager.WRITE_FAILURE);
        }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}

    static Severity severityFor(Level level) {
        int value = level.intValue();
        if (value >= Level.SEVERE.intValue()) {
            return Severity.ERROR;
        }
        if (value >= Level.WARNING.intValue()) {
            return Severity.WARN;
        }
        if (value >= Level.INFO.intValue()) {
            return Severity.INFO;
        }
        return Severity.DEBUG;
    }

    private static void emitToSlf4j(Severity severity, String loggerName, String message,
            @Nullable Throwable thrown) {
        org.slf4j.Logger log = LoggerFactory.getLogger(loggerName);
        switch (severity) {
            case ERROR:
                if (thrown != null) {
                    log.error(message, thrown);
                } else {
                    log.error(message);
                }
                break;
            case WARN:
                if (thrown != null) {
                    log.warn(message, thrown);
                } else {
                    log.warn(message);
                }
                break;
            case INFO:
                if (thrown != null) {
                    log.info(message, thrown);
                } else {
                    log.info(message);
                }
                break;
            case DEBUG:
            default:
                if (thrown != null) {
                    log.debug(message, thrown);
                } else {
                    log.debug(message);
                }
                break;
        }
    }
}
