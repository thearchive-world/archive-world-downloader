// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

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
 * here against the shipped en_us lang file, and the tints ride the composed keys and arguments rather than the strings
 * the catalogs carry.
 */
class ChatCopyTest {
    private static final Map<String, String> LANG = Collections.unmodifiableMap(loadLang());

    @Test
    void downloadingResolvesWithTheFolderNameAmberOverIvoryTemplate() {
        ChatCopy line = ChatCopy.downloading("base_20260703");

        assertEquals("Downloading base_20260703.", resolve(line));
        assertEquals(OptionalInt.of(BrandColors.IVORY), line.templateColor());
        assertEquals(List.of("base_20260703"), tintedTexts(line, BrandColors.AMBER));
        assertEquals(0, clickableCount(line));
    }

    @Test
    void startNeedsNameResolvesAsIvoryTemplateOnly() {
        ChatCopy line = ChatCopy.startNeedsName();

        assertEquals("A download needs a name. Use /wdl start <name> to begin.", resolve(line));
        assertEquals(OptionalInt.of(BrandColors.IVORY), line.templateColor());
        assertEquals(List.of(), line.arguments());
    }

    @Test
    void savingResolvesAsIvoryTemplateOnly() {
        ChatCopy line = ChatCopy.saving();

        assertEquals("Saving...", resolve(line));
        assertEquals(OptionalInt.of(BrandColors.IVORY), line.templateColor());
        assertEquals(List.of(), line.arguments());
    }

    @Test
    void savingInProgressResolvesAsIvoryTemplateOnly() {
        ChatCopy line = ChatCopy.savingInProgress();

        assertEquals("Still saving the download. Try again when it's done.", resolve(line));
        assertEquals(OptionalInt.of(BrandColors.IVORY), line.templateColor());
        assertEquals(List.of(), line.arguments());
    }

    @Test
    void downloadedResolvesTheSummaryWithEveryValueAmberAndDistinct() {
        ChatCopy line = ChatCopy.downloaded("w", 1234, 56, 7, 125_000);

        assertEquals("Downloaded w: chunks 1234, entities 56, containers 7 in 2:05.", resolve(line));
        assertEquals(OptionalInt.of(BrandColors.IVORY), line.templateColor());
        assertEquals(List.of("w", "1234", "56", "7", "2:05"), tintedTexts(line, BrandColors.AMBER));
        assertEquals(List.of(), tintedTexts(line, BrandColors.TEAL));
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
    void statusRecordingResolvesWithAmberCounts() {
        ChatCopy line = ChatCopy.status(CaptureState.RECORDING, new CaptureCounts(580, 7, 341));

        assertEquals("Downloading: chunks 580, entities 341, containers 7.", resolve(line));
        assertEquals(OptionalInt.of(BrandColors.IVORY), line.templateColor());
        assertEquals(List.of("580", "341", "7"), tintedTexts(line, BrandColors.AMBER));
    }

    @Test
    void statusSavingAndIdleResolveAsIvoryTemplates() {
        ChatCopy saving = ChatCopy.status(CaptureState.SAVING, CaptureCounts.EMPTY);
        ChatCopy idle = ChatCopy.status(CaptureState.IDLE, CaptureCounts.EMPTY);

        assertEquals("Saving the download to disk...", resolve(saving));
        assertEquals("Idle. Use /wdl start <name> to begin downloading.", resolve(idle));
        assertEquals(OptionalInt.of(BrandColors.IVORY), saving.templateColor());
        assertEquals(OptionalInt.of(BrandColors.IVORY), idle.templateColor());
    }

    @Test
    void statusCarriesNoTip() {
        assertFalse(resolve(ChatCopy.status(CaptureState.RECORDING, CaptureCounts.EMPTY)).contains("Tip:"),
                "the recording status must not bring back the retired Tip nudge");
        assertFalse(resolve(ChatCopy.status(CaptureState.IDLE, CaptureCounts.EMPTY)).contains("Tip:"),
                "the idle status must not bring back the retired Tip nudge");
    }

    @Test
    void noticesResolveAsIvoryTemplates() {
        List<ChatCopy> notices = List.of(ChatCopy.alreadyDownloading(), ChatCopy.notDownloading(),
                ChatCopy.joinMultiplayer());

        assertEquals("Already downloading.", resolve(notices.get(0)));
        assertEquals("Not downloading. Use /wdl start <name> to begin.", resolve(notices.get(1)));
        assertEquals("Join a multiplayer server to download its world.", resolve(notices.get(2)));
        for (ChatCopy notice : notices) {
            assertEquals(OptionalInt.of(BrandColors.IVORY), notice.templateColor(), notice.translationKey());
        }
    }

    @Test
    void configLinesWearTheIdentityTreatment() {
        ChatCopy file = ChatCopy.configFile("/config/wdl.properties");
        ChatCopy values = ChatCopy.data("captureEntities=true");

        assertEquals("Config file: /config/wdl.properties", resolve(file));
        assertEquals(List.of("/config/wdl.properties"), tintedTexts(file, BrandColors.AMBER));
        assertEquals("captureEntities=true", resolve(values));
        assertEquals(OptionalInt.of(BrandColors.IVORY), values.templateColor());
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
                        .add(argument.text().isEmpty() ? pattern : substitute(pattern, List.of(argument.text())));
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
}
