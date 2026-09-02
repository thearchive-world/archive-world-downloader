// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/**
 * The pause-menu anchor rule. The fallback half runs only on bands whose pause screen names no disconnect button, and
 * the rejection half only when another mod has moved the named one off screen, so neither is reachable from a live
 * client on a modern band.
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
        assertSame(disconnect, AbstractPlatformBridge.anchor(
                null, List.of(button(COLUMN_X, 126, 204, 20), disconnect, corner), SCREEN_WIDTH, SCREEN_HEIGHT));
    }

    @Test
    void halfRowButtonsAreNotAnchors() {
        Button disconnect = button(COLUMN_X, 126, 204, 20);
        Button leftHalf = button(COLUMN_X, 150, 98, 20);
        Button rightHalf = button(SCREEN_WIDTH / 2 + 4, 150, 98, 20);
        assertSame(disconnect, AbstractPlatformBridge.anchor(
                null, List.of(disconnect, leftHalf, rightHalf), SCREEN_WIDTH, SCREEN_HEIGHT));
    }

    @Test
    void theNamedButtonOutranksTheFullWidthButtonBelowIt() {
        // Flashback's BELOW recording mode appends a 204-wide centered button after the disconnect button. Geometry
        // alone cannot tell the two apart, which is why the named button wins wherever the band provides one.
        Button disconnect = button(COLUMN_X, 126, 204, 20);
        Button appended = button(COLUMN_X, 150, 204, 20);
        assertSame(disconnect, AbstractPlatformBridge.anchor(
                disconnect, List.of(disconnect, appended), SCREEN_WIDTH, SCREEN_HEIGHT));
    }

    @Test
    void geometryTakesTheLowestColumnButtonWhenNoButtonIsNamed() {
        Button appended = button(COLUMN_X, 150, 204, 20);
        assertSame(appended, AbstractPlatformBridge.anchor(
                null, List.of(button(COLUMN_X, 126, 204, 20), appended), SCREEN_WIDTH, SCREEN_HEIGHT));
    }

    @Test
    void aParkedNamedButtonIsRejectedForTheVisibleReplacement() {
        // ReplayMod parks the vanilla disconnect button at minus 1000 by minus 1000 during replay playback and puts
        // its own button in the vacated slot; anchoring to the parked one would carry this row off screen with it.
        Button parked = button(-1000, -1000, 204, 20);
        Button replacement = button(COLUMN_X, 150, 204, 20);
        assertSame(replacement, AbstractPlatformBridge.anchor(
                parked, List.of(parked, replacement), SCREEN_WIDTH, SCREEN_HEIGHT));
    }

    @Test
    void noAnchorWhenNothingQualifies() {
        assertNull(AbstractPlatformBridge.anchor(
                null, List.of(button(20, SCREEN_HEIGHT - 40, 20, 20)), SCREEN_WIDTH, SCREEN_HEIGHT));
    }

    @Test
    void noAnchorOnAnEmptyScreen() {
        assertNull(AbstractPlatformBridge.anchor(null, List.<AbstractWidget>of(), SCREEN_WIDTH, SCREEN_HEIGHT));
    }

    @Test
    void aNarrowNamedButtonLosesToTheColumn() {
        // A mod resizing vanilla's own button in place would otherwise reproduce the negative-width primary button.
        Button resized = button(COLUMN_X, 150, 20, 20);
        Button column = button(COLUMN_X, 126, 204, 20);
        assertSame(column, AbstractPlatformBridge.anchor(
                resized, List.of(resized, column), SCREEN_WIDTH, SCREEN_HEIGHT));
    }

    @Test
    void theShiftSpansTheColumnAndSparesCornerButtons() {
        // Just Zoom's button sits below the anchor at the bottom left. Moving it clipped it off the screen edge.
        int anchorX = COLUMN_X;
        int anchorY = 150;
        int anchorWidth = 204;
        assertFalse(AbstractPlatformBridge.movesWithAnchor(
                button(20, SCREEN_HEIGHT - 40, 20, 20), anchorX, anchorY, anchorWidth));
        assertTrue(AbstractPlatformBridge.movesWithAnchor(
                button(anchorX, anchorY, anchorWidth, 20), anchorX, anchorY, anchorWidth));
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
        int anchorX = COLUMN_X;
        int anchorY = 126;
        int anchorWidth = 204;
        assertTrue(AbstractPlatformBridge.movesWithAnchor(
                button(anchorX, 150, 204, 30), anchorX, anchorY, anchorWidth));
        assertTrue(AbstractPlatformBridge.movesWithAnchor(
                button(anchorX, 184, 98, 20), anchorX, anchorY, anchorWidth));
        assertTrue(AbstractPlatformBridge.movesWithAnchor(
                button(SCREEN_WIDTH / 2 + 4, 184, 98, 20), anchorX, anchorY, anchorWidth));
    }

    private static Button button(int x, int y, int width, int height) {
        return Button.builder(Component.literal("test"), press -> {}).bounds(x, y, width, height).build();
    }
}
