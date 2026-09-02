// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import net.minecraft.client.gui.GuiButton;
import org.junit.jupiter.api.Test;

/**
 * The pause-menu anchor rule. This band's pause screen names no disconnect button, so geometry is the only signal
 * available and the rule has to separate the menu column from a corner button on shape alone.
 */
class AbstractPlatformBridgeTest {
    // A 427 by 240 pause screen, vanilla's own layout at the common GUI scale: a 200-wide column centered on 213.
    private static final int SCREEN_WIDTH = 427;
    private static final int SCREEN_HEIGHT = 240;
    private static final int COLUMN_X = SCREEN_WIDTH / 2 - 100;

    @Test
    void cornerButtonDoesNotOutrankTheMenuColumn() {
        // Just Zoom's pause-screen button: 20 by 20 at the bottom left, below every vanilla button.
        GuiButton corner = button(20, SCREEN_HEIGHT - 40, 20, 20);
        GuiButton disconnect = button(COLUMN_X, 150, 200, 20);
        assertSame(disconnect, AbstractPlatformBridge.lowestColumnButton(
                Arrays.asList(button(COLUMN_X, 126, 200, 20), disconnect, corner), SCREEN_WIDTH));
    }

    @Test
    void halfRowButtonsAreNotAnchors() {
        GuiButton disconnect = button(COLUMN_X, 126, 200, 20);
        GuiButton leftHalf = button(COLUMN_X, 150, 98, 20);
        GuiButton rightHalf = button(SCREEN_WIDTH / 2 + 2, 150, 98, 20);
        assertSame(disconnect, AbstractPlatformBridge.lowestColumnButton(
                Arrays.asList(disconnect, leftHalf, rightHalf), SCREEN_WIDTH));
    }

    @Test
    void theLowestColumnButtonWins() {
        GuiButton appended = button(COLUMN_X, 150, 200, 20);
        assertSame(appended, AbstractPlatformBridge.lowestColumnButton(
                Arrays.asList(button(COLUMN_X, 126, 200, 20), appended), SCREEN_WIDTH));
    }

    @Test
    void noAnchorWhenNothingQualifies() {
        assertNull(AbstractPlatformBridge.lowestColumnButton(
                Collections.singletonList(button(20, SCREEN_HEIGHT - 40, 20, 20)), SCREEN_WIDTH));
    }

    @Test
    void noAnchorOnAnEmptyScreen() {
        assertNull(AbstractPlatformBridge.lowestColumnButton(
                Collections.<GuiButton>emptyList(), SCREEN_WIDTH));
    }

    @Test
    void theShiftSpansTheColumnAndSparesCornerButtons() {
        // Just Zoom's button sits below the anchor at the bottom left. Moving it clipped it off the screen edge.
        assertFalse(AbstractPlatformBridge.movesWithAnchor(
                button(20, SCREEN_HEIGHT - 40, 20, 20), COLUMN_X, 150, 200));
        assertTrue(AbstractPlatformBridge.movesWithAnchor(
                button(COLUMN_X, 150, 200, 20), COLUMN_X, 150, 200));
    }

    @Test
    void theShiftSparesWidgetsBesideTheColumn() {
        // A mod's side strip sits below the anchor but clear of its span; only the vertical test would move it.
        assertFalse(AbstractPlatformBridge.movesWithAnchor(
                button(COLUMN_X + 200 + 4, 180, 20, 20), COLUMN_X, 150, 200));
    }

    @Test
    void theShiftCarriesRowsAppendedUnderTheColumn() {
        // The row shapes a recording mod appends under the column: full width, then a half-width pair.
        assertTrue(AbstractPlatformBridge.movesWithAnchor(
                button(COLUMN_X, 150, 200, 30), COLUMN_X, 126, 200));
        assertTrue(AbstractPlatformBridge.movesWithAnchor(
                button(COLUMN_X, 184, 98, 20), COLUMN_X, 126, 200));
        assertTrue(AbstractPlatformBridge.movesWithAnchor(
                button(SCREEN_WIDTH / 2 + 2, 184, 98, 20), COLUMN_X, 126, 200));
    }

    private static GuiButton button(int x, int y, int width, int height) {
        return new GuiButton(0, x, y, width, height, "test");
    }
}
