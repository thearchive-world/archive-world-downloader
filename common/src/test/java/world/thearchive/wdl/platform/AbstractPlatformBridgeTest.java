// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import org.junit.jupiter.api.Test;

/**
 * The pause-menu anchor rule. This band's pause screen names no disconnect button, so geometry is the only signal
 * available and the rule has to separate the menu column from a corner button on shape alone.
 */
class AbstractPlatformBridgeTest {
    // A 427 by 240 pause screen, vanilla's own layout at the common GUI scale: a 204-wide column centered on 213.
    private static final int SCREEN_WIDTH = 427;
    private static final int SCREEN_HEIGHT = 240;
    private static final int COLUMN_X = SCREEN_WIDTH / 2 - 102;

    @Test
    void cornerButtonDoesNotOutrankTheMenuColumn() {
        // Just Zoom's pause-screen button: 20 by 20 at the bottom left, below every vanilla button.
        Button corner = button(20, SCREEN_HEIGHT - 40, 20, 20);
        Button disconnect = button(COLUMN_X, 150, 204, 20);
        assertSame(disconnect, AbstractPlatformBridge.lowestColumnButton(
                Arrays.<AbstractWidget>asList(button(COLUMN_X, 126, 204, 20), disconnect, corner), SCREEN_WIDTH));
    }

    @Test
    void halfRowButtonsAreNotAnchors() {
        Button disconnect = button(COLUMN_X, 126, 204, 20);
        Button leftHalf = button(COLUMN_X, 150, 98, 20);
        Button rightHalf = button(SCREEN_WIDTH / 2 + 4, 150, 98, 20);
        assertSame(disconnect, AbstractPlatformBridge.lowestColumnButton(
                Arrays.<AbstractWidget>asList(disconnect, leftHalf, rightHalf), SCREEN_WIDTH));
    }

    @Test
    void theLowestColumnButtonWins() {
        Button appended = button(COLUMN_X, 150, 204, 20);
        assertSame(appended, AbstractPlatformBridge.lowestColumnButton(
                Arrays.<AbstractWidget>asList(button(COLUMN_X, 126, 204, 20), appended), SCREEN_WIDTH));
    }

    @Test
    void noAnchorWhenNothingQualifies() {
        assertNull(AbstractPlatformBridge.lowestColumnButton(
                Collections.<AbstractWidget>singletonList(button(20, SCREEN_HEIGHT - 40, 20, 20)), SCREEN_WIDTH));
    }

    @Test
    void noAnchorOnAnEmptyScreen() {
        assertNull(AbstractPlatformBridge.lowestColumnButton(
                Collections.<AbstractWidget>emptyList(), SCREEN_WIDTH));
    }

    @Test
    void theShiftSpansTheColumnAndSparesCornerButtons() {
        // Just Zoom's button sits below the anchor at the bottom left. Moving it clipped it off the screen edge.
        assertFalse(AbstractPlatformBridge.movesWithAnchor(
                button(20, SCREEN_HEIGHT - 40, 20, 20), COLUMN_X, 150, 204));
        assertTrue(AbstractPlatformBridge.movesWithAnchor(
                button(COLUMN_X, 150, 204, 20), COLUMN_X, 150, 204));
    }

    @Test
    void theShiftSparesWidgetsBesideTheColumn() {
        // A mod's side strip sits below the anchor but clear of its span; only the vertical test would move it.
        assertFalse(AbstractPlatformBridge.movesWithAnchor(
                button(COLUMN_X + 204 + 4, 180, 20, 20), COLUMN_X, 150, 204));
    }

    @Test
    void theShiftCarriesRowsAppendedUnderTheColumn() {
        // The row shapes a recording mod appends under the column: full width, then a half-width pair.
        assertTrue(AbstractPlatformBridge.movesWithAnchor(
                button(COLUMN_X, 150, 204, 30), COLUMN_X, 126, 204));
        assertTrue(AbstractPlatformBridge.movesWithAnchor(
                button(COLUMN_X, 184, 98, 20), COLUMN_X, 126, 204));
        assertTrue(AbstractPlatformBridge.movesWithAnchor(
                button(SCREEN_WIDTH / 2 + 4, 184, 98, 20), COLUMN_X, 126, 204));
    }

    private static Button button(int x, int y, int width, int height) {
        return new Button(x, y, width, height, "test", press -> {});
    }
}
