// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The gate between the keys the code names and the keys en_us carries, in both directions. {@link LangFidelityTest}
 * holds the 32 locales to en_us and never looks at the code, so without this a key only production names could be
 * absent from every catalog and stay green: MC renders a missing key as the raw key string, and a tooltip behind
 * {@code I18n.exists} renders nothing at all.
 *
 * <p>MC-free: source and resource reads off the module tree, the {@link UiLiteralEnrollmentTest} pattern.
 */
class LangKeyCoverageTest {
    private static final Path CATALOG = Paths.get("src/main/resources/assets/wdl/lang/en_us.json");

    private static final List<Path> PRODUCTION_ROOTS = ImmutableList.of(
            Paths.get("src/main/java/world/thearchive/wdl"),
            Paths.get("../fabric/src/main/java/world/thearchive/wdl"),
            Paths.get("../neoforge/src/main/java/world/thearchive/wdl"));

    // Anchored on both quotes so a log line that merely mentions wdl.properties is not read as a key, and narrowed
    // to the three shapes the project uses so vanilla's own key.* names cannot be swept in.
    private static final Pattern keyLiteral = Pattern
            .compile("\"((?:wdl|key\\.wdl|key\\.category\\.wdl)\\.[A-Za-z0-9_.]+)\"");

    private static final List<String> RESUME_CONFIRM_SUFFIXES = ImmutableList.of(".title", ".message",
            ".message_no_backup");
    private static final List<String> SWEEP_NOTICE_SUFFIXES = ImmutableList.of(".title", ".body");
    private static final List<String> CAPTURE_CONFIRM_SUFFIXES = ImmutableList.of(".title", ".confirm");

    private static final Map<String, List<String>> COMPLETED_STEMS = ImmutableMap.<String, List<String>>builder()
            .put("wdl.screen.downloads.confirm_restore", RESUME_CONFIRM_SUFFIXES)
            .put("wdl.screen.downloads.confirm_restore_blocked", RESUME_CONFIRM_SUFFIXES)
            .put("wdl.screen.downloads.confirm_tainted", RESUME_CONFIRM_SUFFIXES)
            .put("wdl.screen.downloads.confirm_map_id_mismatch", RESUME_CONFIRM_SUFFIXES)
            .put("wdl.screen.downloads.merge", RESUME_CONFIRM_SUFFIXES)
            .put("wdl.toast.sweep_moved_back", SWEEP_NOTICE_SUFFIXES)
            .put("wdl.toast.sweep_relocated", SWEEP_NOTICE_SUFFIXES)
            .put("wdl.toast.sweep_missing_deferred", SWEEP_NOTICE_SUFFIXES)
            .put("wdl.settings.confirm.capture", CAPTURE_CONFIRM_SUFFIXES)
            .build();

    // Safe only because SettingsLangTest asserts both directions over exactly these prefixes.
    private static final Set<String> DELEGATED_STEMS = ImmutableSet.of(
            "wdl.settings.confirm.",
            "wdl.settings.gamerule.",
            "wdl.settings.option.",
            "wdl.settings.tab.",
            "wdl.settings.value.");

    // The controls heading MC derives from the wdl:downloader category identifier, and the two entries
    // ModMenu reads off the mod id. key.wdl.peek_hud is constructed in the forge/ island (WdlForge), outside
    // this test's PRODUCTION_ROOTS.
    private static final Set<String> EXTERNAL_CONSUMERS = ImmutableSet.of(
            "key.categories.wdl.downloader",
            "key.wdl.peek_hud",
            "modmenu.descriptionTranslation.wdl",
            "modmenu.summaryTranslation.wdl");

    private static final Set<String> NOT_TRANSLATION_KEYS = ImmutableSet.of("wdl.properties");

    // Narration and title strings the higher bands set through Component parameters this band's pre-1.14 widget and
    // screen APIs do not carry: a Button hover-tooltip (the 1.15.2 Button widget has no tooltip parameter), a Screen
    // title (the 1.13.2 GuiScreen predates the 1.14 title Component, so super(title) is dropped), and a text field's
    // narration message (the 1.13.2 text field widget takes no message). See WdlDownloadsScreen, WdlSettingsScreen and
    // AbstractPlatformBridge. Their en_us strings stay so the catalog matches the higher bands, but no consumer reaches
    // them on this band.
    private static final Set<String> BAND_DROPPED_KEYS = ImmutableSet.of(
            // No narrator exists below 1.12: neither NarratorChatListener nor ChatType is in this band's Minecraft,
            // and the text-to-speech library they drive is not in its library set, so the downloads list is not
            // narrated here and the key it would have spoken reaches nothing.
            "wdl.screen.downloads.narration",
            "wdl.pause.settings.tooltip",
            "wdl.screen.downloads.download.tooltip",
            "wdl.screen.downloads.name",
            "wdl.screen.downloads.title",
            "wdl.settings.defaults.tooltip",
            "wdl.settings.title");

