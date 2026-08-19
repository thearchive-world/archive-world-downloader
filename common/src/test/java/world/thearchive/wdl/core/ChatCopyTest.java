// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * The download chat copy is MC-free and unit-tested headless: the line templates are wdl.chat translation keys resolved
 * here against the shipped en_us lang file, the three-tone treatment (ivory line template, amber inserted values, teal
 * link) rides the composed keys and arguments, and the click affordances carry their raw targets for the platform
 * bridge to render.
 */
class ChatCopyTest {
    private static final Map<String, String> LANG = Collections.unmodifiableMap(loadLang());

    @Test
    void downloadingResolvesWithTheFolderNameAmberOverIvoryTemplate() {
        ChatCopy line = ChatCopy.downloading("base_20260703");

        assertEquals("Downloading base_20260703.", resolve(line));
        assertEquals(OptionalInt.of(BrandColors.IVORY), line.templateColor());
        assertEquals(ImmutableList.of("base_20260703"), tintedTexts(line, BrandColors.AMBER));
        assertEquals(0, clickableCount(line));
    }

    @Test
    void resumingResolvesWithTheFolderNameAmberOverIvoryTemplate() {
        ChatCopy line = ChatCopy.resuming("base_20260703");

        assertEquals("Resuming base_20260703.", resolve(line));
        assertEquals(OptionalInt.of(BrandColors.IVORY), line.templateColor());
        assertEquals(ImmutableList.of("base_20260703"), tintedTexts(line, BrandColors.AMBER));
        assertEquals(0, clickableCount(line));
    }

    @Test
    void downloadExistsPointsToResumeWithTheNameAmber() {
        ChatCopy line = ChatCopy.downloadExists("museum");

        assertEquals("\"museum\" already exists. Use /wdl resume museum to continue it.", resolve(line));
        assertEquals(ImmutableList.of("museum", "museum"), tintedTexts(line, BrandColors.AMBER));
    }

    @Test
    void startNeedsNameResolvesAsIvoryTemplateOnly() {
        ChatCopy line = ChatCopy.startNeedsName();

        assertEquals("A download needs a name. Use /wdl start <name> to begin.", resolve(line));
        assertEquals(OptionalInt.of(BrandColors.IVORY), line.templateColor());
        assertEquals(ImmutableList.of(), line.arguments());
    }

    @Test
    void savingResolvesAsIvoryTemplateOnly() {
        ChatCopy line = ChatCopy.saving();

        assertEquals("Saving...", resolve(line));
        assertEquals(OptionalInt.of(BrandColors.IVORY), line.templateColor());
        assertEquals(ImmutableList.of(), line.arguments());
    }

    @Test
    void savingInProgressResolvesAsIvoryTemplateOnly() {
        ChatCopy line = ChatCopy.savingInProgress();

        assertEquals("Still saving the download. Try again when it's done.", resolve(line));
        assertEquals(OptionalInt.of(BrandColors.IVORY), line.templateColor());
        assertEquals(ImmutableList.of(), line.arguments());
    }

    @Test
    void busyPicksTheRunningSavingOrRestoringRefusalByState() {
        assertEquals("Already downloading.", resolve(ChatCopy.busy(CaptureState.RECORDING, false)));
        assertEquals("Still saving the download. Try again when it's done.",
                resolve(ChatCopy.busy(CaptureState.SAVING, false)));
        assertEquals("Still restoring the download. Try again when it's done.",
                resolve(ChatCopy.busy(CaptureState.RESTORING, false)));
        assertEquals("Still cleaning up an earlier restore. Try again when it's done.",
                resolve(ChatCopy.busy(CaptureState.RESTORING, true)));
    }

