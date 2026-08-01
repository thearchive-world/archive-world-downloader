// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.core.CaptureController;
import world.thearchive.wdl.core.ChatCopy;
import world.thearchive.wdl.core.ToastCopy;
import world.thearchive.wdl.platform.PlatformBridge;
import world.thearchive.wdl.platform.WdlCommands;

/**
 * The headless slice of the resume flow: the usable-name gate on {@code /wdl start <name>} refuses before the
 * remote-world guard or any resolution runs, so the refusal path never loads config or touches the MC client. The
 * busy-guard half of the ordering is not pinned here; the fixture's controller stays idle.
 */
class ResumeFlowTest {
    @Test
    void anUnusableNameIsRefusedWithTheNeedsNameLineBeforeAnyOtherGuard() {
        RecordingBridge bridge = new RecordingBridge();
        ResumeFlow flow = flow(bridge);

        flow.startNamed("<>");

        assertEquals(List.of("wdl.chat.start_needs_name"), bridge.chatKeys,
                "the name gate speaks before the remote-world guard; its refusal appearing here means the gate moved");
    }

    @Test
    void aUsableNamePassesTheGateToTheNextGuard() {
        RecordingBridge bridge = new RecordingBridge();
        ResumeFlow flow = flow(bridge);

        flow.startNamed("museum");

        assertEquals(List.of("wdl.refuse.join_multiplayer.body"), bridge.chatKeys,
                "a usable name reaches the remote-world guard instead of the needs-name refusal");
    }

    private static ResumeFlow flow(RecordingBridge bridge) {
        return new ResumeFlow(new CaptureController(), bridge,
                () -> fail("the refusal must not load config"),
                screen -> fail("the refusal must not defer a screen"),
                target -> fail("the refusal must not start a download"));
    }

    /** A recording bridge: captures chat line keys, stubs the surfaces the refusal path never reaches. */
    private static final class RecordingBridge implements PlatformBridge {
        final List<String> chatKeys = new ArrayList<>();

        @Override
        public void registerToggleKeybind(Runnable onToggle) {}

        @Override
        public void registerDownloadsKeybind(Runnable onOpen) {}

        @Override
        public void addPauseMenuButtons(Supplier<String> primaryLabelKey, BooleanSupplier primaryEnabled,
                Runnable onPrimary, Runnable onConfig) {}

        @Override
        public void onClientTickEnd(Runnable callback) {}

        @Override
        public void onDisconnect(Runnable callback) {}

        @Override
        public void onServerJoin(Runnable callback) {}

        @Override
        public Path configDirectory() {
            return Path.of(".");
        }

        @Override
        public boolean isRemoteWorld() {
            return false;
        }

        @Override
        public String loaderName() {
            return "test";
        }

        @Override
        public String loaderVersion() {
            return "0";
        }

        @Override
        public String modVersion() {
            return "0";
        }

        @Override
        public boolean isModLoaded(String modId) {
            return false;
        }

        @Override
        public boolean observesSpectatorBlockClick() {
            // Neither shipped loader answers false to both of these: Fabric is false/true and NeoForge
            // true/false. This pair is the most permissive input the crosshair rule takes, so a headless
            // test that ever reaches resolveOpenTarget would exercise a configuration nothing ships.
            // Nothing does today, because that path needs a live client; match a real loader before one can.
            return false;
        }

        @Override
        public boolean observesSpectatorEntityClick() {
            return false;
        }

        @Override
        public void sendChat(ChatCopy line) {
            chatKeys.add(line.translationKey());
        }

        @Override
        public void sendToast(ToastCopy toast) {}

        @Override
        public void registerCommands(WdlCommands commands) {}
    }
}
