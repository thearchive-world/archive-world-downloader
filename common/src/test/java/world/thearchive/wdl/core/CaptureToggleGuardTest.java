// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The consequence-naming guard classification: disabling a core capture toggle is a download-harming edit that warrants
 * a hard confirm; disabling recapture warrants the milder staleness confirm; everything else is benign. The guard fires
 * only on the disable direction, so it classifies by key, not by direction here. The passive-indicator predicate
 * ({@link CaptureToggleGuard#isCapturePartiallyDisabled}) shares the same core notion so both settings surfaces read
 * one source.
 */
class CaptureToggleGuardTest {
    private static WdlConfig config(String... keyValues) {
        Properties properties = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            properties.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return WdlConfig.parse(properties);
    }

    @Test
    void theTwoCoreCaptureTogglesWarnOfDataLoss() {
        assertEquals(CaptureToggleGuard.CAPTURE, CaptureToggleGuard.whenDisabling("captureEntities"));
        assertEquals(CaptureToggleGuard.CAPTURE, CaptureToggleGuard.whenDisabling("captureContainers"));
    }

    @Test
    void recaptureModeHasNoBooleanDisableConfirm() {
        assertEquals(CaptureToggleGuard.NONE, CaptureToggleGuard.whenDisabling("recaptureChunks"),
                "recapture is a three-state mode; its reduction confirm is whenReducingRecapture, not a boolean off");
    }

    @Test
    void reducingRecaptureFreshnessTakesTheMilderStalenessWarning() {
        assertEquals(CaptureToggleGuard.RECAPTURE,
                CaptureToggleGuard.whenReducingRecapture(RecaptureMode.EVERYWHERE, RecaptureMode.NEARBY),
                "dropping revisit-overwrite leaves already-downloaded areas stale");
        assertEquals(CaptureToggleGuard.RECAPTURE,
                CaptureToggleGuard.whenReducingRecapture(RecaptureMode.EVERYWHERE, RecaptureMode.OFF));
        assertEquals(CaptureToggleGuard.RECAPTURE,
                CaptureToggleGuard.whenReducingRecapture(RecaptureMode.NEARBY, RecaptureMode.OFF));
    }

    @Test
    void raisingOrKeepingRecaptureFreshnessNeedsNoConfirm() {
        assertEquals(CaptureToggleGuard.NONE,
                CaptureToggleGuard.whenReducingRecapture(RecaptureMode.OFF, RecaptureMode.EVERYWHERE),
                "turning recapture up loses no data, so no confirm");
        assertEquals(CaptureToggleGuard.NONE,
                CaptureToggleGuard.whenReducingRecapture(RecaptureMode.NEARBY, RecaptureMode.EVERYWHERE));
        assertEquals(CaptureToggleGuard.NONE,
                CaptureToggleGuard.whenReducingRecapture(RecaptureMode.EVERYWHERE, RecaptureMode.EVERYWHERE));
    }

    @Test
    void benignTogglesNeedNoConfirm() {
        assertEquals(CaptureToggleGuard.NONE, CaptureToggleGuard.whenDisabling("overrideGamerules"));
        assertEquals(CaptureToggleGuard.NONE, CaptureToggleGuard.whenDisabling("skipVoidChunks"));
    }

    @Test
    void allCaptureKindsOnIsNotPartiallyDisabled() {
        assertFalse(CaptureToggleGuard.isCapturePartiallyDisabled(WdlConfig.DEFAULTS));
    }

    @Test
    void anyDisabledCoreCaptureKindIsPartiallyDisabled() {
        assertTrue(CaptureToggleGuard.isCapturePartiallyDisabled(config("captureEntities", "false")));
        assertTrue(CaptureToggleGuard.isCapturePartiallyDisabled(config("captureContainers", "false")));
        assertTrue(CaptureToggleGuard.isCapturePartiallyDisabled(
                config("captureEntities", "false", "captureContainers", "false")));
    }

    @Test
    void reducingRecaptureAloneIsNotPartiallyDisabled() {
        assertFalse(CaptureToggleGuard.isCapturePartiallyDisabled(config("recaptureChunks", "OFF")));
    }
}