    @Test
    void busyRestoringLinesResolveAsIvoryTemplatesOnTheirPinnedKeys() {
        ChatCopy restore = ChatCopy.busy(CaptureState.RESTORING, false);
        ChatCopy sweep = ChatCopy.busy(CaptureState.RESTORING, true);

        assertEquals("wdl.chat.busy_restoring", restore.translationKey());
        assertEquals("wdl.chat.busy_restoring_sweep", sweep.translationKey());
        assertEquals(OptionalInt.of(BrandColors.IVORY), restore.templateColor());
        assertEquals(OptionalInt.of(BrandColors.IVORY), sweep.templateColor());
        assertEquals(ImmutableList.of(), restore.arguments());
        assertEquals(ImmutableList.of(), sweep.arguments());
    }

    @Test
    void downloadedResolvesTheSummaryWithEveryValueAmberAndDistinct() {
        ChatCopy line = ChatCopy.downloaded("w", 1234, 56, 7, 125_000);

        assertEquals("Downloaded w: chunks 1234, entities 56, containers 7 in 2:05.", resolve(line));
        assertEquals(OptionalInt.of(BrandColors.IVORY), line.templateColor());
        assertEquals(ImmutableList.of("w", "1234", "56", "7", "2:05"), tintedTexts(line, BrandColors.AMBER));
        assertEquals(ImmutableList.of(), tintedTexts(line, BrandColors.TEAL));
        assertEquals(0, clickableCount(line));
    }

    @Test
    void downloadedCountsReadAsLabelThenNumber() {
        ChatCopy line = ChatCopy.downloaded("w", 1, 1, 1, 7_000);

        assertEquals("Downloaded w: chunks 1, entities 1, containers 1 in 0:07.", resolve(line));
    }

    @Test
    void downloadedElapsedIsHourAware() {
        ChatCopy line = ChatCopy.downloaded("w", 1, 1, 1, 3_753_000);

        assertEquals("Downloaded w: chunks 1, entities 1, containers 1 in 1:02:33.", resolve(line));
    }

    @Test
    void downloadIncompleteRendersAmberWithTheFailedCount() {
        ChatCopy line = ChatCopy.downloadIncomplete(5);

        assertEquals("Note: this download is incomplete. 5 could not be saved; check the log.", resolve(line));
        assertEquals(OptionalInt.of(BrandColors.AMBER), line.templateColor());
        assertEquals(ImmutableList.of("5"), tintedTexts(line, BrandColors.AMBER));
    }

    @Test
    void savedToCarriesTheTealOpenFolderLink() {
        ChatCopy line = ChatCopy.savedTo("w", "/saves/w");

        assertEquals("Saved to w. (click to open save folder)", resolve(line));
        assertEquals(ImmutableList.of("w"), tintedTexts(line, BrandColors.AMBER));
        assertEquals(1, clickableCount(line));
        ChatCopy.Argument link = clickableArguments(line).get(0);
        assertEquals(OptionalInt.of(BrandColors.TEAL), link.color());
        ChatCopy.Click click = requireClick(link);
        assertEquals(ChatCopy.Click.Kind.OPEN_FILE, click.kind());
        assertEquals("/saves/w", click.target());
    }

    @Test
    void updateAvailableWearsTheThreeToneTreatment() {
        ChatCopy line = ChatCopy.updateAvailable("1.0.0-SNAPSHOT", "1.2.0",
                "https://example.invalid/modrinth", "https://example.invalid/curseforge");

        assertEquals("Archive World Downloader update available: 1.0.0-SNAPSHOT → 1.2.0. Modrinth CurseForge",
                resolve(line));
        assertEquals(OptionalInt.of(BrandColors.IVORY), line.templateColor());
        assertEquals(ImmutableList.of("1.0.0-SNAPSHOT", "1.2.0"), tintedTexts(line, BrandColors.AMBER));
        assertEquals(ImmutableList.of("Modrinth", "CurseForge"), tintedTexts(line, BrandColors.TEAL));
    }

