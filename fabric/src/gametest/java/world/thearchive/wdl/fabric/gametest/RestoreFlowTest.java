// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.core.LogEvent;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.client.WdlDownloadsScreen;
import world.thearchive.wdl.core.CaptureState;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.core.browse.SinglePlayerTaint;
import world.thearchive.wdl.core.export.RestoreOperation;

/**
 * The restore cascade driven end to end through the real download screen, the resume confirms, and the command and
 * keybind flows, on the shipped client gametest harness. Each pinned case builds its pre-restore state on the client's
 * saves directory (a captured folder made singleplayer-tainted, a foreign occupant, or a torn attempt under the saves
 * temporary root), drives the production UI, and asserts the durable evidence: the folder content after a swap, the
 * snapshot zip, the emptied temporary root, the resume-blocked offer screen, the per-cause refusal toast, and the
 * sweep-flavored busy copy. Toast refusals are read back through {@link ToastReadback}; chat lines are read from the
 * platform bridge's log channel. The cases share one dedicated-server connection and reset the saves state between
 * them.
 */
@SuppressWarnings("UnstableApiUsage")
public class RestoreFlowTest implements FabricClientGameTest {
    private static final String BRIDGE_LOGGER = "world.thearchive.wdl.platform.AbstractPlatformBridge";
    private static final String TOGGLE_KEY = "key.wdl.toggle";
    private static final int TOGGLE_KEY_CODE = GLFW.GLFW_KEY_F13;
    private static final int IDLE_WAIT_TICKS = 400;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context);
                LogCapture chat = new LogCapture(BRIDGE_LOGGER)) {
            Path saves = context.computeOnClient(client -> client.getLevelSource().getBaseDir());
            chipRoundTrip(context, saves);
            blockedOfferPath(context, saves, chat);
            foreignOccupantRefusal(context, saves, chat);
            terminalReTest(context, saves);
            newArmTempRootRefusal(context, saves, chat);
            sweepBusyCopy(context, saves, chat);
            junctionStaleRowCauses(context, saves);
            ownerRoutedAutoJoinChat(context, saves, chat);
            nameFieldAcrossTheSweepFlip(context, saves);
        }
        singleplayerWorldOpenSilent(context);
    }

    /**
     * Case 1: a tainted folder beside its clean export zip restores through the row's restore chip and the
     * ConfirmScreen's real Restore button, and the folder is the clean copy afterward with the snapshot kept and the
     * temporary root gone.
     */
    private void chipRoundTrip(ClientGameTestContext context, Path saves) {
        String folder = "wdl-restore-1";
        beginCase(context, saves, folder, "");
        Path root = capture(context, folder);
        RestoreFixtures.taint(root);
        Check.that(tainted(context, root), "the folder should read as tainted before the restore");

        ScreenDriver.openDownloads(context);
        ScreenRectangle chip = ScreenDriver.waitForChip(context, folder);
        ScreenDriver.clickRectangle(context, chip);
        context.waitForScreen(ConfirmScreen.class);
        Check.that(screenTitleIs(context, "wdl.screen.downloads.confirm_restore.title"),
                "the restore chip should open the restore confirm");
        context.clickScreenButton("wdl.screen.downloads.restore_action");
        waitIdle(context);

        Check.that(!tainted(context, root), "the restored folder must no longer be tainted");
        Check.that(Files.exists(root.resolve("level.dat")), "the restored folder must hold the clean level.dat");
        Check.that(Files.exists(saves.resolve(folder + "-singleplayer.zip")),
                "the pre-restore snapshot -singleplayer.zip must exist");
        Check.that(!Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT)),
                "the restore temporary root must be gone after a clean restore");
        endCase(context, saves, folder);
    }

    /**
     * Case 2: on a tainted folder with blocked resume, an auto-origin join keeps the shipped tainted notice and never
     * opens the offer, while a deliberate resume opens the blocked-offer screen whose Continue restores with no second
     * prompt.
     */
    private void blockedOfferPath(ClientGameTestContext context, Path saves, LogCapture chat) {
        String folder = "localhost";
        beginCase(context, saves, folder, "blockTaintedResume=true\nautoDownload=true\n");
        Path root = capture(context, folder);
        RestoreFixtures.taint(root);

        chat.clear();
        fireJoin(context);
        Check.that(chatContains(chat, resolved("wdl.refuse.tainted.body")),
                "an auto-origin join on a blocked tainted folder must keep the shipped tainted notice");
        Check.that(context.computeOnClient(client -> !(client.screen instanceof ConfirmScreen)),
                "an auto-origin join must not open the blocked-offer screen");

        chat.clear();
        sendCommand(context, "wdl resume " + folder);
        context.waitForScreen(ConfirmScreen.class);
        Check.that(screenTitleIs(context, "wdl.screen.downloads.confirm_restore_blocked.title"),
                "a deliberate resume on a blocked tainted folder must open the blocked-offer screen");
        context.clickScreenButton("wdl.screen.downloads.restore_action");
        waitIdle(context);
        Check.that(context.computeOnClient(client -> client.screen == null),
                "the restore must run with no second prompt");
        Check.that(!tainted(context, root), "the deliberate restore must clean the tainted folder");
        Check.that(Files.exists(saves.resolve(folder + "-singleplayer.zip")),
                "the deliberate restore must keep the pre-restore snapshot");
        endCase(context, saves, folder);
    }

    /**
     * Case 3: an unmanaged folder at a download's name refuses both the quick-start keybind and the typed-name command
     * with the occupant copy, before any taint copy.
     */
    private void foreignOccupantRefusal(ClientGameTestContext context, Path saves, LogCapture chat) {
        String folder = "localhost";
        beginCase(context, saves, folder, "");
        occupantDirectory(saves.resolve(folder));

        chat.clear();
        closeScreen(context);
        pressToggle(context);
        Check.that(chatContains(chat, resolved("wdl.refuse.occupant.body_named_advice", folder)),
                "the quick-start keybind must refuse an unmanaged occupant with the name-choosing advice copy");
        Check.that(!chatContains(chat, resolved("wdl.refuse.tainted.body")),
                "the occupant refusal must come before any taint copy");

        chat.clear();
        sendCommand(context, "wdl start " + folder);
        Check.that(chatContains(chat, resolved("wdl.refuse.occupant.body_named_advice", folder)),
                "the typed-name command must refuse an unmanaged occupant with the name-choosing advice copy");
        Check.that(!chatContains(chat, resolved("wdl.refuse.tainted.body")),
                "the typed-name occupant refusal must come before any taint copy");
        endCase(context, saves, folder);
    }

    /**
     * Case 4: a tainted merge confirm whose folder becomes a session.lock husk before Continue refuses at the managed
     * re-test with the occupant copy, never the contaminated-download line, and starts no capture.
     */
    private void terminalReTest(ClientGameTestContext context, Path saves) {
        String folder = "wdl-restore-4";
        beginCase(context, saves, folder, "blockTaintedResume=false\n");
        Path root = capture(context, folder);
        RestoreFixtures.deleteRecursively(saves.resolve(folder + ".zip"));
        RestoreFixtures.taint(root);

        ScreenDriver.openDownloads(context);
        context.getInput().typeChars(folder);
        context.clickScreenButton("wdl.screen.downloads.download");
        context.waitForScreen(ConfirmScreen.class);
        Check.that(screenTitleIs(context, "wdl.screen.downloads.confirm_tainted.title"),
                "a resume on a tainted folder with blocking off must open the tainted confirm");

        RestoreFixtures.huskWithSessionLock(root);
        clearToasts(context);
        context.clickScreenButton("gui.continue");

        String toast = latestToast(context);
        Check.that(toast != null && toast.contains(resolved("wdl.refuse.occupant.title")),
                "the terminal re-test must refuse the husk with the occupant copy, saw: " + toast);
        Check.that(toast != null && !toast.contains(resolved("wdl.refuse.tainted.title")),
                "the terminal re-test must never show the contaminated-download line, saw: " + toast);
        Check.that(context.computeOnClient(client -> Wdl.state()) == CaptureState.IDLE,
                "the refused terminal re-test must start no capture");
        Check.that(Files.exists(root.resolve(RestoreFixtures.SESSION_LOCK)),
                "the husk must be untouched after the occupant refusal");
        endCase(context, saves, folder);
    }

    /**
     * Case 5: a torn attempt with the folder missing refuses a NEW start of that name with the torn-attempt notice and
     * creates no folder, then the screen open sweeps the kept-aside back into place.
     */
    private void newArmTempRootRefusal(ClientGameTestContext context, Path saves, LogCapture chat) {
        String folder = "wdl-restore-5";
        beginCase(context, saves, folder, "");
        capture(context, folder);
        RestoreFixtures.craftTornAttempt(saves, folder);
        Check.that(!Files.exists(saves.resolve(folder)), "the torn attempt must leave the live folder absent");

        chat.clear();
        sendCommand(context, "wdl start " + folder);
        Check.that(chatContains(chat, resolved("wdl.refuse.torn_attempt.body")),
                "a NEW start over a torn attempt must show the torn-attempt notice");
        Check.that(!Files.exists(saves.resolve(folder)), "the refused NEW start must create no folder");

        ScreenDriver.openDownloads(context);
        waitIdle(context);
        Check.that(Files.exists(saves.resolve(folder)), "the screen-open sweep must move the kept-aside back");
        Check.that(!Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT)),
                "the sweep must remove the emptied temporary root");
        endCase(context, saves, folder);
    }

    /**
     * Case 6: a deferred sweep workload shows the folder-less restoring label on screen open, and a quick-start while
     * the sweep holds RESTORING gets the sweep-flavored busy line.
     */
    private void sweepBusyCopy(ClientGameTestContext context, Path saves, LogCapture chat) {
        String folder = "wdl-restore-6";
        beginCase(context, saves, folder, "");
        capture(context, folder);
        try (RestoreFixtures.LockedAside aside = RestoreFixtures.craftLockedTornAttempt(saves, folder)) {
            // The screen-open sweep runs on a worker and the RESTORING flip is the single-flight guard held only
            // for that run, so a bare read would race the worker's completion. Park the worker at its run-start
            // barrier to hold RESTORING while the folder-less label is observed.
            try (SweepHold hold = holdSweep()) {
                ScreenDriver.openDownloads(context);
                Check.that(context.computeOnClient(client -> Wdl.state()) == CaptureState.RESTORING
                        && context.computeOnClient(client -> Wdl.restoringFolderName()) == null,
                        "the deferred sweep must show the folder-less restoring label on screen open");
                Check.that(waitForWidgetText(context, resolved("wdl.screen.downloads.restoring_sweep")),
                        "the restoring label must be the sweep-cleanup line");
            }
            closeScreen(context);
            waitIdle(context);

            chat.clear();
            try (SweepHold hold = holdSweep()) {
                context.runOnClient(client -> Wdl.launchSweep(saves));
                pressToggle(context);
                Check.that(waitForChat(context, chat, resolved("wdl.chat.busy_restoring_sweep")),
                        "a quick-start during the sweep must get the sweep-flavored busy line");
            }
            waitIdle(context);
        }
        endCase(context, saves, folder);
    }

    /**
     * Case 7: with the screen open, swapping a managed row's folder for a husk, then a stray file, then deleting it
     * makes row-Resume refuse at the junction with the occupant, occupant, and folder-missing causes, never a taint
     * line and never a merge confirm.
     */
    private void junctionStaleRowCauses(ClientGameTestContext context, Path saves) {
        String folder = "wdl-restore-7";
        beginCase(context, saves, folder, "");
        Path root = capture(context, folder);
        RestoreFixtures.taint(root);

        ScreenDriver.openDownloads(context);
        ScreenRectangle chip = ScreenDriver.waitForChip(context, folder);
        ScreenDriver.clickRowBody(context, chip);

        RestoreFixtures.huskWithSessionLock(root);
        refuseRow(context, "wdl.refuse.occupant.title", "an unmanaged husk row must refuse as an occupant");
        RestoreFixtures.strayFile(root);
        refuseRow(context, "wdl.refuse.occupant.title", "a stray-file row must refuse as an occupant");
        RestoreFixtures.deleteRecursively(root);
        refuseRow(context, "wdl.refuse.folder_missing.title", "a deleted row must refuse as folder-missing");
        endCase(context, saves, folder);
    }

    /**
     * Case 8 part A: during sweep-held RESTORING, an auto-download multiplayer join gets the sweep-flavored busy chat.
     * Part B (after the fixture closes) covers the silent singleplayer world open.
     */
    private void ownerRoutedAutoJoinChat(ClientGameTestContext context, Path saves, LogCapture chat) {
        beginCase(context, saves, "wdl-restore-8", "autoDownload=true\n");
        closeScreen(context);
        waitIdle(context);
        chat.clear();
        context.runOnClient(client -> Wdl.launchSweep(saves));
        fireJoin(context);
        Check.that(chatContains(chat, resolved("wdl.chat.busy_restoring_sweep")),
                "an auto-download join during a sweep must get the sweep-flavored busy chat");
        waitIdle(context);
        endCase(context, saves, "wdl-restore-8");
    }

    /**
     * Case 8 part B: opening a singleplayer world with auto-download on stays silent and starts no capture, because the
     * join hook's remote-world gate stays false for the user's own local world.
     */
    private void singleplayerWorldOpenSilent(ClientGameTestContext context) {
        writeConfig("autoDownload=true\n");
        try (LogCapture chat = new LogCapture(BRIDGE_LOGGER);
                TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(5);
            // Reads the channel rather than a list of forbidden keys, so a line nobody thought to list cannot
            // pass. The update notice rides a second join hook gated by neither the download setting nor the
            // remote-world test, so it is the one line that may legitimately land here; its prefix is taken from
            // the catalog rather than written out, so a copy edit moves it and a mismatch fails loud.
            String spoken = chatOtherThan(chat, resolved("wdl.chat.update_available", "", "", "", "").split(":")[0]);
            Check.that(spoken == null,
                    "a singleplayer world open with auto-download on must stay silent, heard: " + spoken);
            Check.that(context.computeOnClient(client -> Wdl.state()) == CaptureState.IDLE,
                    "a singleplayer world open with auto-download on must start no capture");
        }
    }

    /**
     * Case 9: a sweep flipping the open screen through the restoring view and back gives a half-typed name back and
     * gives nothing back for a row selected before the flip, a caret move over a prefill keeps that row selected, and
     * the primary action agrees with the field text throughout.
     */
    private void nameFieldAcrossTheSweepFlip(ClientGameTestContext context, Path saves) {
        String folder = "wdl-restore-9";
        beginCase(context, saves, folder, "");
        RestoreFixtures.taint(capture(context, folder));

        ScreenDriver.openDownloads(context);
        context.getInput().typeChars("half-typed");
        flipThroughSweep(context, saves);
        Check.that("half-typed".equals(waitForNameField(context)),
                "a half-typed name must survive the sweep's flip through the restoring view");
        Check.that(waitForWidgetText(context, resolved("wdl.screen.downloads.download")),
                "a typed name with no row selected must offer Download");

        ScreenDriver.clickRowBody(context, ScreenDriver.waitForChip(context, folder));
        Check.that(folder.equals(nameFieldValue(context)), "a row-body click must prefill the field with its folder");
        Check.that(waitForWidgetText(context, resolved("wdl.screen.downloads.resume")),
                "a selected row must offer Resume");
        focusNameField(context);
        context.getInput().pressKey(GLFW.GLFW_KEY_END);
        Check.that(folder.equals(nameFieldValue(context))
                && waitForWidgetText(context, resolved("wdl.screen.downloads.resume")),
                "a caret move is not an edit, so the row stays selected and the action stays Resume");

        flipThroughSweep(context, saves);
        Check.that(!folder.equals(waitForNameField(context)),
                "the flip drops the selected row, so its name must not come back under the Download action");
        endCase(context, saves, folder);
    }

    private void flipThroughSweep(ClientGameTestContext context, Path saves) {
        try (SweepHold hold = holdSweep()) {
            context.runOnClient(client -> Wdl.launchSweep(saves));
            for (int tick = 0; tick < IDLE_WAIT_TICKS && nameFieldValue(context) != null; tick++) {
                context.waitTick();
            }
            Check.that(context.computeOnClient(client -> Wdl.state()) == CaptureState.RESTORING
                    && context.computeOnClient(client -> client.screen instanceof WdlDownloadsScreen)
                    && nameFieldValue(context) == null,
                    "the open screen never rebuilt into the restoring view");
        }
        waitIdle(context);
    }

    private static String waitForNameField(ClientGameTestContext context) {
        for (int tick = 0; tick < IDLE_WAIT_TICKS; tick++) {
            String value = nameFieldValue(context);
            if (value != null) {
                return value;
            }
            context.waitTick();
        }
        throw new AssertionError("the name field never came back after the flip");
    }

    /**
     * Put the keyboard on the name field, the state a click inside it leaves behind. A row-body click cannot be used
     * for this: the container reassigns focus to the clicked child after it handles the click, so the list keeps it.
     */
    private static void focusNameField(ClientGameTestContext context) {
        context.runOnClient(client -> {
            if (client.screen instanceof WdlDownloadsScreen screen) {
                for (GuiEventListener child : screen.children()) {
                    if (child instanceof EditBox box) {
                        screen.setFocused(box);
                        return;
                    }
                }
            }
        });
    }

    /** The value in the open download screen's name field, or null when no name field is on screen. */
    private static @Nullable String nameFieldValue(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            if (!(client.screen instanceof WdlDownloadsScreen screen)) {
                return null;
            }
            for (GuiEventListener child : screen.children()) {
                if (child instanceof EditBox box) {
                    return box.getValue();
                }
            }
            return null;
        });
    }

    private void refuseRow(ClientGameTestContext context, String expectedTitleKey, String message) {
        clearToasts(context);
        context.clickScreenButton("wdl.screen.downloads.resume");
        String toast = latestToast(context);
        Check.that(toast != null && toast.contains(resolved(expectedTitleKey)), message + ", saw: " + toast);
        Check.that(toast != null && !toast.contains(resolved("wdl.refuse.tainted.title")),
                "the junction refusal must never be a taint line, saw: " + toast);
        Check.that(context.computeOnClient(client -> client.screen instanceof WdlDownloadsScreen),
                "the junction refusal must dispatch no merge confirm");
        Check.that(context.computeOnClient(client -> Wdl.state()) == CaptureState.IDLE,
                "the junction refusal must start no capture");
    }

    private static Path capture(ClientGameTestContext context, String folder) {
        return CaptureDriver.capture(context, new DownloadTarget(folder, folder, DownloadMode.NEW),
                WdlConfig.DEFAULTS, 30);
    }

    private void beginCase(ClientGameTestContext context, Path saves, String folder, String configLines) {
        closeScreen(context);
        waitIdle(context);
        RestoreOperation.RestoreSweep.resetForTest();
        RestoreFixtures.deleteRecursively(saves.resolve(RestoreOperation.TEMPORARY_ROOT));
        SaveFixture.reset(saves, folder);
        clearToasts(context);
        writeConfig(configLines);
    }

    private void endCase(ClientGameTestContext context, Path saves, String folder) {
        closeScreen(context);
        waitIdle(context);
        RestoreFixtures.deleteRecursively(saves.resolve(RestoreOperation.TEMPORARY_ROOT));
        SaveFixture.reset(saves, folder);
        clearToasts(context);
        RestoreOperation.RestoreSweep.resetForTest();
    }

    private static void occupantDirectory(Path folder) {
        try {
            Files.createDirectories(folder);
            Files.write(folder.resolve("not-a-download.txt"), new byte[] { 1 });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean tainted(ClientGameTestContext context, Path folder) {
        return context.computeOnClient(client -> SinglePlayerTaint.isTainted(folder));
    }

    private static boolean screenTitleIs(ClientGameTestContext context, String titleKey) {
        String expected = resolved(titleKey);
        return context.computeOnClient(client -> client.screen != null
                && client.screen.getTitle().getString().equals(expected));
    }

    private static boolean hasWidgetText(ClientGameTestContext context, String expected) {
        return context.computeOnClient(client -> {
            if (client.screen == null) {
                return false;
            }
            for (GuiEventListener child : client.screen.children()) {
                if (child instanceof AbstractWidget widget
                        && widget.getMessage().getString().equals(expected)) {
                    return true;
                }
            }
            return false;
        });
    }

    private static @Nullable String latestToast(ClientGameTestContext context) {
        return context.computeOnClient(ToastReadback::latestText);
    }

    private static void clearToasts(ClientGameTestContext context) {
        context.runOnClient(ToastReadback::clear);
    }

    private static void sendCommand(ClientGameTestContext context, String command) {
        context.runOnClient(client -> {
            if (client.getConnection() != null) {
                client.getConnection().sendCommand(command);
            }
        });
    }

    private static void closeScreen(ClientGameTestContext context) {
        context.runOnClient(client -> client.setScreen(null));
        context.waitForScreen(null);
    }

    private void waitIdle(ClientGameTestContext context) {
        for (int tick = 0; tick < IDLE_WAIT_TICKS
                && context.computeOnClient(client -> Wdl.state()) != CaptureState.IDLE; tick++) {
            context.waitTick();
        }
        Check.that(context.computeOnClient(client -> Wdl.state()) == CaptureState.IDLE,
                "the controller did not return to idle");
    }

    private static void pressToggle(ClientGameTestContext context) {
        context.runOnClient(client -> {
            for (KeyMapping mapping : client.options.keyMappings) {
                if (mapping.getName().equals(TOGGLE_KEY)) {
                    mapping.setKey(InputConstants.Type.KEYSYM.getOrCreate(TOGGLE_KEY_CODE));
                }
            }
            KeyMapping.resetMapping();
        });
        context.getInput().pressKey(TOGGLE_KEY_CODE);
    }

    private static void fireJoin(ClientGameTestContext context) {
        context.runOnClient(client -> {
            ClientPacketListener connection = client.getConnection();
            if (connection != null) {
                ClientPlayConnectionEvents.JOIN.invoker().onPlayReady(connection,
                        ClientPlayNetworking.getSender(), client);
            }
        });
    }

    // A key the catalog does not carry renders back as the key itself, which no chat line or toast ever contains, so
    // an assertion that some copy is ABSENT passes whatever the code did. Catching it here covers every call site.
    private static String resolved(String key, Object... args) {
        Check.that(Language.getInstance().has(key), "no catalog entry for " + key);
        return Component.translatable(key, args).getString();
    }

    private static @Nullable String chatOtherThan(LogCapture chat, String allowed) {
        for (LogEvent event : chat.events()) {
            String message = event.getMessage().getFormattedMessage();
            if (!message.contains(allowed)) {
                return message;
            }
        }
        return null;
    }

    private static boolean chatContains(LogCapture chat, String expected) {
        for (LogEvent event : chat.events()) {
            if (event.getMessage().getFormattedMessage().contains(expected)) {
                return true;
            }
        }
        return false;
    }

    private static void writeConfig(String configLines) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("wdl.properties");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, "appendDateSuffix=false\n" + configLines);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Installs a run-start barrier so the next sweep worker parks as it begins, holding the single-flight RESTORING
     * flip until the returned handle is closed, which releases the worker and restores the no-op barrier.
     */
    private static SweepHold holdSweep() {
        CountDownLatch release = new CountDownLatch(1);
        RestoreOperation.RestoreSweep.runStartBarrierForTest(() -> awaitUninterruptibly(release));
        return new SweepHold(release);
    }

    private static final class SweepHold implements AutoCloseable {
        private final CountDownLatch release;

        private SweepHold(CountDownLatch release) {
            this.release = release;
        }

        @Override
        public void close() {
            release.countDown();
            RestoreOperation.RestoreSweep.runStartBarrierForTest(() -> {});
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean waitForWidgetText(ClientGameTestContext context, String expected) {
        for (int tick = 0; tick < IDLE_WAIT_TICKS && !hasWidgetText(context, expected); tick++) {
            context.waitTick();
        }
        return hasWidgetText(context, expected);
    }

    private static boolean waitForChat(ClientGameTestContext context, LogCapture chat, String expected) {
        for (int tick = 0; tick < IDLE_WAIT_TICKS && !chatContains(chat, expected); tick++) {
            context.waitTick();
        }
        return chatContains(chat, expected);
    }
}