    @Test
    void everyKeyTheCodeNamesIsCarriedByEnUs() {
        Map<String, String> catalog = loadCatalog();
        Set<String> literals = productionKeyLiterals();

        // Resolving the ambiguity either way silently drops one of the two checks, so they are kept disjoint.
        Set<String> ambiguous = new TreeSet<>(COMPLETED_STEMS.keySet());
        ambiguous.addAll(DELEGATED_STEMS);
        ambiguous.addAll(NOT_TRANSLATION_KEYS);
        ambiguous.retainAll(catalog.keySet());
        assertEquals(ImmutableSet.of(), ambiguous,
                "An enrolled stem is also a key en_us carries, so it is being used two ways at once.\n"
                        + "Rename one of them: a stem names a family, a key names a string.");

        Set<String> missing = new TreeSet<>();
        for (String literal : literals) {
            List<String> suffixes = COMPLETED_STEMS.get(literal);
            if (suffixes != null) {
                for (String suffix : suffixes) {
                    if (!catalog.containsKey(literal + suffix)) {
                        missing.add(literal + suffix);
                    }
                }
                continue;
            }
            if (!catalog.containsKey(literal)
                    && !DELEGATED_STEMS.contains(literal)
                    && !NOT_TRANSLATION_KEYS.contains(literal)) {
                missing.add(literal);
            }
        }
        assertEquals(ImmutableSet.of(), missing,
                "Production names a translation key en_us does not carry. Minecraft renders a\n"
                        + "missing key as the raw key string, and a tooltip behind I18n.exists renders\n"
                        + "nothing at all. Add the English string to en_us and mirror it across every\n"
                        + "locale, or, if the literal is a stem, enroll it: COMPLETED_STEMS with every\n"
                        + "suffix its call site appends, or DELEGATED_STEMS if SettingsLangTest derives\n"
                        + "the keys beneath it.");

        Set<String> stale = new TreeSet<>(COMPLETED_STEMS.keySet());
        stale.addAll(DELEGATED_STEMS);
        stale.addAll(NOT_TRANSLATION_KEYS);
        stale.removeAll(literals);
        assertEquals(ImmutableSet.of(), stale,
                "An enrolled literal is no longer named in the production tree. Drop it from\n"
                        + "COMPLETED_STEMS, DELEGATED_STEMS or NOT_TRANSLATION_KEYS; a stale entry is an\n"
                        + "exemption nothing is checking.");
    }

    @Test
    void everyEnUsKeyIsReached() {
        Map<String, String> catalog = loadCatalog();
        Set<String> reached = new TreeSet<>(productionKeyLiterals());
        for (Map.Entry<String, List<String>> stem : COMPLETED_STEMS.entrySet()) {
            for (String suffix : stem.getValue()) {
                reached.add(stem.getKey() + suffix);
            }
        }
        assertTrue(catalog.keySet().containsAll(EXTERNAL_CONSUMERS),
                "EXTERNAL_CONSUMERS names a key en_us no longer carries");
        reached.addAll(EXTERNAL_CONSUMERS);
        assertTrue(catalog.keySet().containsAll(BAND_DROPPED_KEYS),
                "BAND_DROPPED_KEYS names a key en_us no longer carries");
        reached.addAll(BAND_DROPPED_KEYS);

        Set<String> unreached = new TreeSet<>();
        for (String key : catalog.keySet()) {
            if (!reached.contains(key) && DELEGATED_STEMS.stream().noneMatch(key::startsWith)) {
                unreached.add(key);
            }
        }
        assertEquals(ImmutableSet.of(), unreached,
                "en_us carries a key nothing reaches. Either a consumer names it in a shape this\n"
                        + "test cannot see, and the stem it completes belongs in COMPLETED_STEMS or the\n"
                        + "key in EXTERNAL_CONSUMERS if something outside this tree resolves it, or the\n"
                        + "key is dead and its 32 translations are being carried for nothing.");
    }

    private static Set<String> productionKeyLiterals() {
        Set<String> literals = new TreeSet<>();
        for (Path root : PRODUCTION_ROOTS) {
            if (root.startsWith("..")) {
                // fabric/neoforge are stripped on this Forge-only band (0 tracked files); a present loader root
                // (the inert neoforge/ dir the middle bands still carry) is still required and scanned.
                if (!Files.isDirectory(root)) {
                    continue;
                }
            } else {
                assertTrue(Files.isDirectory(root), "production source root not found: " + root.toAbsolutePath());
            }
            for (Path file : javaFiles(root)) {
                Matcher matcher = keyLiteral.matcher(readString(file));
                while (matcher.find()) {
                    literals.add(matcher.group(1));
                }
            }
        }
        return literals;
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> entries = Files.walk(root)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk " + root.toAbsolutePath(), e);
        }
    }

    private static Map<String, String> loadCatalog() {
        return new Gson().fromJson(readString(CATALOG), new TypeToken<LinkedHashMap<String, String>>() {}.getType());
    }

    private static String readString(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path.toAbsolutePath(), e);
        }
    }
}