    @Test
    void statusRecordingResolvesWithAmberCounts() {
        ChatCopy line = ChatCopy.status(CaptureState.RECORDING, new CaptureCounts(580, 7, 341), false);

        assertEquals("Downloading: chunks 580, entities 341, containers 7.", resolve(line));
        assertEquals(OptionalInt.of(BrandColors.IVORY), line.templateColor());
        assertEquals(ImmutableList.of("580", "341", "7"), tintedTexts(line, BrandColors.AMBER));
    }

    @Test
    void statusSavingAndIdleResolveAsIvoryTemplates() {
        ChatCopy saving = ChatCopy.status(CaptureState.SAVING, CaptureCounts.EMPTY, false);
        ChatCopy idle = ChatCopy.status(CaptureState.IDLE, CaptureCounts.EMPTY, false);

        assertEquals("Saving the download to disk...", resolve(saving));
        assertEquals("Idle. Use /wdl start <name> to begin downloading.", resolve(idle));
        assertEquals(OptionalInt.of(BrandColors.IVORY), saving.templateColor());
        assertEquals(OptionalInt.of(BrandColors.IVORY), idle.templateColor());
    }

    @Test
    void statusRestoringResolvesTheRestoreOrSweepLine() {
        ChatCopy restoring = ChatCopy.status(CaptureState.RESTORING, CaptureCounts.EMPTY, false);
        ChatCopy sweep = ChatCopy.status(CaptureState.RESTORING, CaptureCounts.EMPTY, true);

        assertEquals("Restoring the download from its backup...", resolve(restoring));
        assertEquals("Cleaning up an earlier restore...", resolve(sweep));
        assertEquals("wdl.chat.status.restoring", restoring.translationKey());
        assertEquals("wdl.chat.status.restoring_sweep", sweep.translationKey());
        assertEquals(OptionalInt.of(BrandColors.IVORY), restoring.templateColor());
        assertEquals(OptionalInt.of(BrandColors.IVORY), sweep.templateColor());
    }

    @Test
    void statusCarriesNoTip() {
        assertFalse(resolve(ChatCopy.status(CaptureState.RECORDING, CaptureCounts.EMPTY, false)).contains("Tip:"),
                "the recording status must not bring back the retired Tip nudge");
        assertFalse(resolve(ChatCopy.status(CaptureState.IDLE, CaptureCounts.EMPTY, false)).contains("Tip:"),
                "the idle status must not bring back the retired Tip nudge");
    }

    @Test
    void noticesResolveAsIvoryTemplates() {
        List<ChatCopy> notices = ImmutableList.of(ChatCopy.alreadyDownloading(), ChatCopy.notDownloading(),
                ChatCopy.joinMultiplayer(), ChatCopy.resumeCancelled(), ChatCopy.nothingCaptured(),
                ChatCopy.worldNameFallback(), ChatCopy.refuseTainted(), ChatCopy.refuseLoaded());

        assertEquals("Already downloading.", resolve(notices.get(0)));
        assertEquals("Not downloading. Use /wdl start <name> to begin.", resolve(notices.get(1)));
        assertEquals("Join a multiplayer server to download its world.", resolve(notices.get(2)));
        assertEquals("Resume cancelled.", resolve(notices.get(3)));
        assertEquals("Nothing downloaded. No chunks were saved.", resolve(notices.get(4)));
        assertEquals("Could not read the existing world name, so the default name is used.", resolve(notices.get(5)));
        assertEquals("This world was opened in singleplayer and may contain generated (non-server) chunks. "
                + "Start a fresh download instead of resuming.", resolve(notices.get(6)));
        assertEquals("That world is currently open.", resolve(notices.get(7)));
        for (ChatCopy notice : notices) {
            assertEquals(OptionalInt.of(BrandColors.IVORY), notice.templateColor(), notice.translationKey());
        }
    }

