// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.chat.NarratorChatListener;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.class_1802;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.realms.class_356;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.adapter.RenderSurface;
import world.thearchive.wdl.adapter.impl.RenderSurfaceImpl;
import world.thearchive.wdl.core.BrandColors;
import world.thearchive.wdl.core.CaptureState;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.MapManifest;
import world.thearchive.wdl.core.ToastCopy;
import world.thearchive.wdl.core.browse.DownloadEntry;
import world.thearchive.wdl.core.browse.DownloadFolders;
import world.thearchive.wdl.core.browse.DownloadHealth;
import world.thearchive.wdl.core.browse.SinglePlayerTaint;
import world.thearchive.wdl.core.browse.TargetResolver;
import world.thearchive.wdl.core.export.FinalizeOutputs;
import world.thearchive.wdl.core.export.RestoreOperation;
import world.thearchive.wdl.core.export.RestoreSource;
import world.thearchive.wdl.core.export.SizeFormatter;
import world.thearchive.wdl.core.report.DownloadCounts;
import world.thearchive.wdl.update.UpdateAvailable;
import world.thearchive.wdl.update.UpdateCheck;

/**
 * The download screen: start a new download with a typed name, or browse and resume/recover the wdl-managed downloads
 * already on disk. The vanilla world-select screen repurposed, built on vanilla widgets (no GUI library): an
 * {@link EditBox} name field, a primary Download/Resume button, a Done button, and a selection list of rows fed the
 * MC-free {@code core/browse} model. Loader-agnostic view code; the entry points (keybind, command, pause-menu button)
 * live per loader and route through {@link Wdl}, which decides per entry point whether the open runs inline or is
 * deferred to the next client tick.
 */
public final class WdlDownloadsScreen extends Screen {
    private static final int NAME_MAX_LENGTH = 48;
    private static final int ICON_SIZE = 16;
    private static final int ICON_ADVANCE = 20;
    private static final int ITEM_HEIGHT = 26;
    private static final int NAME_WIDTH = 250;
    private static final int FIELD_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 90;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 8;
    private static final int TOP_Y = 4;
    private static final int LIST_BOTTOM_MARGIN = 12;
    private static final int HEADER_ROW_HEIGHT = 14; // the header / Open-Saves label row; text is centered within it
    private static final int TOOLTIP_WRAP_WIDTH = 200; // wrap width for a multi-line row tooltip

    // Rows draw only icon + text; the vanilla selection list paints the selection treatment.
    private static final int HEADER_ARGB = BrandColors.opaque(BrandColors.IVORY);
    private static final int NAME_ARGB = BrandColors.opaque(BrandColors.AMBER);
    private static final int GRAY_ARGB = BrandColors.opaque(BrandColors.GRAY); // date, summary, size
    private static final int LINK_REST_ARGB = BrandColors.opaque(BrandColors.GRAY);
    private static final int LINK_HOVER_ARGB = BrandColors.opaque(BrandColors.AMBER_HOVER);
    private static final int RECOVER_ARGB = BrandColors.opaque(BrandColors.AMBER);
    private static final int TAINTED_ARGB = BrandColors.opaque(BrandColors.RUST);
    private static final int PARTIAL_ARGB = BrandColors.opaque(BrandColors.AMBER); // a partial download's chip
    private static final int BANNER_FILL_ARGB = 0x99000000 | BrandColors.PANEL;
    private static final int BANNER_OUTLINE_ARGB = BrandColors.opaque(BrandColors.AMBER);
    private static final int BANNER_GLYPH_ARGB = BrandColors.opaque(BrandColors.AMBER);
    private static final int BANNER_TEXT_ARGB = BrandColors.opaque(BrandColors.IVORY);
    private static final int BANNER_LINK_ARGB = BrandColors.opaque(BrandColors.TEAL);
    private static final int BANNER_LINK_HOVER_ARGB = BrandColors.opaque(BrandColors.TEAL_HOVER);

    private static final String ARROW = "⬈";
    private static final String WARNING_GLYPH = "⚠ ";
    private static final String DISMISS_GLYPH = "✕";
    private static final String RESTORE_GLYPH = "⟲";
    private static final int BANNER_GAP = 8;
    private static final int BANNER_HEIGHT = 22;
    private static final String DOT = "·";
    private static final String SUMMARY_ABSENT = "–"; // an en dash marking a not-applicable cell, not a minus
    private static final String TRIANGLE_EXPANDED = "▼ ";
    private static final String TRIANGLE_COLLAPSED = "▶ ";

    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private final @Nullable Screen parent;
    private final Path savesDirectory;
    private final @Nullable Path loadedWorld;
    private final Supplier<List<DownloadEntry>> entriesSupplier;
    private List<DownloadEntry> entries;
    private final String defaultName;
    private final boolean appendDateSuffix;
    private final boolean confirmResume;
    private final boolean blockTaintedResume;
    private final boolean zipOnResume;
    private final boolean remapMapIds;
    private final boolean capturePartiallyDisabled;
    private final String modVersion;
    private final String mcVersion;
    private final Consumer<DownloadTarget> onStart;
    private final Supplier<CaptureState> captureState;
    private final Runnable onStop;
    private final Consumer<ToastCopy> onRefusal;
    private final BooleanSupplier remoteWorld;
    private final @Nullable String activeDownloadName;

    // The existing-worlds list state, remembered across re-opens for the JVM session: it starts collapsed and a
    // manual toggle persists, except /wdl downloads forces it expanded (and that choice then persists too).
    private static boolean listCollapsed = true;

    private @Nullable EditBox nameField;
    // Typed text only: a state-flip rebuild drops the field and the selected row together, so seeding this
    // from a row prefill would bring that row's name back under the Download action.
    private String retainedName = "";
    private @Nullable class_356 primaryButton;
    private @Nullable DownloadList list;
    private @Nullable DownloadEntry selectedEntry;
    private boolean suppressNameResponder;

    private @Nullable Runnable pendingTooltip;

    // The capture state init() last built its widgets for; tick() rebuilds when the live state diverges, so a
    // flip between idle and capturing swaps the whole widget set rather than leaving a stale control on screen.
    private CaptureState builtForState = CaptureState.IDLE;

    // The on-disk size: a daemon walks each row's folder the first time the row is drawn (so only visible rows
    // are walked, never the whole list at once) and the result fills in its size on the render thread. The dedupe
    // set and the walked overlay are screen-scoped, so a resize-driven init() rebuild re-binds the fresh rows to
    // sizes already walked instead of re-walking. removed() closes the scanner; a re-open recreates it.
    private OnDiskSizeScanner sizeScanner = new OnDiskSizeScanner();
    private final Map<Path, Long> walkedSizes = new HashMap<>();
    private final Set<Path> scheduledWalks = new HashSet<>();

    // The restore-source availability cache feeding the tainted rows' restore chip, filled by the same
    // scanner on its availability kind and invalidated wherever the entries list is re-pulled.
    private final Map<Path, Path> availableSources = new HashMap<>();
    private final Set<Path> scheduledProbes = new HashSet<>();

    // The wall clock of the last sweep-work probe (never a gameplay-time counter, which pauses with the
    // game); tick() re-probes at the sweep's own TTL cadence while the screen stays open.
    private long lastSweepCheckMillis;

