// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * The composed copy of one download chat line: a wdl.chat translation key whose en_us pattern is the line template,
 * plus the ordered arguments filling its slots. Each argument is a literal value or a keyed sub-pattern with one
 * insert, carries its {@link BrandColors} tint or empty to inherit the line's, and may carry a click affordance the
 * platform bridge renders with the raw target as hover text. MC-free so every decision is headless-testable against the
 * shipped lang file.
 */
public final class ChatCopy {
    /**
     * One argument of the line pattern: literal text when {@code translationKey} is null, else a keyed sub-pattern with
     * {@code text} as its single insert (empty when the sub-pattern has no slot).
     */
    public static final class Argument {
        private final @Nullable String translationKey;
        private final String text;
        private final OptionalInt color;
        private final @Nullable Click click;

        private Argument(@Nullable String translationKey, String text, OptionalInt color, @Nullable Click click) {
            this.translationKey = translationKey;
            this.text = text;
            this.color = color;
            this.click = click;
        }

        public @Nullable String translationKey() {
            return translationKey;
        }

        public String text() {
            return text;
        }

        /** The RGB tint from {@link BrandColors}, or empty to inherit the line's template tint. */
        public OptionalInt color() {
            return color;
        }

        /** The click affordance, or null for plain text. */
        public @Nullable Click click() {
            return click;
        }
    }

    /** The click affordance of an argument: what kind of target it opens and the raw target string. */
    public static final class Click {
        public enum Kind {
            OPEN_URL,
            OPEN_FILE
        }

        private final Kind kind;
        private final String target;

        private Click(Kind kind, String target) {
            this.kind = kind;
            this.target = target;
        }

        public Kind kind() {
            return kind;
        }

        public String target() {
            return target;
        }
    }

    private final String translationKey;
    private final OptionalInt templateColor;
    private final List<Argument> arguments;

    private ChatCopy(String translationKey, OptionalInt templateColor, List<Argument> arguments) {
        this.translationKey = translationKey;
        this.templateColor = templateColor;
        this.arguments = Collections.unmodifiableList(arguments);
    }

    public String translationKey() {
        return translationKey;
    }

    /** The tint of the line's template text, or empty for the vanilla default chat color. */
    public OptionalInt templateColor() {
        return templateColor;
    }

    public List<Argument> arguments() {
        return arguments;
    }

    /** The download start line naming the on-disk folder the download lands in. */
    public static ChatCopy downloading(String folderName) {
        List<Argument> arguments = new ArrayList<>();
        arguments.add(amber(folderName));
        return ivoryLine("wdl.chat.downloading", arguments);
    }

    /** The resume start line, the counterpart of {@link #downloading} when continuing an existing folder. */
    public static ChatCopy resuming(String folderName) {
        List<Argument> arguments = new ArrayList<>();
        arguments.add(amber(folderName));
        return ivoryLine("wdl.chat.resuming", arguments);
    }

    /**
     * The start refusal when no usable name was given (bare {@code /wdl start}, a name that sanitizes to nothing, or an
     * implicit start with no server name to fall back on), pointing at {@code /wdl start <name>}.
     */
    public static ChatCopy startNeedsName() {
        return ivoryLine("wdl.chat.start_needs_name", new ArrayList<>());
    }

    /** The stop acknowledgment shown while the asynchronous save drains. */
    public static ChatCopy saving() {
        return ivoryLine("wdl.chat.saving", new ArrayList<>());
    }

    /** The completion summary shown in chat when a download finishes (distinct chunks, entities, containers). */
    public static ChatCopy downloaded(String folderName, int chunks, int entities, int containers,
            long elapsedMillis) {
        List<Argument> arguments = new ArrayList<>();
        arguments.add(amber(folderName));
        arguments.add(amberCount("wdl.chat.chunks", chunks));
        arguments.add(amberCount("wdl.chat.entities", entities));
        arguments.add(amberCount("wdl.chat.containers", containers));
        arguments.add(amber(CaptureStatus.completionElapsed(elapsedMillis)));
        return ivoryLine("wdl.chat.downloaded", arguments);
    }

    /** The completion destination line whose link opens the download's save folder. */
    public static ChatCopy savedTo(String folderName, String folderPath) {
        List<Argument> arguments = new ArrayList<>();
        arguments.add(amber(folderName));
        arguments.add(new Argument("wdl.chat.open_save_folder", "", OptionalInt.of(BrandColors.TEAL),
                new Click(Click.Kind.OPEN_FILE, folderPath)));
        return ivoryLine("wdl.chat.saved_to", arguments);
    }

    /** The {@code /wdl status} reply: live counts while recording, else the phase or the idle hint. */
    public static ChatCopy status(CaptureState state, CaptureCounts counts) {
        if (state == CaptureState.RECORDING) {
            List<Argument> arguments = new ArrayList<>();
            arguments.add(amberCount("wdl.chat.chunks", counts.chunks()));
            arguments.add(amberCount("wdl.chat.entities", counts.entities()));
            arguments.add(amberCount("wdl.chat.containers", counts.containers()));
            return ivoryLine("wdl.chat.status.recording", arguments);
        }
        if (state == CaptureState.SAVING) {
            return ivoryLine("wdl.chat.status.saving", new ArrayList<>());
        }
        return ivoryLine("wdl.chat.status.idle", new ArrayList<>());
    }

    static ChatCopy alreadyDownloading() {
        return ivoryLine("wdl.refuse.already_downloading.body", new ArrayList<>());
    }

    /** The refusal shown when a start is attempted while the previous download is still saving. */
    static ChatCopy savingInProgress() {
        return ivoryLine("wdl.refuse.saving_in_progress.body", new ArrayList<>());
    }

    public static ChatCopy notDownloading() {
        return ivoryLine("wdl.chat.not_downloading", new ArrayList<>());
    }

    public static ChatCopy joinMultiplayer() {
        return ivoryLine("wdl.refuse.join_multiplayer.body", new ArrayList<>());
    }

    public static ChatCopy nothingCaptured() {
        return ivoryLine("wdl.chat.nothing_captured", new ArrayList<>());
    }

    /** The passive caution when a download starts with a core capture kind off; wholly amber, not rust. */
    public static ChatCopy capturePartiallyDisabled() {
        return new ChatCopy("wdl.chat.capture_partially_disabled", OptionalInt.of(BrandColors.AMBER),
                new ArrayList<>());
    }

    public static ChatCopy worldNameFallback() {
        return ivoryLine("wdl.chat.world_name_fallback", new ArrayList<>());
    }

    public static ChatCopy configFile(String path) {
        List<Argument> arguments = new ArrayList<>();
        arguments.add(amber(path));
        return ivoryLine("wdl.chat.config_file", arguments);
    }

    /** A machine-data readout line in template ivory; the data is displayed verbatim, never keyed. */
    public static ChatCopy data(String text) {
        List<Argument> arguments = new ArrayList<>();
        arguments.add(new Argument(null, text, OptionalInt.empty(), null));
        return ivoryLine("wdl.chat.data", arguments);
    }

    private static ChatCopy ivoryLine(String translationKey, List<Argument> arguments) {
        return new ChatCopy(translationKey, OptionalInt.of(BrandColors.IVORY), arguments);
    }

    private static Argument amber(String text) {
        return new Argument(null, text, OptionalInt.of(BrandColors.AMBER), null);
    }

    private static Argument amberCount(String keyBase, int count) {
        return new Argument(keyBase, Integer.toString(count), OptionalInt.of(BrandColors.AMBER), null);
    }
}
