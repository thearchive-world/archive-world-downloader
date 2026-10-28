// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class HandBuiltFixtureGuardTest {
    private static final Path TEST_SOURCE_ROOT = Path.of("src/test/java/world/thearchive/wdl");
    private static final Path FIXTURE_PACKAGE = TEST_SOURCE_ROOT.resolve("testsupport");

    /**
     * Every test tree that can hold a fixture, not just this subproject's. A scan that silently covers one module reads
     * exactly like a scan that found nothing.
     */
    private static final List<Path> SOURCE_ROOTS = List.of(
            TEST_SOURCE_ROOT,
            Path.of("../fabric/src/test/java/world/thearchive/wdl"),
            Path.of("../fabric/src/gametest/java/world/thearchive/wdl"),
            Path.of("../neoforge/src/test/java/world/thearchive/wdl"));

    /**
     * {@code "id"} anchors both gated shapes (a block entity's type, an item entry's item).
     *
     * <p>Matched by regex over the whole file rather than by substring per line, because both narrower forms leak. Any
     * typed put counts, since a numeric key written at one width reads back at another, and the whitespace class covers
     * an argument list the formatter wrapped at the column limit.
     */
    private static final List<Pattern> DECIDING_KEY_WRITES = List.of(
            Pattern.compile("put\\w+\\(\\s*\"(id|Slot|Count)\""),
            Pattern.compile("put\\(\\s*\"(id|Slot|Count)\"\\s*,\\s*\\w*Tag\\."));

    private static final String BUILDERS = "BlockEntityFixtures, ItemFixtures or EntityFixtures";

    @Test
    void noTestOutsideTheFixturePackageWritesShapeDecidingKeys() {
        List<String> offenders = new ArrayList<>();
        for (Path file : testSources()) {
            String source = readString(file);
            for (Pattern pattern : DECIDING_KEY_WRITES) {
                Matcher matcher = pattern.matcher(source);
                while (matcher.find()) {
                    offenders.add(file + ":" + lineOf(source, matcher.start()) + "  writes \""
                            + matcher.group(1) + "\"\n      use " + BUILDERS);
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "Test source outside " + FIXTURE_PACKAGE + " writes NBT whose shape a vanilla writer decides.\n"
                        + "A hand-built tag skips the fixture-fidelity check entirely, so a key the producer\n"
                        + "always writes can go missing and the test will pass over the behavior it names.\n"
                        + "Build it in testsupport from the producer, and add a named builder there if none fits:\n"
                        + String.join("\n", offenders));
    }

    @Test
    void theGuardIsLookingAtSourceItCanActuallyRead() {
        assertTrue(Files.isDirectory(TEST_SOURCE_ROOT),
                TEST_SOURCE_ROOT.toAbsolutePath() + " does not exist, so every other root would be skipped "
                        + "silently and the guard would be checking nothing");
        List<Path> sources = testSources();
        assertTrue(sources.size() > 20,
                "the scan found only " + sources.size() + " test sources under " + TEST_SOURCE_ROOT.toAbsolutePath()
                        + "; the root moved and the guard is checking nothing");
    }

    private static List<Path> testSources() {
        List<Path> sources = new ArrayList<>();
        for (Path root : SOURCE_ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> entries = Files.walk(root)) {
                entries.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .filter(path -> !path.startsWith(FIXTURE_PACKAGE))
                        .forEach(sources::add);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot walk " + root.toAbsolutePath(), e);
            }
        }
        return sources;
    }

    private static int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file.toAbsolutePath(), e);
        }
    }
}
