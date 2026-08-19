// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import world.thearchive.wdl.testsupport.LogCapture;

/**
 * The per-item capture-loss voice. Pinned here because all three parts of its contract are invisible to a compiler: a
 * loss line that stops carrying its item stops telling a user what they lost, a line that stops carrying its cause
 * makes every loss after the first indistinguishable and misattributes them to whichever cause happened to arrive
 * first, and a repeat that starts carrying its stack again buries the causes under a finish drain's worth of copies.
 */
class CaptureLossLogTest {
    private static final String ACTION = "failed to write map data {}";
    private static final String CONSEQUENCE = "it renders blank in the reopened world";

    private String loggerName;
    private LogCapture captured;

    @BeforeEach
    void attachAppender(TestInfo info) {
        loggerName = "world.thearchive.wdl.test.loss." + info.getTestMethod().get().getName();
        captured = LogCapture.attach(loggerName);
    }

    @AfterEach
    void detachAppender() {
        if (captured != null) { // an attach that threw leaves nothing to close, and an NPE here would bury it
            captured.close();
        }
    }

    private CaptureLossLog lossLog() {
        return new CaptureLossLog(LogManager.getLogger(loggerName), ACTION, CONSEQUENCE);
    }

    @Test
    void namesEveryLostItemAndItsCauseAtInfo() {
        CaptureLossLog loss = lossLog();
        IOException cause = new IOException("the save folder is a file");

        loss.lost("map_7", cause);
        loss.lost("map_8", cause);
        loss.lost("map_9", cause);

        assertEquals(3, captured.count(), "every lost map keeps its own line, so none becomes invisible");
        for (int i = 0; i < 3; i++) {
            assertEquals("INFO", captured.level(i), "a capture loss is a soft failure and logs at INFO");
            assertEquals("failed to write map data map_" + (7 + i)
                    + ": java.io.IOException: the save folder is a file; it renders blank in the reopened world",
                    captured.rendered(i), "every line names its own item and its own cause, repeats included");
        }
    }

    @Test
    void attachesOneStackPerDistinctCauseTypeRatherThanPerItem() {
        CaptureLossLog loss = lossLog();

        loss.lost("map_7", new InvalidPathException("map_7", "bad key"));
        loss.lost("map_8", new IOException("No space left on device"));
        loss.lost("map_9", new IOException("No space left on device"));
        loss.lost("map_10", new InvalidPathException("map_10", "bad key"));

        assertNotNull(captured.thrown(0), "the first loss of a cause type carries its stack");
        assertNotNull(captured.thrown(1),
                "a second, unrelated cause carries its own stack; it is not represented by the first one's");
        assertNull(captured.thrown(2), "a repeat of a stacked cause type drops the stack");
        assertNull(captured.thrown(3), "the bound is the cause type, not the arrival order");
        assertTrue(captured.rendered(2).contains("java.io.IOException: No space left on device"),
                "a stackless repeat still identifies its cause, so a pasted excerpt is diagnosable alone");
    }

    @Test
    void keepsTheLineForAnItemWhoseIdentifierCouldNotBeRead() {
        CaptureLossLog loss = lossLog();
        IOException cause = new IOException("encode failed");

        loss.lost(null, cause);
        loss.lost(null, cause);

        assertEquals(2, captured.count(), "an unreadable identifier never suppresses the loss line");
        assertTrue(captured.rendered(1).startsWith("failed to write map data null: java.io.IOException: encode failed"),
                "the cause carries the line when the item cannot, so a stackless repeat is never content-free");
    }

    @Test
    void rejectsAnActionThatWouldMisalignItsArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new CaptureLossLog(LogManager.getLogger(loggerName), "lost {} of {}", CONSEQUENCE),
                "a second placeholder would consume the throwable and silently drop every stack");
        assertThrows(IllegalArgumentException.class,
                () -> new CaptureLossLog(LogManager.getLogger(loggerName), "lost a map", CONSEQUENCE),
                "with no placeholder for the item the formatter fills the cause's slot with it instead, and the "
                        + "cause, which is what makes a stackless repeat diagnosable, is dropped");
    }

    @Test
    void rejectsConsequencePlaceholdersThatWouldMisalignItsArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new CaptureLossLog(LogManager.getLogger(loggerName), ACTION, "its map {} renders blank"),
                "the formatter reads one assembled pattern, so a third placeholder consumes the throwable as "
                        + "text and no loss on this voice ever carries a stack again");
    }
}
