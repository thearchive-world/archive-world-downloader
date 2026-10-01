// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.browse;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A present member that cannot be listed is {@link TaintState#UNKNOWN} rather than clean, and {@link #decide} maps that
 * to {@link Decision#CONFIRM}: the clobber-safety gate fails safe on a folder it cannot verify, never silently allows
 * the resume.
 */
public final class SinglePlayerTaint {
    /**
     * The relative player-data directory, {@code playerdata} on 1.21.x and earlier. The 26.x layout
     * {@code players/data} is not a member: {@code PrimaryLevelData.createTag} writes no player compound there, so WDL
     * writes that file itself. Neither member is redundant, since point-of-interest does not exist before 1.14 and
     * player data is unusable from 26.x, so dropping either blinds a band.
     */
    private static final List<String> PLAYER_DATA_DIRECTORIES = Collections
            .unmodifiableList(Arrays.asList("playerdata"));

    private static final List<String> FIXED_POI_DIRECTORIES = Collections
            .unmodifiableList(Arrays.asList("poi", "DIM-1/poi", "DIM1/poi"));

    public enum Decision {
        ALLOW,
        CONFIRM,
        REFUSE
    }

    public enum TaintState {
        CLEAN,
        TAINTED,
        UNKNOWN
    }

    interface DirectoryProbe {
        Presence presence(Path directory);
    }

    enum Presence {
        HAS_ENTRY,
        NO_ENTRY,
        UNREADABLE
    }

    private static final DirectoryProbe filesystemProbe = SinglePlayerTaint::probeFilesystem;

    private SinglePlayerTaint() {}

    public static boolean isTainted(Path saveFolder) {
        return classify(saveFolder) == TaintState.TAINTED;
    }

    /**
     * Classify {@code saveFolder}: {@code TAINTED} on any non-empty server-only-artifact member, {@code UNKNOWN} when a
     * present member could not be listed, else {@code CLEAN}.
     */
    public static TaintState classify(Path saveFolder) {
        return classify(saveFolder, filesystemProbe);
    }

    static TaintState classify(Path saveFolder, DirectoryProbe probe) {
        boolean unreadable = false;
        List<String> candidates = new ArrayList<String>(PLAYER_DATA_DIRECTORIES);
        candidates.addAll(FIXED_POI_DIRECTORIES);
        List<String> datapackPoi = datapackPoiDirectories(saveFolder);
        if (datapackPoi == null) {
            unreadable = true;
        } else {
            candidates.addAll(datapackPoi);
        }
        for (String relative : candidates) {
            Presence presence = probe.presence(saveFolder.resolve(relative));
            if (presence == Presence.HAS_ENTRY) {
                return TaintState.TAINTED;
            }
            if (presence == Presence.UNREADABLE) {
                unreadable = true;
            }
        }
        return unreadable ? TaintState.UNKNOWN : TaintState.CLEAN;
    }

    private static @Nullable List<String> datapackPoiDirectories(Path saveFolder) {
        Path dimensions = saveFolder.resolve("dimensions");
        if (!Files.isDirectory(dimensions)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        try (DirectoryStream<Path> namespaces = Files.newDirectoryStream(dimensions)) {
            for (Path namespace : namespaces) {
                if (!Files.isDirectory(namespace)) {
                    continue;
                }
                try (DirectoryStream<Path> ids = Files.newDirectoryStream(namespace)) {
                    for (Path id : ids) {
                        result.add("dimensions/" + namespace.getFileName() + "/" + id.getFileName() + "/poi");
                    }
                }
            }
        } catch (IOException | DirectoryIteratorException e) {
            // An unlistable dimensions tree cannot be verified clean, so classify must report UNKNOWN
            // rather than silently treating the unseen entries as absent.
            return null;
        }
        return result;
    }

    public static Decision decide(TaintState state, boolean blockTaintedResume) {
        if (state == TaintState.CLEAN) {
            return Decision.ALLOW;
        }
        if (state == TaintState.UNKNOWN) {
            return Decision.CONFIRM;
        }
        return blockTaintedResume ? Decision.REFUSE : Decision.CONFIRM;
    }

    private static Presence probeFilesystem(Path directory) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            return entries.iterator().hasNext() ? Presence.HAS_ENTRY : Presence.NO_ENTRY;
        } catch (NoSuchFileException | NotDirectoryException e) {
            return Presence.NO_ENTRY;
        } catch (IOException | DirectoryIteratorException e) {
            return Presence.UNREADABLE;
        }
    }

    public static boolean entryPathIsServerArtifact(String rootRelativePath) {
        String lower = rootRelativePath.toLowerCase(Locale.ROOT);
        for (String member : PLAYER_DATA_DIRECTORIES) {
            if (lower.startsWith(member + "/")) {
                return true;
            }
        }
        for (String member : FIXED_POI_DIRECTORIES) {
            if (lower.startsWith(member.toLowerCase(Locale.ROOT) + "/")) {
                return true;
            }
        }
        String[] segments = lower.split("/", 5);
        return segments.length >= 5 && segments[0].equals("dimensions") && segments[3].equals("poi");
    }
}
