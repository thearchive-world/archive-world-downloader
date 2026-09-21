// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Collects one java.util.logging logger's WARNING and SEVERE records for the duration of each test and keeps them off
 * the console. Registered as an extension field, it attaches before each test with the parent handlers off and detaches
 * after it. A test claims each record it provokes through {@link #drain(String)} or {@link #drainAll(String)}; a record
 * still unclaimed when the test passes fails it. Records below WARNING are dropped while it is attached. The collection
 * is unsynchronized: the subject logs on the test's own thread.
 */
public final class JulCapture implements BeforeEachCallback, AfterEachCallback {
    private final Logger logger;
    private final List<LogRecord> records = new ArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                records.add(record);
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };
    private boolean attached;
    private boolean usedParentHandlers;

    private JulCapture(Logger logger) {
        this.logger = logger;
    }

    /** A capture of the logger named {@code type.getName()}. */
    public static JulCapture of(Class<?> type) {
        return new JulCapture(Logger.getLogger(type.getName()));
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        usedParentHandlers = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        attached = true;
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (!attached) {
            return;
        }
        attached = false;
        logger.removeHandler(handler);
        logger.setUseParentHandlers(usedParentHandlers);
        if (!context.getExecutionException().isPresent()) {
            assertEquals(Collections.emptyList(), messages(), "every WARNING the test provokes is claimed by a drain");
        }
    }

    /**
     * Removes and returns the one captured record whose message starts with {@code prefix}, failing on none or several.
     */
    public LogRecord drain(String prefix) {
        List<String> captured = messages();
        List<LogRecord> drained = drainAll(prefix);
        assertEquals(1, drained.size(), "one WARNING starting with \"" + prefix + "\" among " + captured);
        return drained.get(0);
    }

    /** Removes and returns every captured record whose message starts with {@code prefix}, in the order logged. */
    public List<LogRecord> drainAll(String prefix) {
        List<LogRecord> drained = new ArrayList<>();
        for (Iterator<LogRecord> remaining = records.iterator(); remaining.hasNext();) {
            LogRecord record = remaining.next();
            if (record.getMessage().startsWith(prefix)) {
                drained.add(record);
                remaining.remove();
            }
        }
        return drained;
    }

    private List<String> messages() {
        List<String> messages = new ArrayList<>();
        for (LogRecord record : records) {
            messages.add(record.getMessage());
        }
        return messages;
    }
}