    public WdlDownloadsScreen(@Nullable Screen parent, Path savesDirectory, @Nullable Path loadedWorld,
            Supplier<List<DownloadEntry>> entriesSupplier, boolean expandExistingList, String defaultName,
            boolean appendDateSuffix,
            boolean confirmResume, boolean blockTaintedResume, boolean zipOnResume, boolean remapMapIds,
            boolean capturePartiallyDisabled, String modVersion, String mcVersion,
            Consumer<DownloadTarget> onStart, Supplier<CaptureState> captureState, Runnable onStop,
            Consumer<ToastCopy> onRefusal, BooleanSupplier remoteWorld, @Nullable String activeDownloadName) {
        this.parent = parent;
        this.savesDirectory = savesDirectory;
        this.loadedWorld = loadedWorld;
        this.entriesSupplier = entriesSupplier;
        this.entries = entriesSupplier.get();
        this.defaultName = defaultName;
        this.appendDateSuffix = appendDateSuffix;
        this.confirmResume = confirmResume;
        this.blockTaintedResume = blockTaintedResume;
        this.zipOnResume = zipOnResume;
        this.remapMapIds = remapMapIds;
        this.capturePartiallyDisabled = capturePartiallyDisabled;
        this.modVersion = modVersion;
        this.mcVersion = mcVersion;
        this.onStart = onStart;
        this.captureState = captureState;
        this.onStop = onStop;
        this.onRefusal = onRefusal;
        this.remoteWorld = remoteWorld;
        this.activeDownloadName = activeDownloadName;
        if (expandExistingList && !entries.isEmpty()) {
            listCollapsed = false; // /wdl downloads forces the list open; the choice then persists for the session
        }
        // The launch sweep rides the screen open: pending roll-back work under the temporary root is dispatched
        // from idle here, and the TTL re-check in tick() repeats the probe while the screen stays open.
        this.lastSweepCheckMillis = Util.getMillis();
        if (captureState.get() == CaptureState.IDLE
                && RestoreOperation.RestoreSweep.hasWork(savesDirectory)) {
            Wdl.launchSweep(savesDirectory);
        }
    }

    @Override
    protected void init() {
        // init() re-runs on resize and on a capture-state flip; release the prior rows' icon textures before the
        // widget lists are cleared, then build the widget set for the live capture state.
        if (this.list != null) {
            this.list.closeIcons();
        }
        CaptureState state = this.captureState.get();
        this.builtForState = state;
        if (state == CaptureState.IDLE) {
            initIdle();
        } else if (state == CaptureState.RESTORING) {
            initRestoring();
        } else {
            initCapturing(state);
        }
    }

    /** Idle: an editable name field, a Download/Resume button, and the browsable downloads list. */
    private void initIdle() {
        String name = this.selectedEntry != null ? this.selectedEntry.folderName() : this.retainedName;

        int fieldX = (this.field_1230 - NAME_WIDTH) / 2;
        int fieldY = TOP_Y + 4 + Math.round(0.05f * Math.max(0, this.field_1231 - TOP_Y - FIELD_HEIGHT - 8));
        NameField field = new NameField(this.field_1234, fieldX, fieldY, NAME_WIDTH, FIELD_HEIGHT);
        field.setMaxLength(NAME_MAX_LENGTH);
        field.setValue(name);
        field.method_18387((editId, text) -> onNameTyped(text));
        this.field_20307.add(field);
        this.nameField = field;

        int buttonRowY = fieldY + FIELD_HEIGHT + 6;
        Component primaryLabel = this.selectedEntry != null ? resumeLabel() : downloadLabel();
        boolean primaryActive = this.selectedEntry != null || TargetResolver.hasUsableName(name);
        addButtonRow(buttonRowY, primaryLabel, this::onPrimary, primaryActive);
        setPrimaryActive(primaryActive);

        int listWidth = listBandWidth();
        int listX = (this.field_1230 - listWidth) / 2;
        int belowButtons = addCaptureWarning(buttonRowY + BUTTON_HEIGHT + 8);
        int headerY = Math.max(addUpdateBanner(belowButtons), TOP_Y + 50);
        int linkWidth = this.field_1234.width(openSavesText()) + 8;
        int disclosureWidth = Math.max(listWidth - linkWidth - 4, 80);
        if (!this.entries.isEmpty()) {
            method_13411(new DisclosureWidget(listX, headerY, disclosureWidth));
            method_13411(new OpenSavesWidget(listX + listWidth - linkWidth, headerY, linkWidth));
        }

        int listTopY = headerY + 18;
        int availableHeight = Math.max(this.field_1231 - listTopY - LIST_BOTTOM_MARGIN, 2 * ITEM_HEIGHT + 8);
        int listHeight = Math.min(this.entries.size() * ITEM_HEIGHT + 8, availableHeight);
        DownloadList downloadList = new DownloadList(this.field_1229, listWidth, listHeight, listTopY, ITEM_HEIGHT);
        downloadList.method_6709(listX);
        downloadList.populate(this.entries);
        this.list = downloadList;
        if (!listCollapsed && !this.entries.isEmpty()) {
            this.field_20307.add(downloadList);
        }
        // removed() closes the scanner when a screen is pushed on top, so a re-open recreates it and lets the
        // visible rows re-submit; the walked overlay persists, so nothing already walked is re-walked.
        if (this.sizeScanner.isClosed()) {
            this.sizeScanner = new OnDiskSizeScanner();
            this.scheduledWalks.clear();
            this.scheduledProbes.clear();
        }
        method_18421(field);
        // Below 1.17 the screen's focus does not propagate into the widget (there is no Screen.setInitialFocus at
        // this band), so the name field takes no cursor and no typing until clicked; focus the EditBox directly.
        field.setFocus(true);
    }

    /**
     * In-capture: a non-editable label and a Stop button replace the field, primary action, list, and links, so the
     * screen doubles as the running download's control. The browse affordances are for idle downloads only.
     */
    private void initCapturing(CaptureState state) {
        this.nameField = null;
        this.list = null;
        this.selectedEntry = null;

        String name = this.activeDownloadName != null ? this.activeDownloadName : this.defaultName;
        Component labelText = amberComponent(new TranslatableComponent("wdl.screen.downloads.downloading", name));
        StringLabel label = new StringLabel(labelText, this.field_1234.width(labelText.getString()), FIELD_HEIGHT);
        centerTopWidget(label, FIELD_HEIGHT);
        method_13411(label);

        int buttonRowY = label.field_1052 + FIELD_HEIGHT + 6;
        boolean recording = state == CaptureState.RECORDING;
        // a finishing save shows a disabled Saving label, not an actionable Stop
        class_356 primary = addButtonRow(buttonRowY, recording ? stopLabel() : savingLabel(),
                this::stopCapture, recording);
        addUpdateBanner(buttonRowY + BUTTON_HEIGHT + 8);
        if (recording) {
            method_18421(primary);
        }
    }

    /**
     * Restoring: a busy label (named for the player restore's folder, or the folder-less sweep line) and a Done button,
     * so the browse affordances wait until the disk settles while Esc and Done still leave.
     */
    private void initRestoring() {
        this.nameField = null;
        this.primaryButton = null;
        this.list = null;
        this.selectedEntry = null;

        String name = Wdl.restoringFolderName();
        Component labelText = amberComponent(name != null
                ? new TranslatableComponent("wdl.screen.downloads.restoring", name)
                : new TranslatableComponent("wdl.screen.downloads.restoring_sweep"));
        StringLabel label = new StringLabel(labelText, this.field_1234.width(labelText.getString()), FIELD_HEIGHT);
        centerTopWidget(label, FIELD_HEIGHT);
        method_13411(label);

        int buttonRowY = label.field_1052 + FIELD_HEIGHT + 6;
        method_13411(new class_356(0, (this.field_1230 - BUTTON_WIDTH) / 2, buttonRowY, BUTTON_WIDTH, BUTTON_HEIGHT,
                I18n.get("gui.done")) {
            @Override
            public void method_18374(double mouseX, double mouseY) {
                method_18608();
            }
        });
        addUpdateBanner(buttonRowY + BUTTON_HEIGHT + 8);
    }

    private void centerTopWidget(class_356 widget, int height) {
        widget.field_1051 = (this.field_1230 - widget.getWidth()) / 2;
        widget.field_1052 = TOP_Y + 4 + Math.round(0.05f * Math.max(0, this.field_1231 - TOP_Y - height - 8));
    }

