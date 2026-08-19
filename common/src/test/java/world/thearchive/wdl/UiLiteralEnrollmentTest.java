// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Fail-closed guard that new user-facing UI text lands as a translation key, not a hardcoded literal. Every user-facing
 * string is a {@code Component.translatable(...)} keyed under the {@code wdl} namespace; the
 * {@code new TextComponent(...)} calls left in the view layer render data or glyphs, never prose, and each is enrolled
 * below with the reason it is not text. This test pins that inventory per file, so a newly hardcoded literal fails the
 * build until it is classified: made a translation key if it is text, or enrolled here with a note if it is data or a
 * glyph. Same fail-closed enrollment shape as {@link PitestAllowlistEnrollmentTest}.
 */
class UiLiteralEnrollmentTest {
    private static final String NEEDLE = "new TextComponent(";

    private static final List<Path> VIEW_ROOTS = ImmutableList.of(
            Paths.get("src/main/java/world/thearchive/wdl"),
            Paths.get("../fabric/src/main/java/world/thearchive/wdl"),
            Paths.get("../neoforge/src/main/java/world/thearchive/wdl"));

    // File to number of new TextComponent(...) calls, each one data or a glyph, never user-facing prose.
    private static final Map<String, Integer> ACKNOWLEDGED_UI_LITERALS = ImmutableMap.of(
            // the one amber-argument helper every confirm body's data insert (folder, backup or source zip
            // name, snapshot name) routes through
            "ResumeConfirm.java", 1,
            "WdlDownloadsScreen.java", 4, // disclosure triangle, update banner label and url
            // the chat and toast arguments core flattened to data, plus the pause-menu config button's ellipsis
            // glyph, which lives on the shared bridge rather than duplicated on each loader
            "AbstractPlatformBridge.java", 4,
            // the empty no-phase HUD label placeholder, phaseLabel's NONE case
            "WdlHudOverlay.java", 1);

    @Test
    void everyViewLayerLiteralIsAcknowledgedData() {
        Map<String, Integer> found = new TreeMap<>();
        for (Path root : VIEW_ROOTS) {
            assertTrue(Files.isDirectory(root), "view-layer source root not found: " + root.toAbsolutePath());
            for (Path file : javaFiles(root)) {
                int count = literalCount(file);
                if (count > 0) {
                    found.merge(file.getFileName().toString(), count, Integer::sum);
                }
            }
        }
        assertEquals(
                new TreeMap<>(ACKNOWLEDGED_UI_LITERALS),
                found,
                "The view layer's new TextComponent(...) inventory changed. Every user-facing\n"
                        + "string must be a Component.translatable(...) keyed under wdl.*, never a\n"
                        + "hardcoded literal. Component.literal is only for a data value or a\n"
                        + "non-linguistic glyph. Classify the change: make it a translation key if it\n"
                        + "is text, or enroll it in ACKNOWLEDGED_UI_LITERALS with a note if it is not.");
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> entries = Files.walk(root)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".java") && !name.equals("package-info.java");
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk " + root.toAbsolutePath(), e);
        }
    }

    private static int literalCount(Path file) {
        String content;
        try {
            content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file.toAbsolutePath(), e);
        }
        int count = 0;
        for (int at = content.indexOf(NEEDLE); at >= 0; at = content.indexOf(NEEDLE, at + NEEDLE.length())) {
            count++;
        }
        return count;
    }
}
