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
 * Whether a wdl-managed save folder has ever been opened in singleplayer, a sticky property WDL uses to refuse (or
 * confirm) resuming into it. The integrated server writes a set of server-only artifacts on autosave and exit: the
 * player-data directory, and, from 1.14 onward, the point-of-interest directory for each loaded dimension. WDL never
 * writes any of them (the downloader goes into level.dat's Player tag, progress into advancements/ and stats/, and it
 * opens only the region and entities storages, never point-of-interest), so a non-empty member of the set means
 * singleplayer touched the folder.
 *
 * <p>Directory names, and for point-of-interest the dimension root itself, are band-dependent, so the check tests the
 * union of the known vanilla layouts, which also covers a folder opened by a different MC version than the one running
 * this check. The set tracks vanilla {@code LevelResource.PLAYER_DATA_DIR} and the per-dimension point-of-interest
 * storage across bands, and gains an entry (plus a test) when a future band moves one again. MC-free (plain java.nio)
 * so the check runs on every band.
 *
 * <p>A present member that cannot be listed is {@link TaintState#UNKNOWN} rather than clean, and {@link #decide} maps
 * that to {@link Decision#CONFIRM}: the clobber-safety gate fails safe on a folder it cannot verify, never silently
 * allows the resume.
 */
public final class SinglePlayerTaint {
    /**
     * Relative player-data directories across bands: {@code playerdata} on 1.21.x and earlier, {@code players/data} on
     * 26.x.
     */
    private static final List<String> PLAYER_DATA_DIRECTORIES = Collections
            .unmodifiableList(Arrays.asList("playerdata", "players/data"));

    /**
     * Dimension-root-relative point-of-interest directories: the save root plus the vanilla nether and end roots.
     * Datapack dimensions (dimensions/&lt;namespace&gt;/&lt;id&gt;/poi) are enumerated live in {@link #classify} and
     * matched positionally in {@link #entryPathIsServerArtifact}; point-of-interest data is resolved per dimension by
     * the server's chunk map and is server-written only, and WDL opens only the region and entities storages, never
     * point-of-interest, so the check is safe to add. On pre-1.14 bands the directory never exists, so the check is
     * inert there with no band branch.
     */
    private static final List<String> FIXED_POI_DIRECTORIES = Collections
            .unmodifiableList(Arrays.asList("poi", "DIM-1/poi", "DIM1/poi"));

    /** The gate outcome for a resume/recover into a possibly-tainted folder. */
    public enum Decision {
        ALLOW,
        CONFIRM,
        REFUSE
    }

    /**
     * A folder's singleplayer-history verdict: {@code CLEAN} (no server-only artifact), {@code TAINTED} (a non-empty
     * server-only-artifact directory, player data or point-of-interest), or {@code UNKNOWN} when a present directory
     * could not be listed.
     */
    public enum TaintState {
        CLEAN,
        TAINTED,
        UNKNOWN
    }

    /** The observed state of a candidate player-data directory; the seam that makes the unreadable branch testable. */
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

    /**
     * Whether {@code saveFolder} is definitely tainted (a non-empty server-only-artifact member of any known layout).
     */
    public static boolean isTainted(Path saveFolder) {
        return classify(saveFolder) == TaintState.TAINTED;
    }

    /**
     * Classify {@code saveFolder}: {@code TAINTED} on any non-empty server-only-artifact member, {@code UNKNOWN} when a
     * present member could not be listed (so the gate can fail safe), else {@code CLEAN}.
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

    /**
     * The bounded two-level enumeration under dimensions/: dimensions/&lt;namespace&gt;/&lt;id&gt;/poi for each id
     * directory, or {@code null} when the tree could not be listed.
     */
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

    /**
     * The gate outcome: a clean folder is allowed; a tainted one is refused when blocking, else confirmed; an unknown
     * (unreadable) folder is never allowed, always confirmed, so a folder that cannot be verified clean still prompts
     * rather than clobbering silently.
     */
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

    /**
     * Whether a root-stripped, slash-separated zip entry path names content inside a server-only-artifact directory at
     * its pinned position, compared case-insensitively. The zip-side twin of {@link #classify}: shares the member set
     * and the positional rules, so folder and zip agree on what clean means.
     *
     * <p>The live probe resolves fixed names through the filesystem, so a POSIX hand-re-cased {@code Playerdata/}
     * escapes it, matching the shipped probe; this matcher is lexically case-insensitive instead, since the zip-scan
     * specification's case rules apply only here.
     */
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
        // dimensions/<namespace>/<id>/poi/... : four leading segments with poi fourth.
        String[] segments = lower.split("/", 5);
        return segments.length >= 5 && segments[0].equals("dimensions") && segments[3].equals("poi");
    }
}