    private class_356 addButtonRow(int buttonRowY, Component primaryLabel, Runnable onPrimary,
            boolean primaryActive) {
        int total = BUTTON_WIDTH * 2 + BUTTON_GAP;
        int startX = (this.field_1230 - total) / 2;
        // This band's button carries no hover-tooltip parameter, so the disabled-primary explanation is not shown.
        class_356 primary = new class_356(0, startX, buttonRowY, BUTTON_WIDTH, BUTTON_HEIGHT,
                primaryLabel.getString()) {
            @Override
            public void method_18374(double mouseX, double mouseY) {
                onPrimary.run();
            }
        };
        primary.field_1055 = primaryActive;
        this.primaryButton = method_13411(primary);
        method_13411(new class_356(0, startX + BUTTON_WIDTH + BUTTON_GAP, buttonRowY, BUTTON_WIDTH, BUTTON_HEIGHT,
                I18n.get("gui.done")) {
            @Override
            public void method_18374(double mouseX, double mouseY) {
                method_18608();
            }
        });
        return primary;
    }

    /** Tint {@code component} the nearest vanilla color to the brand amber (gold), mutating its style in place. */
    private static Component amberComponent(Component component) {
        component.getStyle().setColor(ChatFormatting.GOLD);
        return component;
    }

    /** The centered band the existing-worlds list and the update banner both span. */
    private int listBandWidth() {
        return Mth.clamp(this.field_1230 - 20, 280, 600);
    }

    /**
     * The newer-release banner: a bordered footer notice below the button row, sized to its content (capped at the list
     * band) and centered, added when a result is held and not dismissed, re-evaluated on every open so a result that
     * lands after the first open still surfaces; dismissal is remembered on the launch-scoped check, not this recreated
     * screen. It deliberately ignores showChatMessages: silencing chat must not hide an affordance inside the mod's own
     * UI. Returns the y where the content below the band resumes, {@code y} untouched when the banner is hidden.
     */
    private int addUpdateBanner(int y) {
        UpdateCheck updateCheck = Wdl.updateCheck();
        if (!updateCheck.bannerVisible()) {
            return y;
        }
        UpdateAvailable update = updateCheck.available().get();
        Component prose = new TranslatableComponent("wdl.screen.downloads.update_available",
                update.runningDisplay(), update.latestDisplay());
        int maxBandWidth = listBandWidth();
        int modrinthWidth = this.field_1234.width("Modrinth");
        int curseforgeWidth = this.field_1234.width("CurseForge");
        int dismissWidth = this.field_1234.width(DISMISS_GLYPH);
        int leftWidth = this.field_1234.width(WARNING_GLYPH) + this.field_1234.width(prose.getString());
        // A row too wide for the list band drops the lower-priority CurseForge label rather than
        // overflowing the box.
        boolean curseforgeFits = leftWidth + BANNER_GAP + modrinthWidth + BANNER_GAP + curseforgeWidth
                + BANNER_GAP + dismissWidth <= maxBandWidth - 2 * BANNER_GAP;
        int innerWidth = leftWidth + BANNER_GAP + modrinthWidth + BANNER_GAP + dismissWidth
                + (curseforgeFits ? BANNER_GAP + curseforgeWidth : 0);
        // The edge padding is the one inter-item gap, so every distance across the row reads uniform.
        int bandWidth = Math.min(innerWidth + 2 * BANNER_GAP, maxBandWidth);
        int bandX = (this.field_1230 - bandWidth) / 2;
        method_13411(new BannerPanelWidget(bandX, y, bandWidth, prose));
        int x = bandX + BANNER_GAP + leftWidth + BANNER_GAP;
        method_13411(new BannerLinkWidget(x, y, modrinthWidth, "Modrinth",
                UpdateCheck.MODRINTH_PAGE_URL));
        if (curseforgeFits) {
            x += modrinthWidth + BANNER_GAP;
            method_13411(new BannerLinkWidget(x, y, curseforgeWidth, "CurseForge",
                    UpdateCheck.CURSEFORGE_PAGE_URL));
        }
        method_13411(new BannerDismissWidget(bandX + bandWidth - BANNER_GAP - dismissWidth, y,
                dismissWidth));
        return y + BANNER_HEIGHT + 8;
    }

    /**
     * The passive "capture partially disabled" indicator at the start action: a centered amber caution line below the
     * button row when a core capture kind is off, the cross-surface twin of the settings-screen row mark
     * (defense-in-depth, not the sole guard). Returns the y where content below it resumes, {@code y} untouched when
     * capture is whole.
     */
    private int addCaptureWarning(int y) {
        if (!this.capturePartiallyDisabled) {
            return y;
        }
        Component text = new TranslatableComponent("wdl.screen.downloads.capture_disabled");
        int width = this.field_1234.width(WARNING_GLYPH) + this.field_1234.width(text.getString());
        method_13411(new CaptureWarningWidget((this.field_1230 - width) / 2, y, width, text));
        return y + HEADER_ROW_HEIGHT + 6;
    }

    /** Walk a row's folder the first time it is drawn, so only visible rows are walked, never the whole list. */
    private void scheduleSizeWalk(Path folder) {
        if (!this.walkedSizes.containsKey(folder) && this.scheduledWalks.add(folder)) {
            this.sizeScanner.submit(folder, false);
        }
    }

    /** Probe a tainted row's restore source the first time it is drawn; the answer feeds the restore chip. */
    private void scheduleAvailabilityProbe(Path folder) {
        if (!this.availableSources.containsKey(folder) && this.scheduledProbes.add(folder)) {
            this.sizeScanner.submit(folder, true);
        }
    }

    private Component downloadLabel() {
        return new TranslatableComponent("wdl.screen.downloads.download");
    }

    private Component resumeLabel() {
        return new TranslatableComponent("wdl.screen.downloads.resume");
    }

    private Component stopLabel() {
        return new TranslatableComponent("wdl.screen.downloads.stop");
    }

    private Component savingLabel() {
        return new TranslatableComponent("wdl.screen.downloads.saving");
    }

    private String openSavesText() {
        return new TranslatableComponent("wdl.screen.downloads.open_saves").getString();
    }

    private void onNameTyped(String text) {
        if (suppressNameResponder) {
            return;
        }
        // EditBox runs its responder on a bare caret move as well as an edit (moveCursorTo calls onValueChange),
        // so text still matching the selected row is an arrow key or a click, not an edit, and must leave the
        // selection alone.
        if (this.selectedEntry != null && text.equals(this.selectedEntry.folderName())) {
            return;
        }
        this.retainedName = text;
        // Editing the name is a fresh download: drop any selected row and relabel the primary action.
        this.selectedEntry = null;
        if (this.primaryButton != null) {
            this.primaryButton.field_1053 = downloadLabel().getString();
        }
        setPrimaryActive(TargetResolver.hasUsableName(text));
    }

    /** Set the primary action's enabled state, explaining a disabled Download with the why tooltip. */
    private void setPrimaryActive(boolean active) {
        if (this.primaryButton == null) {
            return;
        }
        this.primaryButton.field_1055 = active;
    }

    private void onRowSelected(DownloadEntry entry) {
        this.selectedEntry = entry;
        if (this.nameField != null) {
            suppressNameResponder = true;
            this.nameField.setValue(entry.folderName()); // resume targets the folder verbatim
            this.nameField.moveCursorToEnd(); // a long prefilled name shows its end
            suppressNameResponder = false;
            method_18421(this.nameField);
        }
        if (this.primaryButton != null) {
            this.primaryButton.field_1053 = resumeLabel().getString();
        }
        setPrimaryActive(true); // a selected row resumes its folder verbatim, so the name gate does not apply
    }