    @Test
    void flowChannelStartRefusalsShareTheToastBodiesAsIvoryLines() {
        ChatCopy occupant = ChatCopy.refuseOccupant("base", false);
        ChatCopy occupantAdvice = ChatCopy.refuseOccupant("base", true);
        ChatCopy folderMissing = ChatCopy.refuseFolderMissing("base");
        ChatCopy tornAttempt = ChatCopy.refuseTornAttempt();

        assertEquals("wdl.refuse.occupant.body", occupant.translationKey());
        assertEquals("wdl.refuse.occupant.body_named_advice", occupantAdvice.translationKey());
        assertEquals("wdl.refuse.folder_missing.body", folderMissing.translationKey());
        assertEquals("wdl.refuse.torn_attempt.body", tornAttempt.translationKey());
        assertEquals("\"base\" is taken by something that isn't a download.", resolve(occupant));
        assertEquals("\"base\" is taken by something that isn't a download. "
                + "Choose another name, or move the folder aside.", resolve(occupantAdvice));
        assertEquals("The download folder \"base\" no longer exists.", resolve(folderMissing));
        assertEquals("An earlier restore left unfinished cleanup for that name. "
                + "Open the Downloads screen to finish it, or wait for the cleanup to complete.",
                resolve(tornAttempt));
        for (ChatCopy refusal : ImmutableList.of(occupant, occupantAdvice, folderMissing, tornAttempt)) {
            assertEquals(OptionalInt.of(BrandColors.IVORY), refusal.templateColor(), refusal.translationKey());
        }
        for (ChatCopy named : ImmutableList.of(occupant, occupantAdvice, folderMissing)) {
            assertEquals("base", named.arguments().get(0).text(), named.translationKey());
            assertEquals(OptionalInt.of(BrandColors.AMBER), named.arguments().get(0).color());
        }
        assertEquals(ImmutableList.of(), tornAttempt.arguments());
    }

    @Test
    void noSuchDownloadTintsTheNameAmber() {
        ChatCopy line = ChatCopy.noSuchDownload("w");

        assertEquals("No download named \"w\". Use /wdl start <name> to begin a new one.", resolve(line));
        assertEquals(ImmutableList.of("w"), tintedTexts(line, BrandColors.AMBER));
    }

    @Test
    void gameRuleOverridesSkippedResolvesCountFreeOverIvory() {
        ChatCopy line = ChatCopy.gameRuleOverridesSkipped("doFoo, doBar");

        assertEquals("Some game-rule overrides don't apply to this Minecraft version and were skipped: "
                + "doFoo, doBar", resolve(line));
        assertEquals(OptionalInt.of(BrandColors.IVORY), line.templateColor());
        assertEquals(ImmutableList.of(), tintedTexts(line, BrandColors.AMBER));
    }

    @Test
    void capturePartiallyDisabledIsWhollyAmber() {
        ChatCopy line = ChatCopy.capturePartiallyDisabled();

        assertEquals("Note: some content toggles are off, so this download will be missing that content.",
                resolve(line));
        assertEquals(OptionalInt.of(BrandColors.AMBER), line.templateColor(),
                "the caution rides the amber template, not the ivory notice template");
        assertEquals(ImmutableList.of(), line.arguments());
        assertEquals(0, clickableCount(line));
    }

    @Test
    void saveFailedIsWhollyRust() {
        ChatCopy line = ChatCopy.saveFailed(SaveFailureReason.keyed("wdl.reason.access_denied"));

        assertEquals("Save failed: access denied", resolve(line));
        assertEquals(OptionalInt.of(BrandColors.RUST), line.templateColor());
        assertEquals(OptionalInt.empty(), line.arguments().get(0).color(), "the description inherits the rust");
    }

    @Test
    void configLinesWearTheIdentityTreatment() {
        ChatCopy file = ChatCopy.configFile("/config/wdl.properties");
        ChatCopy values = ChatCopy.data("captureEntities=true");

        assertEquals("Config file: /config/wdl.properties", resolve(file));
        assertEquals(ImmutableList.of("/config/wdl.properties"), tintedTexts(file, BrandColors.AMBER));
        assertEquals("captureEntities=true", resolve(values));
        assertEquals(OptionalInt.of(BrandColors.IVORY), values.templateColor());
    }

