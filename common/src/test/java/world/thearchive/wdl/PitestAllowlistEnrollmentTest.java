// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the PITest fidelity allowlist honest as the packages grow. The allowlist in {@code common/build.gradle.kts}
 * fails closed: a class it does not name gets no mutants, so a newly-added fidelity-critical class would silently carry
 * no mutation signal, and once a ratchet gate lands it would pass green over that unpinned code. This guard makes that
 * hole loud: every top-level {@code core/} pure-logic class (imports no {@code net.minecraft}) and every top-level
 * {@code adapter/} deterministic transform (imports {@code net.minecraft}) must be a deliberate choice, either enrolled
 * in the PITest {@code targetClasses} or listed in {@link #ACKNOWLEDGED_EXCLUDES} here. A new class that is neither
 * fails the build until someone classifies it.
 *
 * <p>The allowlist is read from {@code build.gradle.kts} rather than duplicated, so the enrollment registry and the
 * classes PIT actually mutates cannot drift: a class listed here but absent from {@code targetClasses} (believed
 * covered yet never mutated) is caught too.
 */
class PitestAllowlistEnrollmentTest {
    private static final Path SOURCE_ROOT = Paths.get("src/main/java/world/thearchive/wdl");
    private static final Path CORE_SOURCE = SOURCE_ROOT.resolve("core");
    private static final Path ADAPTER_SOURCE = SOURCE_ROOT.resolve("adapter");
    private static final Path BUILD_SCRIPT = Paths.get("build.gradle.kts");

    private static final String CORE_PACKAGE = "world.thearchive.wdl.core";
    private static final String ADAPTER_PACKAGE = "world.thearchive.wdl.adapter";

    // The FQN string literals inside the pitest targetClasses list; the targetTests glob ("...wdl.*") and
    // the class names in prose comments do not match this fully-qualified, capitalized-leaf shape.
    private static final Pattern targetClassFqnPattern = Pattern
            .compile("\"(world\\.thearchive\\.wdl\\.(?:core|adapter)\\.[A-Z][A-Za-z0-9_]*)\"");

    // Every allowlist entry at any package depth. The registry scan above reaches only top-level core/ and
    // adapter/, so hand-enrolled subpackage entries (core.report, adapter.impl) would otherwise rot silently:
    // a renamed or moved class leaves a dead glob that PIT matches to zero mutants without erroring, and the
    // 100 gate stays green over now-unmutated code.
    private static final Pattern anyDepthTargetClassFqnPattern = Pattern
            .compile("\"(world\\.thearchive\\.wdl(?:\\.[a-z][a-z0-9]*)*\\.[A-Z][A-Za-z0-9_]*)\"");

    // Classes that match the structural predicate but carry no cross-band fidelity contract, so they are
    // deliberately not mutated: SPI interfaces (no body to mutate), the IO/threading and packet/render/
    // tracker plumbing (effect-asserted, not value-asserted), and presentation/config/value types.
    private static final Set<String> ACKNOWLEDGED_EXCLUDES = ImmutableSet.of(
            // core/ presentation, config, and value types (no fidelity contract):
            "world.thearchive.wdl.core.AtomicFileWrite",
            "world.thearchive.wdl.core.BrandColors",
            "world.thearchive.wdl.core.CaptureCounts",
            "world.thearchive.wdl.core.CaptureState",
            "world.thearchive.wdl.core.CaptureToggleGuard",
            "world.thearchive.wdl.core.CapturedContainers",
            "world.thearchive.wdl.core.ChatCopy",
            "world.thearchive.wdl.core.ConfigOption",
            "world.thearchive.wdl.core.ConfigSchema",
            "world.thearchive.wdl.core.ConfigType",
            "world.thearchive.wdl.core.ConfigValues",
            "world.thearchive.wdl.core.CuratedGameRule",
            "world.thearchive.wdl.core.DownloadMode",
            "world.thearchive.wdl.core.DownloadTarget",
            "world.thearchive.wdl.core.ElapsedTime",
            "world.thearchive.wdl.core.GameRuleResolution",
            "world.thearchive.wdl.core.GameRuleSchema",
            "world.thearchive.wdl.core.HudAnchor",
            "world.thearchive.wdl.core.HudConfig",
            "world.thearchive.wdl.core.HudPeekMode",
            "world.thearchive.wdl.core.MarkerHue",
            "world.thearchive.wdl.core.OutlineClamp",
            "world.thearchive.wdl.core.OutlineClass",
            "world.thearchive.wdl.core.OutlineConfig",
            "world.thearchive.wdl.core.OverlayHighlights",
            "world.thearchive.wdl.core.RecaptureMode",
            "world.thearchive.wdl.core.RecoveredCoverage",
            "world.thearchive.wdl.core.RimFace",
            "world.thearchive.wdl.core.SaveFailureComposer",
            "world.thearchive.wdl.core.SaveFailureReason",
            "world.thearchive.wdl.core.SaveProgress",
            "world.thearchive.wdl.core.SaveStage",
            "world.thearchive.wdl.core.SettingsDraft",
            "world.thearchive.wdl.core.SettingsLayout",
            "world.thearchive.wdl.core.TimingWindow",
            "world.thearchive.wdl.core.ToastCopy",
            "world.thearchive.wdl.core.WorldType",
            // adapter/ SPI interfaces, IO/threading, packet/render/tracker plumbing, value types:
            "world.thearchive.wdl.adapter.AsyncSaveWriter",
            "world.thearchive.wdl.adapter.CapturedPlayer",
            "world.thearchive.wdl.adapter.ChunkCodec",
            "world.thearchive.wdl.adapter.ChunkSnapshotSource",
            "world.thearchive.wdl.adapter.ConnectionTee",
            "world.thearchive.wdl.adapter.ContainerCapture",
            "world.thearchive.wdl.adapter.ContainerSink",
            "world.thearchive.wdl.adapter.EntityBuffer",
            "world.thearchive.wdl.adapter.EntityPacketCapture",
            "world.thearchive.wdl.adapter.EntitySink",
            "world.thearchive.wdl.adapter.EquipmentEntry",
            "world.thearchive.wdl.adapter.InteractionCapture",
            "world.thearchive.wdl.adapter.LecternSink",
            "world.thearchive.wdl.adapter.LevelDataWriter",
            "world.thearchive.wdl.adapter.LiveCaptureSession",
            "world.thearchive.wdl.adapter.MapArchive",
            "world.thearchive.wdl.adapter.MapDataWriter",
            "world.thearchive.wdl.adapter.MapSink",
            "world.thearchive.wdl.adapter.MenuChangeTracker",
            "world.thearchive.wdl.adapter.MountMenuReader",
            "world.thearchive.wdl.adapter.OpenClickTracker",
            "world.thearchive.wdl.adapter.OutlineRenderContext",
            "world.thearchive.wdl.adapter.OutlineRim",
            "world.thearchive.wdl.adapter.OutlineTracker",
            "world.thearchive.wdl.adapter.PlayerSink",
            "world.thearchive.wdl.adapter.RegionChunkWriter",
            "world.thearchive.wdl.adapter.RenderSurface",
            "world.thearchive.wdl.adapter.RimRenderer",
            "world.thearchive.wdl.adapter.StashHolder",
            "world.thearchive.wdl.adapter.WdlRegionStorage",
            "world.thearchive.wdl.adapter.WorldPaths");

    @Test
    void everyFidelityCandidateIsEnrolledOrAcknowledgedExcluded() {
        Set<String> allowlist = allowlistFromBuildScript();
        assertTrue(
                !allowlist.isEmpty(),
                "No PITest targetClasses FQNs found in " + BUILD_SCRIPT.toAbsolutePath()
                        + ". Did the pitest block move or change shape?");

        Set<String> registry = new TreeSet<>(allowlist);
        registry.addAll(ACKNOWLEDGED_EXCLUDES);

        Set<String> unclassified = new TreeSet<>(fidelityCandidates());
        unclassified.removeAll(registry);

        assertTrue(
                unclassified.isEmpty(),
                "Fidelity-critical class(es) are neither enrolled nor acknowledged-excluded. Each core/ pure-logic\n"
                        + "and adapter/ deterministic-transform class must be a deliberate choice:\n"
                        + "  - enroll it in pitest targetClasses in common/build.gradle.kts (it carries a fidelity\n"
                        + "    contract and its tests must pin its behavior), or\n"
                        + "  - add it to ACKNOWLEDGED_EXCLUDES in this test (it does not).\n"
                        + "Unclassified:\n" + bullets(unclassified));
    }

    @Test
    void noAcknowledgedExcludeIsStale() {
        Set<String> stale = new TreeSet<>(ACKNOWLEDGED_EXCLUDES);
        stale.removeAll(fidelityCandidates());
        assertTrue(
                stale.isEmpty(),
                "ACKNOWLEDGED_EXCLUDES names class(es) that are no longer a core/adapter fidelity candidate\n"
                        + "(renamed, deleted, or moved). Remove them so the registry stays honest:\n" + bullets(stale));
    }

    @Test
    void noAllowlistEntryIsStale() {
        Set<String> stale = new TreeSet<>(allowlistFromBuildScript());
        stale.removeAll(fidelityCandidates());
        assertTrue(
                stale.isEmpty(),
                "The pitest targetClasses names class(es) PIT would silently never mutate: the FQN matches no\n"
                        + "core/adapter top-level candidate (renamed or misspelled). Fix common/build.gradle.kts:\n"
                        + bullets(stale));
    }

    @Test
    void everyAllowlistEntryResolvesToProductionSource() {
        Set<String> dead = new TreeSet<>();
        Matcher matcher = anyDepthTargetClassFqnPattern.matcher(readString(BUILD_SCRIPT));
        while (matcher.find()) {
            String fqn = matcher.group(1);
            if (!Files.isRegularFile(Paths.get("src/main/java", fqn.replace('.', '/') + ".java"))) {
                dead.add(fqn);
            }
        }
        assertTrue(
                dead.isEmpty(),
                "The pitest targetClasses names class(es) with no production source file. PIT matches a dead\n"
                        + "glob to zero mutants without erroring, so the gate would pass green over unmutated code.\n"
                        + "Rename or remove the entries:\n" + bullets(dead));
    }

    @Test
    void allowlistAndExcludesAreDisjoint() {
        Set<String> both = new TreeSet<>(allowlistFromBuildScript());
        both.retainAll(ACKNOWLEDGED_EXCLUDES);
        assertTrue(
                both.isEmpty(),
                "Class(es) are both enrolled and acknowledged-excluded. A class is mutated or it is not, never\n"
                        + "both:\n" + bullets(both));
    }

    /**
     * The structural predicate: every top-level {@code core/} class (which imports no {@code net.minecraft} by the
     * checkCoreImports invariant) and every top-level {@code adapter/} class that imports {@code net.minecraft} (a
     * transform operates on vanilla NBT / value types). Subpackages are out of scope by not being scanned, matching the
     * allowlist's top-level-only reach.
     */
    private static Set<String> fidelityCandidates() {
        Set<String> candidates = new TreeSet<>();
        for (Path file : topLevelJavaFiles(CORE_SOURCE)) {
            if (!importsMinecraft(file)) {
                candidates.add(CORE_PACKAGE + "." + className(file));
            }
        }
        for (Path file : topLevelJavaFiles(ADAPTER_SOURCE)) {
            if (importsMinecraft(file)) {
                candidates.add(ADAPTER_PACKAGE + "." + className(file));
            }
        }
        return candidates;
    }

    private static Set<String> allowlistFromBuildScript() {
        String script = readString(BUILD_SCRIPT);
        Matcher matcher = targetClassFqnPattern.matcher(script);
        Set<String> allowlist = new TreeSet<>();
        while (matcher.find()) {
            allowlist.add(matcher.group(1));
        }
        return allowlist;
    }

    // Same net.minecraft import scan as the checkCoreImports build fence, so both read the tree identically.
    private static boolean importsMinecraft(Path javaFile) {
        for (String line : readAllLines(javaFile)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import net.minecraft.") || trimmed.startsWith("import static net.minecraft.")) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> topLevelJavaFiles(Path packageDirectory) {
        try (Stream<Path> entries = Files.list(packageDirectory)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".java") && !name.equals("package-info.java");
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + packageDirectory.toAbsolutePath(), e);
        }
    }

    private static String className(Path javaFile) {
        String name = javaFile.getFileName().toString();
        return name.substring(0, name.length() - ".java".length());
    }

    private static List<String> readAllLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file.toAbsolutePath(), e);
        }
    }

    private static String readString(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file.toAbsolutePath(), e);
        }
    }

    private static String bullets(Set<String> names) {
        return names.stream().map(name -> "  - " + name).collect(Collectors.joining("\n"));
    }
}
