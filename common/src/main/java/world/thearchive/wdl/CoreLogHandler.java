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
import org.apache.logging.log4j.LogManager;
import org.jspecify.annotations.Nullable;

/**
 * Routes {@code core/}'s {@link java.util.logging} (JUL) records into the MC log (log4j2) once per loader.
 *
 * <p>The {@code core/} layer is MC-free and compiles on the Java 8 floor so it can be cherry-picked to the deep bands,
 * which rules out {@code com.mojang.logging.LogUtils} (an MC type); it logs via the JDK logger instead. The MC runtime
 * forwards log4j2 to {@code logs/latest.log} but does not forward JUL, so a fail-soft warning a {@code core/} class
 * emits (it catches an I/O failure, logs, and keeps the save openable) is invisible in the one place a user or
 * developer looks. {@link #install()} attaches a forwarding handler to the {@code world.thearchive.wdl} JUL namespace
 * (every {@code core/} logger is named under it), so those records reach {@code latest.log} without {@code core/}
 * taking any MC or log4j dependency.
 *
 * <p>Scoped on purpose: it forwards only our own namespace, never the JVM-global JUL stream, so it cannot capture
 * another mod's logging in the shared client and needs no new dependency (log4j2 is already on this layer's classpath
 * via Minecraft).
 */
final class CoreLogHandler extends Handler {
    /** The JUL namespace whose records are forwarded; every {@code core/} logger is named under it. */
    static final String NAMESPACE = "world.thearchive.wdl";

    /** The log4j2 severity a forwarded record maps to. */
    enum Severity {
        ERROR,
        WARN,
        INFO,
        DEBUG
    }

    /** Sink for a mapped record; production forwards through log4j2, tests capture. */
    @FunctionalInterface
    interface Emitter {
        void emit(Severity severity, String loggerName, String message, @Nullable Throwable thrown);
    }

    // Substitutes any {0}-style parameters into the record's message exactly as JUL would, without the
    // timestamp/level wrapper SimpleFormatter.format adds (which log4j2 supplies on its own).
    private static final Formatter messageFormatter = new SimpleFormatter();

    // A strong reference to the namespace logger so the LogManager (which may hold loggers weakly) cannot
    // collect it and drop our handler; doubles as the once-per-process install guard.
    @Nullable
    private static Logger namespaceLogger;

    private final Emitter emitter;

    CoreLogHandler(Emitter emitter) {
        this.emitter = emitter;
    }

    /** Install the forwarding handler on the {@code world.thearchive.wdl} namespace. Idempotent. */
    static synchronized void install() {
        if (namespaceLogger != null) {
            return;
        }
        Logger target = Logger.getLogger(NAMESPACE);
        attach(target, CoreLogHandler::emitToLog4j);
        namespaceLogger = target;
    }

    /** Attach a forwarding handler to {@code target}, routing its subtree's records to {@code emitter}. */
    static CoreLogHandler attach(Logger target, Emitter emitter) {
        CoreLogHandler handler = new CoreLogHandler(emitter);
        handler.setLevel(Level.ALL);
        // Forward every record under the namespace and let the log4j2 backend own the render threshold,
        // so a future sub-INFO core log is not silently swallowed by java.util.logging's own level gate.
        target.setLevel(Level.ALL);
        // Our handler is the sole destination for the namespace: the JDK's default console handler does not
        // reach latest.log anyway, so routing only through log4j2 avoids a stray duplicate on stderr.
        target.setUseParentHandlers(false);
        target.addHandler(handler);
        return handler;
    }

    @Override
    public void publish(LogRecord record) {
        // The whole body is fail-safe: a logging bridge must never break the call site that logged, because
        // core logs from inside fail-soft catch blocks, so a throw here would escape and could skip downstream
        // fail-soft handling. An Error (not a RuntimeException) is left to propagate, as it is unrecoverable.
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

    /** Map a JUL level to a log4j2 severity by its standard threshold, so a custom level still maps sanely. */
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

    /** Production sink: forward to the per-class log4j2 logger so latest.log names the originating core class. */
    private static void emitToLog4j(Severity severity, String loggerName, String message,
            @Nullable Throwable thrown) {
        org.apache.logging.log4j.Logger log = LogManager.getLogger(loggerName);
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
