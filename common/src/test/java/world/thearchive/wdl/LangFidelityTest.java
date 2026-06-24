// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The lang-file fidelity gate: every shipped locale mirrors en_us's key set and order, is placeholder-safe, and is
 * written in one canonical byte shape. en_us is the source of truth, verbatim: every locale carries exactly its key set
 * in order. This test is the build-time enforcement that a hand-edit or a bad sync cannot slip a drifted locale into a
 * release. MC-free: pure resource checks, reading the source files from the module tree (the
 * {@link PitestAllowlistEnrollmentTest} pattern).
 */
class LangFidelityTest {
    private static final Path LANG_DIRECTORY = Path.of("src/main/resources/assets/wdl/lang");
    private static final String SOURCE = "en_us.json";

    // The only conversion shapes the lang files use: %s, positional %1$s/%2$s, and the %% literal.
    private static final Pattern token = Pattern.compile("%(?:(\\d+)\\$)?([a-zA-Z%])");

    @Test
    void everyLocaleMirrorsTheEnUsKeySetInOrder() {
        List<String> expected = new ArrayList<>(loadOrdered(LANG_DIRECTORY.resolve(SOURCE)).keySet());
        for (Path locale : localeFiles()) {
            List<String> actual = new ArrayList<>(loadOrdered(locale).keySet());
            assertEquals(expected, actual, locale.getFileName() + " keys are not the en_us set in order");
        }
    }

    @Test
    void everyKeyReferencesTheSameFormatArgumentsAcrossLocales() {
        Map<String, String> en = loadOrdered(LANG_DIRECTORY.resolve(SOURCE));
        for (Path locale : localeFiles()) {
            Map<String, String> data = loadOrdered(locale);
            for (Map.Entry<String, String> entry : data.entrySet()) {
                String expected = en.get(entry.getKey());
                if (expected == null) {
                    continue; // key-set parity is asserted separately
                }
                assertEquals(
                        placeholderArguments(expected),
                        placeholderArguments(entry.getValue()),
                        locale.getFileName() + " placeholder mismatch on " + entry.getKey()
                                + " (drops, adds, or references an out-of-range format argument)");
            }
        }
    }

    @Test
    void everyLangFileIsCanonicalAndHygienic() {
        List<Path> files = new ArrayList<>(localeFiles());
        files.add(LANG_DIRECTORY.resolve(SOURCE));
        for (Path file : files) {
            String name = file.getFileName().toString();
            byte[] bytes = readBytes(file);
            String content = new String(bytes, UTF_8);
            assertEquals(bytes.length, content.getBytes(UTF_8).length, name + " is not valid UTF-8");
            assertFalse(content.startsWith("﻿"), name + " has a UTF-8 BOM");
            assertFalse(content.contains("\\u"), name + " uses \\u escapes; write literal UTF-8 characters");
            assertFalse(content.contains("\t"), name + " uses a tab; indent with two spaces");
            assertTrue(content.startsWith("{\n"), name + " must open with '{' on its own line");
            assertTrue(content.endsWith("}\n"), name + " must end with '}' and a single trailing newline");
            assertFalse(content.endsWith("\n\n"), name + " has a trailing blank line");
            String[] lines = content.split("\n", -1);
            for (int i = 1; i < lines.length - 2; i++) { // between the opening { and closing }
                String line = lines[i];
                assertTrue(line.startsWith("  \""), name + " line " + (i + 1) + " is not indented two spaces");
                assertFalse(line.endsWith(" "), name + " line " + (i + 1) + " has trailing whitespace");
            }
        }
    }

    /**
     * The set of format-argument positions a string references, by Java Formatter rules: a bare %s consumes the next
     * sequential argument, %k$s references argument k explicitly, and %% is a literal. A locale may reorder and switch
     * between %s and %1$s freely (it routinely must, to fit its grammar). Only a dropped, added, or out-of-range
     * argument, which is how a translated line crashes or loses a value, changes the set.
     */
    private static Set<Integer> placeholderArguments(String value) {
        Set<Integer> arguments = new TreeSet<>();
        int auto = 0;
        Matcher matcher = token.matcher(value);
        while (matcher.find()) {
            String index = matcher.group(1);
            if ("%".equals(matcher.group(2))) {
                continue;
            }
            arguments.add(index != null ? Integer.parseInt(index) : ++auto);
        }
        return arguments;
    }

    private static List<Path> localeFiles() {
        try (Stream<Path> entries = Files.list(LANG_DIRECTORY)) {
            return entries
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals(SOURCE))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + LANG_DIRECTORY.toAbsolutePath(), e);
        }
    }

    private static LinkedHashMap<String, String> loadOrdered(Path path) {
        try {
            return new Gson().fromJson(
                    Files.readString(path, UTF_8), new TypeToken<LinkedHashMap<String, String>>() {}.getType());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path.toAbsolutePath(), e);
        }
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path.toAbsolutePath(), e);
        }
    }
}
