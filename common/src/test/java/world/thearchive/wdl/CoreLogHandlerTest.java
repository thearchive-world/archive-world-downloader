// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.CoreLogHandler.Severity;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.core.export.FinalizeOutputs;

class CoreLogHandlerTest {
    private record Captured(Severity severity, String loggerName, String message, @Nullable Throwable thrown) {}

    private final List<Captured> captured = new ArrayList<>();
    private final CoreLogHandler.Emitter capturing = (severity, loggerName, message, thrown) -> {
        captured.add(new Captured(severity, loggerName, message, thrown));
    };

    // When a test attaches to a live logger, restore it afterward so the global java.util.logging state the
    // next test sees is unchanged.
    @Nullable
    private Logger attachedTo;
    @Nullable
    private CoreLogHandler attachedHandler;
    private boolean priorUseParentHandlers;
    @Nullable
    private Level priorLevel;

    @AfterEach
    void detachAnyHandler() {
        if (attachedTo != null && attachedHandler != null) {
            attachedTo.removeHandler(attachedHandler);
            attachedTo.setUseParentHandlers(priorUseParentHandlers);
            attachedTo.setLevel(priorLevel);
        }
    }

    @Test
    void severityForMapsByThreshold() {
        assertEquals(Severity.ERROR, CoreLogHandler.severityFor(Level.SEVERE));
        assertEquals(Severity.WARN, CoreLogHandler.severityFor(Level.WARNING));
        assertEquals(Severity.INFO, CoreLogHandler.severityFor(Level.INFO));
        assertEquals(Severity.DEBUG, CoreLogHandler.severityFor(Level.CONFIG));
        assertEquals(Severity.DEBUG, CoreLogHandler.severityFor(Level.FINE));
        assertEquals(Severity.DEBUG, CoreLogHandler.severityFor(Level.FINEST));
        assertEquals(Severity.ERROR, CoreLogHandler.severityFor(Level.OFF), "a level above SEVERE maps to ERROR");
        assertEquals(Severity.DEBUG, CoreLogHandler.severityFor(Level.ALL), "a level below INFO maps to DEBUG");
    }

    @Test
    void publishForwardsSeverityMessageAndThrowable() {
        CoreLogHandler handler = new CoreLogHandler(capturing);
        IOException boom = new IOException("disk full");
        LogRecord record = new LogRecord(Level.WARNING, "the export zip failed");
        record.setLoggerName("world.thearchive.wdl.core.export.FinalizeOutputs");
        record.setThrown(boom);

        handler.publish(record);

        assertEquals(1, captured.size());
        Captured only = captured.get(0);
        assertEquals(Severity.WARN, only.severity());
        assertEquals("world.thearchive.wdl.core.export.FinalizeOutputs", only.loggerName());
        assertEquals("the export zip failed", only.message());
        assertSame(boom, only.thrown());
    }

    @Test
    void publishToleratesMissingMessageAndThrowable() {
        CoreLogHandler handler = new CoreLogHandler(capturing);
        LogRecord record = new LogRecord(Level.INFO, null); // no message, no logger name, no throwable

        handler.publish(record);

        assertEquals(1, captured.size());
        Captured only = captured.get(0);
        assertEquals(Severity.INFO, only.severity());
        assertEquals(CoreLogHandler.NAMESPACE, only.loggerName(),
                "a record with no logger name falls back to the namespace");
        assertEquals("", only.message());
        assertNull(only.thrown());
    }

    @Test
    void attachRoutesDescendantRecordToTheEmitter() {
        Logger parent = Logger.getLogger("world.thearchive.wdl.test.CoreLogHandlerPropagation");
        priorUseParentHandlers = parent.getUseParentHandlers();
        priorLevel = parent.getLevel();
        attachedHandler = CoreLogHandler.attach(parent, capturing);
        attachedTo = parent;
        assertFalse(parent.getUseParentHandlers(), "attach routes the namespace solely through slf4j");

        Logger descendant = Logger.getLogger(parent.getName() + ".Descendant");
        IOException locked = new IOException("session.lock held");
        descendant.log(Level.WARNING, "the resume backup zip failed", locked);

        assertEquals(1, captured.size());
        assertEquals(Severity.WARN, captured.get(0).severity());
        assertSame(locked, captured.get(0).thrown());
    }

    @Test
    void attachForwardsSubInfoRecordsSoLog4jOwnsTheThreshold() {
        Logger parent = Logger.getLogger("world.thearchive.wdl.test.CoreLogHandlerSubInfo");
        priorUseParentHandlers = parent.getUseParentHandlers();
        priorLevel = parent.getLevel();
        attachedHandler = CoreLogHandler.attach(parent, capturing);
        attachedTo = parent;

        Logger descendant = Logger.getLogger(parent.getName() + ".Descendant");
        descendant.log(Level.CONFIG, "a verbose diagnostic");

        assertEquals(1, captured.size(), "a sub-INFO record is forwarded, not dropped by the JUL level gate");
        assertEquals(Severity.DEBUG, captured.get(0).severity());
    }

    @Test
    void publishExpandsMessageParametersWithoutAddingTheJulPrefix() {
        CoreLogHandler handler = new CoreLogHandler(capturing);
        LogRecord record = new LogRecord(Level.WARNING, "saved {0} chunks");
        record.setParameters(new Object[] { 42 });

        handler.publish(record);

        assertEquals(1, captured.size());
        assertEquals("saved 42 chunks", captured.get(0).message());
    }

    @Test
    void publishSwallowsAnEmitterFailureSoItCannotBreakTheCallSite() {
        CoreLogHandler handler = new CoreLogHandler((severity, loggerName, message, thrown) -> {
            throw new IllegalStateException("the slf4j backend is broken");
        });
        LogRecord record = new LogRecord(Level.WARNING, "a core fail-soft warning");

        assertDoesNotThrow(() -> handler.publish(record));
    }

    @Test
    void theNamespaceIsAnAncestorOfEveryCoreLogger() {
        assertTrue(FinalizeOutputs.class.getName().startsWith(CoreLogHandler.NAMESPACE));
        assertTrue(WdlConfig.class.getName().startsWith(CoreLogHandler.NAMESPACE));
    }
}