    private void onPrimary() {
        DownloadEntry selected = this.selectedEntry;
        if (selected != null) {
            resumeEntry(selected);
            return;
        }
        String typed = this.nameField != null ? this.nameField.getValue() : "";
        DownloadTarget target = TargetResolver.resolveNew(typed, LocalDate.now(), this.appendDateSuffix);
        switch (TargetResolver.classifyTarget(target.folderName(), this.savesDirectory, this.loadedWorld)) {
            case REFUSE_LOADED:
                refuseLoadedWorld();
                break;
            // Merging into an existing folder is a resume: a RESUME target takes the pre-merge backup and keeps
            // the world's own level.dat name, where a NEW target would skip the pre-merge backup.
            case RESUME_EXISTING:
                resumeFolder(target.folderName(), true);
                break;
            case NEW:
                start(target);
                break;
        }
    }

    /** The one-click resume shortcut for a recoverable row: same confirm as the primary Resume. */
    private void recover(DownloadEntry entry) {
        resumeEntry(entry);
    }

    /**
     * The restore chip's click: re-run the source discovery fresh at the gate (the cached answer only decided to show
     * the chip), refuse with the source-changed toast when the source vanished, else pin it into the restore confirm
     * whose Restore dispatches the guarded replace and returns here to show the busy label.
     */
    private void restoreEntry(DownloadEntry entry) {
        Optional<RestoreSource> source = RestoreSource.find(this.savesDirectory, entry.folderName());
        if (!source.isPresent()) {
            this.onRefusal.accept(ToastCopy.restoreRefusedSourceChanged());
            return;
        }
        RestoreSource pinned = source.get();
        if (this.field_1229 != null) {
            this.field_1229.setScreen(ResumeConfirm.createRestore("wdl.screen.downloads.confirm_restore",
                    entry.folderName(), pinned.zip().getFileName().toString(),
                    RestoreOperation.nextSnapshotName(this.savesDirectory, entry.folderName()), this.zipOnResume,
                    () -> {
                        Wdl.launchRestore(this.savesDirectory, entry.folderName(), pinned);
                        this.field_1229.setScreen(this);
                    },
                    () -> this.field_1229.setScreen(this)));
        }
    }

    private void resumeEntry(DownloadEntry entry) {
        if (entry.isCurrentlyLoaded()) {
            refuseLoadedWorld();
            return;
        }
        resumeFolder(entry.folderName(), false);
    }

    /**
     * The screen's resume junction, the front-loaded guards mirroring the command flow's so a resume that will be
     * refused never flashes a backup warning first: the remote-world test, then the managed stat with its per-cause
     * split (a vanished folder is the missing cause; a file or an unmanaged directory at the name is a foreign occupant
     * either way, named by its filesystem-reported spelling), then the tainted gate. The typed-name tail passes
     * {@code suggestRename} so an occupant refusal carries the name-choosing advice; the row and Recover paths pass
     * false, since a selected row involves no typed name to change.
     */
    private void resumeFolder(String folderName, boolean suggestRename) {
        if (!this.remoteWorld.getAsBoolean()) {
            this.onRefusal.accept(ToastCopy.joinMultiplayer());
            return;
        }
        DownloadTarget target = TargetResolver.resolveResume(folderName, this.savesDirectory);
        String resolvedName = target.folderName();
        Path saveFolder = this.savesDirectory.resolve(resolvedName);
        if (!DownloadFolders.isWdlManaged(saveFolder)) {
            this.onRefusal.accept(Files.exists(saveFolder)
                    ? ToastCopy.refuseOccupant(resolvedName, suggestRename)
                    : ToastCopy.refuseFolderMissing(resolvedName));
            return;
        }
        gateTaintedThenResume(resolvedName, () -> confirmThenStart(target, resolvedName));
    }

    private void refuseLoadedWorld() {
        this.onRefusal.accept(ToastCopy.refuseLoaded());
    }

    /**
     * The tainted decision (fresh disk read) as the outermost resume gate: a REFUSE stops with the refusal toast,
     * unless a fresh source discovery finds a clean backup, in which case the refusal becomes the blocked-offer screen
     * (a screen click is deliberate by construction) whose Restore dispatches the replace instead; a CONFIRM shows the
     * tainted confirm whose Continue runs the map-id mismatch gate then a direct start (so the tainted confirm stands
     * in for the merge confirm, one prompt not two), picking the restorable tip variant when the folder is tainted and
     * a source exists (an unknown taint keeps the plain tainted copy); and an ALLOW runs the map-id mismatch gate then
     * {@code allowDownstream}, the caller's normal resume tail. The hard refusal comes before any advisory warn, so a
     * tainted-and-blocked folder never flashes the mismatch confirm.
     */
    private void gateTaintedThenResume(String folderName, Runnable allowDownstream) {
        SinglePlayerTaint.TaintState taint = SinglePlayerTaint.classify(this.savesDirectory.resolve(folderName));
        SinglePlayerTaint.Decision decision = SinglePlayerTaint.decide(taint, this.blockTaintedResume);
        if (decision == SinglePlayerTaint.Decision.REFUSE) {
            Optional<RestoreSource> source = RestoreSource.find(this.savesDirectory, folderName);
            if (source.isPresent() && this.field_1229 != null) {
                RestoreSource pinned = source.get();
                this.field_1229.setScreen(ResumeConfirm.createRestore(
                        "wdl.screen.downloads.confirm_restore_blocked",
                        folderName, pinned.zip().getFileName().toString(),
                        RestoreOperation.nextSnapshotName(this.savesDirectory, folderName), this.zipOnResume,
                        () -> {
                            Wdl.launchRestore(this.savesDirectory, folderName, pinned);
                            this.field_1229.setScreen(this);
                        },
                        () -> this.field_1229.setScreen(this)));
                return;
            }
            refuseTainted();
            return;
        }
        boolean mismatch = MapManifest.schemeMismatch(this.savesDirectory.resolve(folderName), this.remapMapIds);
        if (decision == SinglePlayerTaint.Decision.CONFIRM) {
            DownloadTarget target = TargetResolver.resolveResume(folderName, this.savesDirectory);
            if (this.field_1229 == null) {
                return;
            }
            boolean backupHere = this.zipOnResume && !mismatch;
            Runnable onContinue = () -> gateMapIdMismatch(folderName, mismatch, this.zipOnResume,
                    () -> start(target));
            Runnable onCancel = () -> this.field_1229.setScreen(this);
            Optional<RestoreSource> source = taint == SinglePlayerTaint.TaintState.TAINTED
                    ? RestoreSource.find(this.savesDirectory, folderName)
                    : Optional.empty();
            this.field_1229.setScreen(source.isPresent()
                    ? ResumeConfirm.createTaintedRestorable(folderName,
                            source.get().zip().getFileName().toString(),
                            FinalizeOutputs.nextBackupName(this.savesDirectory, folderName), backupHere,
                            onContinue, onCancel)
                    : ResumeConfirm.create("wdl.screen.downloads.confirm_tainted", folderName,
                            FinalizeOutputs.nextBackupName(this.savesDirectory, folderName), backupHere,
                            onContinue, onCancel));
            return;
        }
        gateMapIdMismatch(folderName, mismatch, this.zipOnResume && !this.confirmResume, allowDownstream);
    }

    /**
     * When {@code mismatch} is set, show a one-time confirm whose Continue runs {@code proceed}; otherwise run
     * {@code proceed} now. {@code backupHere} is whether this confirm is the terminal prompt that commits the resume,
     * so it alone carries the backup reassurance while the earlier gates pass false, showing the line once. The
     * reusable middle layer between the tainted gate and the resume tail: {@code proceed} is a direct start after the
     * tainted confirm, or the caller's normal downstream (the merge confirm) on the allow path.
     */
    private void gateMapIdMismatch(String folderName, boolean mismatch, boolean backupHere, Runnable proceed) {
        if (!mismatch) {
            proceed.run();
            return;
        }
        if (this.field_1229 != null) {
            this.field_1229.setScreen(ResumeConfirm.create("wdl.screen.downloads.confirm_map_id_mismatch",
                    folderName, FinalizeOutputs.nextBackupName(this.savesDirectory, folderName), backupHere,
                    proceed,
                    () -> this.field_1229.setScreen(this)));
        }
    }

