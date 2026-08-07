// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The capture-semantics toggle set and its conjunction, which is what keeps a mid-download settings edit from stranding
 * a marker: the on-screen aids draw an axis only where the live settings and the running download's latched set agree.
 */
class CaptureTogglesTest {
    private static WdlConfig config(String... keyThenValue) {
        Properties properties = new Properties();
        for (int i = 0; i < keyThenValue.length; i += 2) {
            properties.setProperty(keyThenValue[i], keyThenValue[i + 1]);
        }
        return WdlConfig.parse(properties);
    }

    @Test
    void readsEveryCaptureSemanticsAxisFromTheConfig() {
        CaptureToggles toggles = CaptureToggles.from(config("renderUnsavedOutline", "true",
                "captureContainers", "true", "captureEntities", "true", "recaptureChunks", "everywhere",
                "savePlayerEnderChest", "true"));

        assertTrue(toggles.renderUnsavedOutline());
        assertTrue(toggles.captureContainers());
        assertTrue(toggles.captureEntities());
        assertTrue(toggles.refreshesHotChunks());
        assertTrue(toggles.savePlayerEnderChest());
    }

    @Test
    void offRecaptureModeDoesNotRefreshHotChunks() {
        assertFalse(CaptureToggles.from(config("recaptureChunks", "off")).refreshesHotChunks(),
                "the interaction write rides the hot-chunk refresh, so off means no interaction reaches disk");
    }

    // Two complementary patterns rather than one: with a single mixed fixture the axes sharing a value stay
    // interchangeable, so a slot wired to the wrong knob reads correct. No two axes agree across both.
    @Test
    void eachAxisReadsItsOwnKnob() {
        CaptureToggles first = CaptureToggles.from(config("renderUnsavedOutline", "true",
                "captureContainers", "false", "captureEntities", "true", "recaptureChunks", "off",
                "savePlayerEnderChest", "false"));

        assertTrue(first.renderUnsavedOutline());
        assertFalse(first.captureContainers());
        assertTrue(first.captureEntities());
        assertFalse(first.refreshesHotChunks());
        assertFalse(first.savePlayerEnderChest());

        CaptureToggles second = CaptureToggles.from(config("renderUnsavedOutline", "false",
                "captureContainers", "false", "captureEntities", "true", "recaptureChunks", "everywhere",
                "savePlayerEnderChest", "true"));

        assertFalse(second.renderUnsavedOutline());
        assertFalse(second.captureContainers());
        assertTrue(second.captureEntities());
        assertTrue(second.refreshesHotChunks());
        assertTrue(second.savePlayerEnderChest());
    }

    @Test
    void aNewDownloadLatchesNoOpinionOnTheOutlineMaster() {
        WdlConfig outlineOff = config("renderUnsavedOutline", "false", "captureContainers", "false");

        assertTrue(CaptureToggles.latchedBy(outlineOff, false).renderUnsavedOutline(),
                "nothing on a new download reads the outline master, so latching one would gate the aid on an "
                        + "opinion this download never acts on");
        assertFalse(CaptureToggles.latchedBy(outlineOff, true).renderUnsavedOutline(),
                "a resume runs the recovered-coverage scan under it, so there the latched value is real");
        assertFalse(CaptureToggles.latchedBy(outlineOff, false).captureContainers(),
                "only the outline master differs; every other axis latches its configured value either way");
    }

    @Test
    void conjunctionKeepsAnAxisOnlyWhereBothSetsHaveIt() {
        CaptureToggles allOn = CaptureToggles.from(config("renderUnsavedOutline", "true",
                "captureContainers", "true", "captureEntities", "true", "recaptureChunks", "everywhere",
                "savePlayerEnderChest", "true"));
        CaptureToggles allOff = CaptureToggles.from(config("renderUnsavedOutline", "false",
                "captureContainers", "false", "captureEntities", "false", "recaptureChunks", "off",
                "savePlayerEnderChest", "false"));

        CaptureToggles switchedOnMidDownload = allOn.and(allOff);
        assertFalse(switchedOnMidDownload.renderUnsavedOutline());
        assertFalse(switchedOnMidDownload.captureContainers());
        assertFalse(switchedOnMidDownload.captureEntities());
        assertFalse(switchedOnMidDownload.refreshesHotChunks());
        assertFalse(switchedOnMidDownload.savePlayerEnderChest());

        CaptureToggles switchedOffMidDownload = allOff.and(allOn);
        assertFalse(switchedOffMidDownload.renderUnsavedOutline());
        assertFalse(switchedOffMidDownload.captureContainers());
        assertFalse(switchedOffMidDownload.captureEntities());
        assertFalse(switchedOffMidDownload.refreshesHotChunks());
        assertFalse(switchedOffMidDownload.savePlayerEnderChest());
    }

    @Test
    void conjunctionOfTwoOnSetsStaysOn() {
        CaptureToggles allOn = CaptureToggles.from(config("renderUnsavedOutline", "true",
                "captureContainers", "true", "captureEntities", "true", "recaptureChunks", "everywhere",
                "savePlayerEnderChest", "true"));

        CaptureToggles agreed = allOn.and(allOn);

        assertTrue(agreed.renderUnsavedOutline());
        assertTrue(agreed.captureContainers());
        assertTrue(agreed.captureEntities());
        assertTrue(agreed.refreshesHotChunks());
        assertTrue(agreed.savePlayerEnderChest());
    }

    @Test
    void conjunctionIsPerAxisRatherThanAllOrNothing() {
        CaptureToggles latched = CaptureToggles.from(config("captureContainers", "true", "captureEntities", "false"));
        CaptureToggles live = CaptureToggles.from(config("captureContainers", "true", "captureEntities", "true"));

        CaptureToggles drawn = live.and(latched);

        assertTrue(drawn.captureContainers(), "the axis both sets agree on is untouched by the other axis");
        assertFalse(drawn.captureEntities());
    }
}