    @Test
    void updateAvailableLinksOpenTheTwoPageUrls() {
        ChatCopy line = ChatCopy.updateAvailable("1.0.0-SNAPSHOT", "1.2.0",
                "https://example.invalid/modrinth", "https://example.invalid/curseforge");

        List<ChatCopy.Argument> links = clickableArguments(line);
        assertEquals(2, links.size());
        assertEquals("Modrinth", links.get(0).text());
        assertEquals(ChatCopy.Click.Kind.OPEN_URL, requireClick(links.get(0)).kind());
        assertEquals("https://example.invalid/modrinth", requireClick(links.get(0)).target());
        assertEquals("CurseForge", links.get(1).text());
        assertEquals(ChatCopy.Click.Kind.OPEN_URL, requireClick(links.get(1)).kind());
        assertEquals("https://example.invalid/curseforge", requireClick(links.get(1)).target());
    }

    @Test
    void onlyTheFolderLinkArgumentCarriesItsClickTarget() {
        ChatCopy line = ChatCopy.savedTo("w", "/saves/w");

        for (ChatCopy.Argument argument : line.arguments()) {
            if (!"wdl.chat.open_save_folder".equals(argument.translationKey())) {
                assertNull(argument.click(), argument.text());
            }
        }
        assertEquals(1, clickableCount(line));
    }

    /** Resolves a composed line to its en_us copy, failing on any key the lang file does not carry. */
    private static String resolve(ChatCopy line) {
        List<String> resolvedArguments = new ArrayList<>();
        for (ChatCopy.Argument argument : line.arguments()) {
            if (argument.translationKey() == null) {
                resolvedArguments.add(argument.text());
            } else {
                String pattern = requireKey(argument.translationKey());
                resolvedArguments
                        .add(argument.text().isEmpty() ? pattern
                                : substitute(pattern, ImmutableList.of(argument.text())));
            }
        }
        return substitute(requireKey(line.translationKey()), resolvedArguments);
    }

    private static String substitute(String pattern, List<String> arguments) {
        StringBuilder resolved = new StringBuilder();
        int from = 0;
        for (String argument : arguments) {
            int slot = pattern.indexOf("%s", from);
            if (slot < 0) {
                fail("more arguments than %s slots in \"" + pattern + "\"");
            }
            resolved.append(pattern, from, slot).append(argument);
            from = slot + 2;
        }
        assertEquals(-1, pattern.indexOf("%s", from), "unfilled %s slot in \"" + pattern + "\"");
        return resolved.append(pattern.substring(from)).toString();
    }

    private static String requireKey(String key) {
        String pattern = LANG.get(key);
        assertNotNull(pattern, "en_us.json is missing " + key);
        return pattern;
    }

    private static Map<String, String> loadLang() {
        InputStream stream = ChatCopyTest.class.getResourceAsStream("/assets/wdl/lang/en_us.json");
        if (stream == null) {
            throw new IllegalStateException("en_us.json not on the test classpath");
        }
        return new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8),
                new TypeToken<Map<String, String>>() {}.getType());
    }

    private static List<String> tintedTexts(ChatCopy line, int colorRgb) {
        List<String> texts = new ArrayList<>();
        for (ChatCopy.Argument argument : line.arguments()) {
            if (argument.color().isPresent() && argument.color().getAsInt() == colorRgb) {
                texts.add(argument.text());
            }
        }
        return texts;
    }

    private static List<ChatCopy.Argument> clickableArguments(ChatCopy line) {
        List<ChatCopy.Argument> arguments = new ArrayList<>();
        for (ChatCopy.Argument argument : line.arguments()) {
            if (argument.click() != null) {
                arguments.add(argument);
            }
        }
        return arguments;
    }

    private static int clickableCount(ChatCopy line) {
        return clickableArguments(line).size();
    }

    private static ChatCopy.Click requireClick(ChatCopy.Argument argument) {
        ChatCopy.Click click = argument.click();
        assertNotNull(click);
        return click;
    }
}