    private void refuseTainted() {
        this.onRefusal.accept(ToastCopy.refuseTainted());
    }

    private void confirmThenStart(DownloadTarget target, String folderName) {
        if (this.field_1229 == null) {
            return;
        }
        if (!this.confirmResume) {
            start(target); // continue silently; the backup is separate, still governed by zipOnResume
            return;
        }
        this.field_1229.setScreen(ResumeConfirm.create("wdl.screen.downloads.merge",
                folderName, FinalizeOutputs.nextBackupName(this.savesDirectory, folderName), this.zipOnResume,
                () -> start(target),
                () -> this.field_1229.setScreen(this))); // cancel returns here, typed name preserved, no backup
    }

    private void start(DownloadTarget target) {
        this.onStart.accept(target);
        if (this.field_1229 != null) {
            this.field_1229.setScreen(null); // back to the game; the capture runs
        }
    }

    /** Request that the running capture stop; tick() swaps to the saving widget set once the state flips. */
    private void stopCapture() {
        this.onStop.run();
    }

    /** The "Existing Worlds (N)" disclosure: a focusable, narratable header whose whole row toggles the list. */
    private void rebuildWidgets() {
        this.field_1232.clear();
        this.field_20307.clear();
        init();
    }

    /** A centered, inert header label: below the 1.19.4 GUI additions there is no vanilla StringWidget. */
    private final class StringLabel extends class_356 {
        private final Component text;

        StringLabel(Component text, int width, int height) {
            super(0, 0, 0, width, height, text.getString());
            this.text = text;
            this.field_1055 = false;
        }

        @Override
        public void renderButton(int mouseX, int mouseY, float partialTick) {
            new RenderSurfaceImpl().text(field_1234, this.text, this.field_1051,
                    this.field_1052 + (this.field_1050 - field_1234.lineHeight) / 2, NAME_ARGB);
        }
    }

    private final class DisclosureWidget extends class_356 {
        DisclosureWidget(int x, int y, int width) {
            super(0, x, y, width, HEADER_ROW_HEIGHT, new TranslatableComponent("wdl.screen.downloads.existing",
                    entries.size()).getString());
        }

        @Override
        public void renderButton(int mouseX, int mouseY, float partialTick) {
            RenderSurface surface = new RenderSurfaceImpl();
            String triangle = listCollapsed ? TRIANGLE_COLLAPSED : TRIANGLE_EXPANDED;
            Component header = new TextComponent(triangle)
                    .append(new TranslatableComponent("wdl.screen.downloads.existing", entries.size()));
            surface.text(field_1234, header, this.field_1051 + 4,
                    this.field_1052 + (this.field_1050 - field_1234.lineHeight) / 2, HEADER_ARGB);
        }

        @Override
        public void method_18374(double mouseX, double mouseY) {
            toggleCollapsed();
        }
    }

    /** The "Open Saves Folder" link: a focusable, narratable control that opens the saves directory. */
    private final class OpenSavesWidget extends class_356 {
        OpenSavesWidget(int x, int y, int width) {
            super(0, x, y, width, HEADER_ROW_HEIGHT,
                    new TranslatableComponent("wdl.screen.downloads.open_saves").getString());
        }

        @Override
        public void renderButton(int mouseX, int mouseY, float partialTick) {
            RenderSurface surface = new RenderSurfaceImpl();
            int color = field_5056 ? LINK_HOVER_ARGB : LINK_REST_ARGB;
            surface.text(field_1234, openSavesText(), this.field_1051 + 4,
                    this.field_1052 + (this.field_1050 - field_1234.lineHeight) / 2, color);
        }

        @Override
        public void method_18374(double mouseX, double mouseY) {
            Util.getPlatform().openFile(savesDirectory.toFile());
        }
    }

    /** The banner's bordered band: an amber outline over a subtle panel, with the glyph and prose inside. */
    private final class BannerPanelWidget extends class_356 {
        private final Component prose;

        BannerPanelWidget(int x, int y, int width, Component prose) {
            super(0, x, y, width, BANNER_HEIGHT, prose.getString());
            this.prose = prose;
            this.field_1055 = false; // decorative and inert, like a vanilla StringWidget
        }

        @Override
        public void renderButton(int mouseX, int mouseY, float partialTick) {
            RenderSurface surface = new RenderSurfaceImpl();
            surface.fill(this.field_1051, this.field_1052, this.field_1051 + getWidth(),
                    this.field_1052 + this.field_1050, BANNER_FILL_ARGB);
            surface.outline(this.field_1051, this.field_1052, getWidth(), this.field_1050, BANNER_OUTLINE_ARGB);
            int textY = this.field_1052 + (this.field_1050 - field_1234.lineHeight) / 2;
            surface.text(field_1234, WARNING_GLYPH, this.field_1051 + BANNER_GAP, textY, BANNER_GLYPH_ARGB);
            surface.text(field_1234, this.prose, this.field_1051 + BANNER_GAP + field_1234.width(WARNING_GLYPH), textY,
                    BANNER_TEXT_ARGB);
        }
    }

    /** One named banner link: an underlined teal label opening its release page in the OS browser. */
    private final class BannerLinkWidget extends class_356 {
        private final Component label;
        private final String url;

        BannerLinkWidget(int x, int y, int width, String label, String url) {
            super(0, x, y, width, BANNER_HEIGHT, label);
            this.label = new TextComponent(label);
            this.url = url;
        }

        @Override
        public void renderButton(int mouseX, int mouseY, float partialTick) {
            RenderSurface surface = new RenderSurfaceImpl();
            int color = field_5056 ? BANNER_LINK_HOVER_ARGB : BANNER_LINK_ARGB;
            surface.text(field_1234, this.label, this.field_1051,
                    this.field_1052 + (this.field_1050 - field_1234.lineHeight) / 2, color);
            if (this.field_5056) {
                WdlDownloadsScreen.this.renderTooltip(this.url, mouseX, mouseY);
            }
        }

        @Override
        public void method_18374(double mouseX, double mouseY) {
            Util.getPlatform().openUri(this.url);
        }
    }

    /** The banner's dismiss control: hides the banner for the rest of the launch. */
    private final class BannerDismissWidget extends class_356 {
        BannerDismissWidget(int x, int y, int width) {
            super(0, x, y, width, BANNER_HEIGHT,
                    new TranslatableComponent("wdl.screen.downloads.update_dismiss").getString());
        }

        @Override
        public void renderButton(int mouseX, int mouseY, float partialTick) {
            RenderSurface surface = new RenderSurfaceImpl();
            int color = field_5056 ? LINK_HOVER_ARGB : LINK_REST_ARGB;
            // The glyph comes from the fallback font, whose ink sits high in its line box, so the shared
            // centering formula reads a pixel high without the nudge.
            surface.text(field_1234, DISMISS_GLYPH, this.field_1051,
                    this.field_1052 + (this.field_1050 - field_1234.lineHeight) / 2 + 1, color);
        }

        @Override
        public void method_18374(double mouseX, double mouseY) {
            Wdl.updateCheck().dismissBanner();
            rebuildWidgets();
        }
    }

    /** The passive capture-disabled caution: an inert amber glyph and label with an explanatory tooltip. */
    private final class CaptureWarningWidget extends class_356 {
        private final Component text;

        CaptureWarningWidget(int x, int y, int width, Component text) {
            super(0, x, y, width, HEADER_ROW_HEIGHT, text.getString());
            this.text = text;
            this.field_1055 = false; // a passive indicator, like the update banner's panel, not a control
        }

        @Override
        public void renderButton(int mouseX, int mouseY, float partialTick) {
            RenderSurface surface = new RenderSurfaceImpl();
            int textY = this.field_1052 + (this.field_1050 - field_1234.lineHeight) / 2;
            surface.text(field_1234, WARNING_GLYPH, this.field_1051, textY, BANNER_GLYPH_ARGB);
            surface.text(field_1234, this.text, this.field_1051 + field_1234.width(WARNING_GLYPH), textY, NAME_ARGB);
            if (this.field_5056) {
                WdlDownloadsScreen.this.renderTooltip(field_1234.split(
                        new TranslatableComponent("wdl.screen.downloads.capture_disabled.tooltip").getString(), 200),
                        mouseX, mouseY);
            }
        }
    }

    private void toggleCollapsed() {
        listCollapsed = !listCollapsed;
        if (this.list == null) {
            return;
        }
        if (listCollapsed) {
            this.field_20307.remove(this.list);
        } else {
            this.field_20307.add(this.list);
        }
    }

    @Override
    public void method_18608() {
        if (this.field_1229 != null) {
            this.field_1229.setScreen(this.parent);
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTick) {
        // At this band the screen paints its own background each frame; without it the widgets render in the world
        // render's leftover GL state and stay invisible, leaving only the list's own dirt fill (the settings screen
        // needs the same, and the higher bands did not).
        this.method_1043();
        super.render(mouseX, mouseY, partialTick);
        // The name field is an EditBox, not a button, so the screen button pass does not draw it; draw it by hand.
        if (this.nameField != null) {
            this.nameField.renderButton(mouseX, mouseY, partialTick);
        }
        // Below the 1.19.4 GUI additions Screen.render paints only its buttons, so the list added as a widget is
        // drawn by hand here, in the same paint order the renderable list gave it on the higher bands.
        if (this.list != null && !listCollapsed && !this.entries.isEmpty()) {
            this.list.method_1053(mouseX, mouseY, partialTick);
        }
        // Drawing a tooltip during the row render leaves it clipped or painted over by the list; deferring it to
        // after the list draws it cleanly on top.
        if (this.pendingTooltip != null) {
            this.pendingTooltip.run();
            this.pendingTooltip = null;
        }
    }

    @Override
    public void tick() {
        CaptureState state = this.captureState.get();
        if (state != this.builtForState) {
            // The flip back to idle is the moment disk state changed (a save finished, or a restore or sweep
            // ended), so the browse model and the caches are refreshed here, not on resize-driven init()
            // reruns; a completed sweep that never touched the disk skips the re-pull. The availability
            // cache rides the entries list, so it invalidates on the same re-pull.
            if (state == CaptureState.IDLE
                    && (this.builtForState != CaptureState.RESTORING || Wdl.lastRestoreChangedDisk())) {
                this.entries = this.entriesSupplier.get();
                this.walkedSizes.clear();
                this.scheduledWalks.clear();
                this.availableSources.clear();
                this.scheduledProbes.clear();
            }
            rebuildWidgets(); // the capture state flipped while open (started, stopped, or finished saving)
            return;
        }
        if (state == CaptureState.IDLE
                && Util.getMillis() - this.lastSweepCheckMillis >= RestoreOperation.RestoreSweep.TTL_MS) {
            this.lastSweepCheckMillis = Util.getMillis();
            if (RestoreOperation.RestoreSweep.hasWork(this.savesDirectory)) {
                Wdl.launchSweep(this.savesDirectory);
            }
        }
        // Apply finished walks on the render thread: a real on-disk total becomes the row's size; a failed walk
        // is dropped, so the row keeps showing no size rather than a wrong one. A zero total is dropped the same
        // way, harmless because a wdl-managed folder always has level.dat and a wdl/ record, so it is never zero.
        // An availability answer fills the restore-source cache; a probe that found nothing stays out of it.
        for (OnDiskSizeScanner.Result result : this.sizeScanner.drainCompleted()) {
            Path source = result.restoreSource();
            if (source != null) {
                this.availableSources.put(result.folder(), source);
            } else if (result.size().isPresent() && result.size().getAsLong() > 0) {
                this.walkedSizes.put(result.folder(), result.size().getAsLong());
            }
        }
    }

    @Override
    public void method_1030() {
        if (this.list != null) {
            this.list.closeIcons();
        }
        this.sizeScanner.close(); // drop queued walks and discard any in-flight result for this closed screen
    }

    /**
     * The named row's restore-chip hit box as last rendered, or null while the row is absent or its chip has never been
     * drawn (source not cached, the row is the loaded world, or the list has not been expanded this open). Collapsing
     * the list only removes its widget, so a row rendered once keeps its last-rendered rectangle rather than reverting
     * to null; read the box only while the list is expanded. Exists for the test harness, so the screen gametests read
     * the live rectangle instead of recomputing pixel math.
     */
    public @Nullable Rect2i restoreChipBox(String folderName) {
        DownloadList downloadList = this.list;
        if (downloadList == null) {
            return null;
        }
        for (DownloadList.Row row : downloadList.method_18423()) {
            if (row.entry.folderName().equals(folderName)) {
                return row.restoreChipBox();
            }
        }
        return null;
    }

    /** The name field; Enter submits the primary action (download or resume) only while the button is enabled. */
    private final class NameField extends EditBox {
        NameField(Font font, int x, int y, int width, int height) {
            super(0, font, x, y, width, height);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (primaryButton != null && primaryButton.field_1055) {
                    onPrimary();
                    return true;
                }
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
    }

    /** The row list: a vanilla selection list whose selection prefills the name field. */
    private final class DownloadList extends class_1802<DownloadList.Row> {
        private boolean suppressPrefill;
        private @Nullable Row selectedRow;

        DownloadList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, y + height, itemHeight);
        }

        @Override
        public int method_6706() {
            return this.field_7733 - 14; // scale rows to the (centered) list band, less the scrollbar gutter
        }

        // No-op: the base fills opaque dirt across the list's horizontal band from the screen top (y zero) down to
        // the list top, and again below the list. Because this screen draws the list last, that top strip paints
        // over the name field, the primary and Done buttons, and the Existing-Worlds and Open-Saves headers whenever
        // the list is expanded, so it is suppressed here. The settings list draws first, so it keeps the fill.
        @Override
        protected void method_1065(int top, int bottom, int alphaTop, int alphaBottom) {}

        @Override
        protected int method_1069() {
            // This band's default is a fixed width/2 + 124 that assumes the 220-wide default row and a list at
            // x zero, so with this screen's wider row and a left position applied it lands inside each row and the
            // entry-at-position test treats everything past it as the scrollbar. Later bands derive it from the
            // real row right edge, which this follows.
            return this.field_7734 + this.field_7733 / 2 + method_6706() / 2 + 10;
        }

        // The selected row's highlight treatment: this list marks its own selected row, where the entry-list base
        // marks none.
        @Override
        protected boolean moveSelection(int index) {
            return this.selectedRow != null && method_18423().indexOf(this.selectedRow) == index;
        }

        void populate(List<DownloadEntry> rows) {
            for (DownloadEntry entry : rows) {
                method_18398(new Row(entry));
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            // An arrow / Recover click highlights the row like a body click, but does not prefill the field.
            int index = method_18411(mouseX, mouseY);
            Row row = index >= 0 && index < method_18423().size() ? method_18423().get(index) : null;
            if (row != null && row.handleEdgeClick((int) mouseX, (int) mouseY)) {
                this.suppressPrefill = true;
                setSelected(row);
                this.suppressPrefill = false;
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        void setSelected(@Nullable Row entry) {
            this.selectedRow = entry;
            if (entry != null) {
                NarratorChatListener.INSTANCE.handle(ChatType.SYSTEM,
                        new TranslatableComponent("wdl.screen.downloads.narration", entry.displayName,
                                entry.lastPlayed));
                if (!this.suppressPrefill) { // a body click prefills; an edge click only highlights
                    onRowSelected(entry.entry);
                }
            }
        }

        void closeIcons() {
            for (Row row : method_18423()) {
                row.close();
            }
        }

        /** One download row: icon, name, last-played date, and either the summary plus size or a Recover chip. */
        final class Row extends class_1802.class_1803<Row> {
            private final DownloadEntry entry;
            private final String displayName;
            private final String lastPlayed;
            private final Path folder;
            private final @Nullable DynamicTexture icon;
            private @Nullable ResourceLocation iconLocation;
            private @Nullable String snapshotName;
            private int line2Top;
            private int arrowLeft;
            private int arrowRight;
            private int recoverLeft = -1;
            private int recoverRight = -1;
            private int restoreLeft = -1;
            private int restoreRight = -1;
            private int contentX;
            private int contentY;
            private int contentWidth;
            private boolean iconClosed;

            Row(DownloadEntry entry) {
                this.entry = entry;
                // Strip legacy section codes so a hostile recorded name cannot color or obfuscate the row.
                this.displayName = ChatFormatting.stripFormatting(entry.displayName());
                this.lastPlayed = dateFormat.format(Instant.ofEpochMilli(entry.lastPlayedEpochMillis()));
                this.folder = savesDirectory.resolve(entry.folderName());
                this.icon = loadIcon(entry);
            }

            private @Nullable DynamicTexture loadIcon(DownloadEntry entry) {
                byte[] bytes = entry.iconBytes();
                if (bytes == null || field_1229 == null) {
                    return null;
                }
                try {
                    // Below 1.20 there is no FaviconTexture; the world icon is managed by hand as vanilla's list does.
                    DynamicTexture texture = new DynamicTexture(NativeImage.read(new ByteArrayInputStream(bytes)));
                    // The sha1 hex is always a valid ResourceLocation path and uniquely names the folder, so it
                    // stands in for the pre-1.16 missing Util.sanitizeName folder-name segment.
                    ResourceLocation location = new ResourceLocation("wdl", "world/"
                            + Hashing.sha1().hashUnencodedChars(entry.folderName()) + "/icon");
                    field_1229.getTextureManager().register(location, texture);
                    this.iconLocation = location;
                    return texture;
                } catch (IOException | RuntimeException e) {
                    return null; // a validated-but-undecodable icon is dropped, not fatal
                }
            }

            @Override
            public void method_6700(int rowWidth, int rowHeight, int mouseX, int mouseY, boolean hovering,
                    float partialTick) {
                this.contentX = method_18404();
                this.contentY = method_18403();
                this.contentWidth = rowWidth;
                renderContent(mouseX, mouseY, hovering, partialTick);
            }

            private int getContentX() {
                return contentX;
            }

            private int getContentY() {
                return contentY;
            }

            private int getContentWidth() {
                return contentWidth;
            }

            private void renderContent(int mouseX, int mouseY, boolean hovering, float partialTick) {
                RenderSurface surface = new RenderSurfaceImpl(WdlDownloadsScreen.this);
                // Probe or walk only visible rows, on disjoint domains: a tainted row is availability-probed
                // for its restore chip and never walked, a complete row is walked for its size.
                if (this.entry.isTainted()) {
                    scheduleAvailabilityProbe(this.folder);
                } else if (this.entry.health() == DownloadHealth.COMPLETE) {
                    scheduleSizeWalk(this.folder);
                }
                int rowX = getContentX();
                int rowY = getContentY();
                int entryWidth = getContentWidth();
                int rightEdge = rowX + entryWidth - 4;
                if (this.iconLocation != null) {
                    surface.blitFavicon(this.iconLocation, rowX + 2, rowY + 3, ICON_SIZE);
                }
                int textX = rowX + 4 + ICON_ADVANCE;
                int dateX = rightEdge - field_1234.width(this.lastPlayed);
                surface.text(field_1234, this.lastPlayed, dateX, rowY + 4, GRAY_ARGB);
                int labelMax = entryWidth - field_1234.width(this.lastPlayed) - 12 - ICON_ADVANCE;
                surface.text(field_1234, ClientText.ellipsize(field_1234, this.displayName, labelMax),
                        textX, rowY + 4, NAME_ARGB);

                this.line2Top = rowY + 14;
                int slotLeft = renderStatus(surface, rightEdge, this.line2Top, mouseX, mouseY, hovering);
                renderSummary(surface, textX, this.line2Top, slotLeft - 4);
            }

            private void renderSummary(RenderSurface surface, int textX, int y, int rightLimit) {
                DownloadCounts counts = this.entry.counts();
                if (counts == null) {
                    return; // a recoverable download has no trustworthy summary
                }
                int pairX = textX;
                boolean any = false;
                if (counts.chunks() != 0) {
                    pairX = cell(surface, pairX, y, "wdl.screen.downloads.summary.chunks", counts.chunks(),
                            false, rightLimit);
                    any = true;
                }
                if (this.entry.isChunksOnly()) {
                    // a resume knows the cumulative chunk total but no cumulative entity or container count;
                    // mark those two not applicable rather than show a session number beside cumulative chunks
                    pairX = cell(surface, pairX, y, "wdl.screen.downloads.summary.entities", SUMMARY_ABSENT,
                            any, rightLimit);
                    cell(surface, pairX, y, "wdl.screen.downloads.summary.containers", SUMMARY_ABSENT,
                            true, rightLimit);
                    return;
                }
                pairX = cell(surface, pairX, y, "wdl.screen.downloads.summary.entities", counts.entities(),
                        any, rightLimit);
                cell(surface, pairX, y, "wdl.screen.downloads.summary.containers", counts.containers(),
                        true, rightLimit);
            }

            /**
             * One summary cell clamped at {@code rightLimit} (the slot's left edge): a piece that crosses the limit is
             * ellipsized and freezes the cursor there, so the cells that follow draw nothing.
             */
            private int cell(RenderSurface surface, int pairX, int y, String labelKey, int value,
                    boolean separator, int rightLimit) {
                return cell(surface, pairX, y, labelKey, Integer.toString(value), separator, rightLimit);
            }

            private int cell(RenderSurface surface, int pairX, int y, String labelKey, String text,
                    boolean separator, int rightLimit) {
                if (pairX >= rightLimit) {
                    return rightLimit;
                }
                if (separator) {
                    if (pairX + 6 + field_1234.width(DOT) + 6 >= rightLimit) {
                        return rightLimit;
                    }
                    surface.text(field_1234, DOT, pairX + 6, y, GRAY_ARGB);
                    pairX += 6 + field_1234.width(DOT) + 6;
                }
                String label = new TranslatableComponent(labelKey).getString();
                String clampedLabel = ClientText.ellipsize(field_1234, label, rightLimit - pairX);
                surface.text(field_1234, clampedLabel, pairX, y, GRAY_ARGB);
                if (!clampedLabel.equals(label)) {
                    return rightLimit;
                }
                int valueX = pairX + field_1234.width(label) + 4;
                if (valueX >= rightLimit) {
                    return rightLimit;
                }
                String clampedText = ClientText.ellipsize(field_1234, text, rightLimit - valueX);
                surface.text(field_1234, clampedText, valueX, y, GRAY_ARGB);
                if (!clampedText.equals(text)) {
                    return rightLimit;
                }
                return valueX + field_1234.width(text);
            }

            /**
             * Draw the arrow and the slot content, returning the slot content's left edge (the arrow's when the slot is
             * empty) so the summary can clamp against it.
             */
            private int renderStatus(RenderSurface surface, int rightEdge, int y, int mouseX, int mouseY,
                    boolean hovering) {
                this.arrowLeft = rightEdge - field_1234.width(ARROW);
                this.arrowRight = rightEdge;
                this.recoverLeft = -1;
                this.recoverRight = -1;
                this.restoreLeft = -1;
                this.restoreRight = -1;
                boolean overArrow = hovering && inLine(mouseX, mouseY, this.arrowLeft, this.arrowRight, y);
                surface.text(field_1234, ARROW, this.arrowLeft, y, overArrow ? LINK_HOVER_ARGB : LINK_REST_ARGB);

                // The slot just left of the arrow holds the Singleplayer chip (joined by the Restore action
                // chip when a clean source is cached and the row is not the loaded world), the Recover chip,
                // the Partial chip, or the on-disk size. A chip stands in for the size, so a chip row shows
                // no size and, in renderContent, never walks its folder. A plain row shows its size once it lands.
                int slotRight = this.arrowLeft - 4;
                int slotLeft = this.arrowLeft;
                if (this.entry.isTainted()) {
                    Path source = availableSources.get(this.folder);
                    boolean restorable = false;
                    if (source != null && !this.entry.isCurrentlyLoaded()) {
                        restorable = true;
                        String restoreChip = new TranslatableComponent("wdl.screen.downloads.restore").getString();
                        this.restoreLeft = slotRight - field_1234.width(restoreChip);
                        this.restoreRight = slotRight;
                        boolean overRestore = hovering
                                && inLine(mouseX, mouseY, this.restoreLeft, this.restoreRight, y);
                        drawRestoreChip(surface, restoreChip, this.restoreLeft, y,
                                overRestore ? LINK_HOVER_ARGB : RECOVER_ARGB);
                        if (overRestore) {
                            pendingTooltip = () -> surface.tooltip(field_1234, restoreTooltip(source),
                                    TOOLTIP_WRAP_WIDTH, mouseX, mouseY);
                        }
                        slotRight = this.restoreLeft - 4;
                    }
                    String chip = new TranslatableComponent("wdl.screen.downloads.tainted").getString();
                    int chipLeft = slotRight - field_1234.width(chip);
                    surface.text(field_1234, chip, chipLeft, y, TAINTED_ARGB);
                    if (hovering && inLine(mouseX, mouseY, chipLeft, slotRight, y)) {
                        // With the Restore chip present the tooltip drops the fresh-download advice: the
                        // chip beside it is the better way out.
                        Component taintedTip = new TranslatableComponent(restorable
                                ? "wdl.screen.downloads.tooltip.tainted_restorable"
                                : "wdl.screen.downloads.tooltip.tainted");
                        pendingTooltip = () -> surface.tooltip(field_1234, taintedTip, TOOLTIP_WRAP_WIDTH, mouseX,
                                mouseY);
                    }
                    slotLeft = chipLeft;
                } else if (this.entry.health() == DownloadHealth.RECOVERABLE) {
                    String chip = new TranslatableComponent("wdl.screen.downloads.recover").getString();
                    this.recoverLeft = slotRight - field_1234.width(chip);
                    this.recoverRight = slotRight;
                    boolean overRecover = hovering && inLine(mouseX, mouseY, this.recoverLeft, this.recoverRight, y);
                    surface.text(field_1234, chip, this.recoverLeft, y,
                            overRecover ? LINK_HOVER_ARGB : RECOVER_ARGB);
                    slotLeft = this.recoverLeft;
                } else if (this.entry.health() == DownloadHealth.PARTIAL) {
                    String chip = new TranslatableComponent("wdl.screen.downloads.partial").getString();
                    int chipLeft = slotRight - field_1234.width(chip);
                    surface.text(field_1234, chip, chipLeft, y, PARTIAL_ARGB);
                    if (hovering && inLine(mouseX, mouseY, chipLeft, slotRight, y)) {
                        pendingTooltip = () -> surface.tooltip(field_1234,
                                new TranslatableComponent("wdl.toast.partial.title"), TOOLTIP_WRAP_WIDTH, mouseX,
                                mouseY);
                    }
                    slotLeft = chipLeft;
                } else {
                    OptionalLong size = effectiveSize();
                    if (size.isPresent()) {
                        SizeFormatter.Size formatted = SizeFormatter.format(size.getAsLong());
                        Component text = new TranslatableComponent(formatted.unitKey(), formatted.number());
                        surface.text(field_1234, text, slotRight - field_1234.width(text.getString()), y, GRAY_ARGB);
                        slotLeft = slotRight - field_1234.width(text.getString());
                    }
                }

                if (overArrow) {
                    pendingTooltip = () -> surface.tooltip(field_1234, ImmutableList.of(
                            new TranslatableComponent("wdl.screen.downloads.tooltip.folder", entry.folderName()),
                            new TranslatableComponent("wdl.screen.downloads.tooltip.version", modVersion, mcVersion)),
                            mouseX, mouseY);
                }
                return slotLeft;
            }

            /**
             * The restore glyph comes from the fallback font, whose ink sits high in its line box, so the glyph alone
             * is drawn a pixel lower while the label stays on the row baseline.
             */
            private void drawRestoreChip(RenderSurface surface, String chip, int x, int y, int color) {
                if (chip.startsWith(RESTORE_GLYPH)) {
                    surface.text(field_1234, RESTORE_GLYPH, x, y + 1, color);
                    surface.text(field_1234, chip.substring(RESTORE_GLYPH.length()),
                            x + field_1234.width(RESTORE_GLYPH), y, color);
                } else {
                    surface.text(field_1234, chip, x, y, color);
                }
            }

            /** The restore chip's tooltip: the action, the source zip, and the snapshot fate by zipOnResume. */
            private Component restoreTooltip(Path source) {
                String sourceName = source.getFileName().toString();
                if (!zipOnResume) {
                    return new TranslatableComponent("wdl.screen.downloads.tooltip.restore_no_backup", sourceName);
                }
                if (this.snapshotName == null) {
                    // A directory probe, and a row is rebuilt whenever a restore could have taken the next free name.
                    this.snapshotName = RestoreOperation.nextSnapshotName(savesDirectory, this.entry.folderName());
                }
                return new TranslatableComponent("wdl.screen.downloads.tooltip.restore", sourceName,
                        this.snapshotName);
            }

            /** The on-disk total once its walk lands; absent until then. */
            private OptionalLong effectiveSize() {
                Long walked = walkedSizes.get(this.folder);
                return walked != null ? OptionalLong.of(walked) : OptionalLong.empty();
            }

            private boolean inLine(int mouseX, int mouseY, int left, int right, int top) {
                return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= top + field_1234.lineHeight;
            }

            boolean handleEdgeClick(int mouseX, int mouseY) {
                if (inLine(mouseX, mouseY, this.arrowLeft, this.arrowRight, this.line2Top)) {
                    Util.getPlatform().openFile(this.folder.toFile()); // the per-row open-folder affordance
                    return true;
                }
                if (this.entry.health() == DownloadHealth.RECOVERABLE && this.recoverLeft >= 0
                        && inLine(mouseX, mouseY, this.recoverLeft, this.recoverRight, this.line2Top)) {
                    recover(this.entry);
                    return true;
                }
                if (this.entry.isTainted() && this.restoreLeft >= 0
                        && inLine(mouseX, mouseY, this.restoreLeft, this.restoreRight, this.line2Top)) {
                    restoreEntry(this.entry);
                    return true;
                }
                return false;
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                // The list treats a false return as a miss and skips the selection, so a body click returns true
                // to register the row and prefill through setSelected.
                DownloadList.this.setSelected(this);
                return true;
            }

            /**
             * The restore chip's live hit box, or null while the chip is absent. The live hit test treats both edges as
             * inclusive, so the width and height carry the extra pixel: every point inside the reported rectangle
             * clicks the chip.
             */
            @Nullable
            Rect2i restoreChipBox() {
                if (this.restoreLeft < 0) {
                    return null;
                }
                return new Rect2i(this.restoreLeft, this.line2Top,
                        this.restoreRight - this.restoreLeft + 1, field_1234.lineHeight + 1);
            }

            void close() {
                // removed() closes the icons when a confirm screen is pushed on top and init() closes them again on the
                // way back; the iconClosed guard skips the second close, which would double-free the GL texture. Close
                // does not release the location; the next init() re-registers it, evicting the stale entry.
                if (this.icon != null && !this.iconClosed) {
                    this.icon.close();
                    this.iconClosed = true;
                }
            }
        }
    }
}
