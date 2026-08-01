// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import com.mojang.blaze3d.platform.NativeImage;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import world.thearchive.wdl.Wdl;
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
import world.thearchive.wdl.core.export.RestoreOperation;
import world.thearchive.wdl.core.export.RestoreSource;
import world.thearchive.wdl.core.export.SizeFormatter;
import world.thearchive.wdl.core.report.DownloadCounts;
import world.thearchive.wdl.update.UpdateAvailable;
import world.thearchive.wdl.update.UpdateCheck;

/**
 * The download screen: start a new download with a typed name, or browse and resume/recover the wdl-managed downloads
 * already on disk. The vanilla world-select screen repurposed, built on vanilla widgets (no GUI library): an
 * {@link EditBox} name field, a primary Download/Resume button, a Done button, and an {@link ObjectSelectionList} of
 * rows fed the MC-free {@code core/browse} model. Loader-agnostic view code; the entry points (keybind, command,
 * pause-menu button) live per loader and route through {@link Wdl}, which decides per entry point whether the open runs
 * inline or is deferred to the next client tick.
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

    // Rows draw only icon + text; vanilla's AbstractSelectionList.renderItem paints the selection treatment.
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
    private @Nullable Button primaryButton;
    private @Nullable DownloadList list;
    private @Nullable DownloadEntry selectedEntry;
    private boolean suppressNameResponder;

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
        super(Component.translatable("wdl.screen.downloads.title"));
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
        String priorName = this.nameField != null ? this.nameField.getValue() : "";

        NameField field = new NameField(this.font, 0, 0, NAME_WIDTH, FIELD_HEIGHT,
                Component.translatable("wdl.screen.downloads.name"));
        field.setMaxLength(NAME_MAX_LENGTH);
        field.setValue(priorName);
        field.setResponder(this::onNameTyped);
        centerTopWidget(field);
        this.nameField = addRenderableWidget(field);

        int buttonRowY = field.getY() + field.getHeight() + 6;
        Component primaryLabel = this.selectedEntry != null ? resumeLabel() : downloadLabel();
        boolean primaryActive = this.selectedEntry != null || TargetResolver.hasUsableName(priorName);
        addButtonRow(buttonRowY, primaryLabel, button -> onPrimary(), primaryActive);
        setPrimaryActive(primaryActive);

        int listWidth = listBandWidth();
        int listX = (this.width - listWidth) / 2;
        int belowButtons = addCaptureWarning(buttonRowY + BUTTON_HEIGHT + 8);
        int headerY = Math.max(addUpdateBanner(belowButtons), TOP_Y + 50);
        int linkWidth = this.font.width(openSavesText()) + 8;
        int disclosureWidth = Math.max(listWidth - linkWidth - 4, 80);
        if (!this.entries.isEmpty()) {
            addRenderableWidget(new DisclosureWidget(listX, headerY, disclosureWidth));
            addRenderableWidget(new OpenSavesWidget(listX + listWidth - linkWidth, headerY, linkWidth));
        }

        int listTopY = headerY + 18;
        int availableHeight = Math.max(this.height - listTopY - LIST_BOTTOM_MARGIN, 2 * ITEM_HEIGHT + 8);
        int listHeight = Math.min(this.entries.size() * ITEM_HEIGHT + 8, availableHeight);
        DownloadList downloadList = new DownloadList(this.minecraft, listWidth, listHeight, listTopY, ITEM_HEIGHT);
        downloadList.setX(listX);
        downloadList.populate(this.entries);
        this.list = downloadList;
        if (!listCollapsed && !this.entries.isEmpty()) {
            addRenderableWidget(downloadList);
        }
        // removed() closes the scanner when a screen is pushed on top, so a re-open recreates it and lets the
        // visible rows re-submit; the walked overlay persists, so nothing already walked is re-walked.
        if (this.sizeScanner.isClosed()) {
            this.sizeScanner = new OnDiskSizeScanner();
            this.scheduledWalks.clear();
            this.scheduledProbes.clear();
        }
        setInitialFocus(field);
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
        Component labelText = Component.translatable("wdl.screen.downloads.downloading", name)
                .withColor(BrandColors.AMBER);
        StringWidget label = new StringWidget(this.font.width(labelText), FIELD_HEIGHT, labelText, this.font);
        centerTopWidget(label);
        addRenderableWidget(label);

        int buttonRowY = label.getY() + label.getHeight() + 6;
        boolean recording = state == CaptureState.RECORDING;
        // a finishing save shows a disabled Saving label, not an actionable Stop
        Button primary = addButtonRow(buttonRowY, recording ? stopLabel() : savingLabel(),
                button -> stopCapture(), recording);
        addUpdateBanner(buttonRowY + BUTTON_HEIGHT + 8);
        if (recording) {
            setInitialFocus(primary);
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
        Component labelText = (name != null
                ? Component.translatable("wdl.screen.downloads.restoring", name)
                : Component.translatable("wdl.screen.downloads.restoring_sweep"))
                        .withColor(BrandColors.AMBER);
        StringWidget label = new StringWidget(this.font.width(labelText), FIELD_HEIGHT, labelText, this.font);
        centerTopWidget(label);
        addRenderableWidget(label);

        int buttonRowY = label.getY() + label.getHeight() + 6;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds((this.width - BUTTON_WIDTH) / 2, buttonRowY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        addUpdateBanner(buttonRowY + BUTTON_HEIGHT + 8);
    }

    private void centerTopWidget(AbstractWidget widget) {
        GridLayout grid = new GridLayout();
        grid.addChild(widget, 0, 0, settings -> settings.padding(4, 4, 4, 4));
        grid.arrangeElements();
        FrameLayout.alignInRectangle(grid, 0, TOP_Y, this.width, this.height, 0.5f, 0.05f);
        grid.arrangeElements();
    }

    private Button addButtonRow(int buttonRowY, Component primaryLabel, Button.OnPress onPrimary,
            boolean primaryActive) {
        int total = BUTTON_WIDTH * 2 + BUTTON_GAP;
        int startX = (this.width - total) / 2;
        Button primary = Button.builder(primaryLabel, onPrimary)
                .bounds(startX, buttonRowY, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        primary.active = primaryActive;
        this.primaryButton = addRenderableWidget(primary);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(startX + BUTTON_WIDTH + BUTTON_GAP, buttonRowY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        return primary;
    }

    /** The centered band the existing-worlds list and the update banner both span. */
    private int listBandWidth() {
        return Mth.clamp(this.width - 20, 280, 600);
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
        UpdateAvailable update = updateCheck.available().orElseThrow();
        Component prose = Component.translatable("wdl.screen.downloads.update_available",
                update.runningDisplay(), update.latestDisplay());
        int maxBandWidth = listBandWidth();
        int modrinthWidth = this.font.width("Modrinth");
        int curseforgeWidth = this.font.width("CurseForge");
        int dismissWidth = this.font.width(DISMISS_GLYPH);
        int leftWidth = this.font.width(WARNING_GLYPH) + this.font.width(prose);
        // A row too wide for the list band drops the lower-priority CurseForge label rather than
        // overflowing the box.
        boolean curseforgeFits = leftWidth + BANNER_GAP + modrinthWidth + BANNER_GAP + curseforgeWidth
                + BANNER_GAP + dismissWidth <= maxBandWidth - 2 * BANNER_GAP;
        int innerWidth = leftWidth + BANNER_GAP + modrinthWidth + BANNER_GAP + dismissWidth
                + (curseforgeFits ? BANNER_GAP + curseforgeWidth : 0);
        // The edge padding is the one inter-item gap, so every distance across the row reads uniform.
        int bandWidth = Math.min(innerWidth + 2 * BANNER_GAP, maxBandWidth);
        int bandX = (this.width - bandWidth) / 2;
        addRenderableWidget(new BannerPanelWidget(bandX, y, bandWidth, prose));
        int x = bandX + BANNER_GAP + leftWidth + BANNER_GAP;
        addRenderableWidget(new BannerLinkWidget(x, y, modrinthWidth, "Modrinth",
                UpdateCheck.MODRINTH_PAGE_URL));
        if (curseforgeFits) {
            x += modrinthWidth + BANNER_GAP;
            addRenderableWidget(new BannerLinkWidget(x, y, curseforgeWidth, "CurseForge",
                    UpdateCheck.CURSEFORGE_PAGE_URL));
        }
        addRenderableWidget(new BannerDismissWidget(bandX + bandWidth - BANNER_GAP - dismissWidth, y,
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
        Component text = Component.translatable("wdl.screen.downloads.capture_disabled");
        int width = this.font.width(WARNING_GLYPH) + this.font.width(text);
        addRenderableWidget(new CaptureWarningWidget((this.width - width) / 2, y, width, text));
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
        return Component.translatable("wdl.screen.downloads.download");
    }

    private Component resumeLabel() {
        return Component.translatable("wdl.screen.downloads.resume");
    }

    private Component stopLabel() {
        return Component.translatable("wdl.screen.downloads.stop");
    }

    private Component savingLabel() {
        return Component.translatable("wdl.screen.downloads.saving");
    }

    private String openSavesText() {
        return Component.translatable("wdl.screen.downloads.open_saves").getString();
    }

    private void onNameTyped(String text) {
        if (suppressNameResponder) {
            return;
        }
        // Editing the name is a fresh download: drop any selected row and relabel the primary action.
        this.selectedEntry = null;
        if (this.primaryButton != null) {
            this.primaryButton.setMessage(downloadLabel());
        }
        setPrimaryActive(TargetResolver.hasUsableName(text));
    }

    /** Set the primary action's enabled state, explaining a disabled Download with the why tooltip. */
    private void setPrimaryActive(boolean active) {
        if (this.primaryButton == null) {
            return;
        }
        this.primaryButton.active = active;
        this.primaryButton.setTooltip(active ? null
                : Tooltip.create(Component.translatable("wdl.screen.downloads.download.tooltip")));
    }

    private void onRowSelected(DownloadEntry entry) {
        this.selectedEntry = entry;
        if (this.nameField != null) {
            suppressNameResponder = true;
            this.nameField.setValue(entry.folderName()); // resume targets the folder verbatim
            this.nameField.moveCursorToEnd(false); // a long prefilled name shows its end
            suppressNameResponder = false;
            setFocused(this.nameField);
        }
        if (this.primaryButton != null) {
            this.primaryButton.setMessage(resumeLabel());
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
            case REFUSE_LOADED -> refuseLoadedWorld();
            // Merging into an existing folder is a resume: a RESUME target takes the pre-merge backup and keeps
            // the world's own level.dat name, where a NEW target would skip the pre-merge backup.
            case RESUME_EXISTING -> resumeFolder(target.folderName(), true);
            case NEW -> start(target);
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
        if (source.isEmpty()) {
            this.onRefusal.accept(ToastCopy.restoreRefusedSourceChanged());
            return;
        }
        RestoreSource pinned = source.get();
        if (this.minecraft != null) {
            this.minecraft.setScreen(ResumeConfirm.createRestore("wdl.screen.downloads.confirm_restore",
                    entry.folderName(), pinned.zip().getFileName().toString(), this.zipOnResume,
                    () -> {
                        Wdl.launchRestore(this.savesDirectory, entry.folderName(), pinned);
                        this.minecraft.setScreen(this);
                    },
                    () -> this.minecraft.setScreen(this)));
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
            if (source.isPresent() && this.minecraft != null) {
                RestoreSource pinned = source.get();
                this.minecraft.setScreen(ResumeConfirm.createRestore(
                        "wdl.screen.downloads.confirm_restore_blocked",
                        folderName, pinned.zip().getFileName().toString(), this.zipOnResume,
                        () -> {
                            Wdl.launchRestore(this.savesDirectory, folderName, pinned);
                            this.minecraft.setScreen(this);
                        },
                        () -> this.minecraft.setScreen(this)));
                return;
            }
            refuseTainted();
            return;
        }
        boolean mismatch = MapManifest.schemeMismatch(this.savesDirectory.resolve(folderName), this.remapMapIds);
        if (decision == SinglePlayerTaint.Decision.CONFIRM) {
            DownloadTarget target = TargetResolver.resolveResume(folderName, this.savesDirectory);
            if (this.minecraft == null) {
                return;
            }
            boolean backupHere = this.zipOnResume && !mismatch;
            Runnable onContinue = () -> gateMapIdMismatch(folderName, mismatch, this.zipOnResume,
                    () -> start(target));
            Runnable onCancel = () -> this.minecraft.setScreen(this);
            Optional<RestoreSource> source = taint == SinglePlayerTaint.TaintState.TAINTED
                    ? RestoreSource.find(this.savesDirectory, folderName)
                    : Optional.empty();
            this.minecraft.setScreen(source.isPresent()
                    ? ResumeConfirm.createTaintedRestorable(folderName,
                            source.get().zip().getFileName().toString(), backupHere, onContinue, onCancel)
                    : ResumeConfirm.create("wdl.screen.downloads.confirm_tainted", folderName, backupHere,
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
        if (this.minecraft != null) {
            this.minecraft.setScreen(ResumeConfirm.create("wdl.screen.downloads.confirm_map_id_mismatch",
                    folderName, backupHere,
                    proceed,
                    () -> this.minecraft.setScreen(this)));
        }
    }

    private void refuseTainted() {
        this.onRefusal.accept(ToastCopy.refuseTainted());
    }

    private void confirmThenStart(DownloadTarget target, String folderName) {
        if (this.minecraft == null) {
            return;
        }
        if (!this.confirmResume) {
            start(target); // continue silently; the backup is separate, still governed by zipOnResume
            return;
        }
        this.minecraft.setScreen(ResumeConfirm.create("wdl.screen.downloads.merge",
                folderName, this.zipOnResume,
                () -> start(target),
                () -> this.minecraft.setScreen(this))); // cancel returns here, typed name preserved, no backup
    }

    private void start(DownloadTarget target) {
        this.onStart.accept(target);
        if (this.minecraft != null) {
            this.minecraft.setScreen(null); // back to the game; the capture runs
        }
    }

    /** Request that the running capture stop; tick() swaps to the saving widget set once the state flips. */
    private void stopCapture() {
        this.onStop.run();
    }

    /** The "Existing Worlds (N)" disclosure: a focusable, narratable header whose whole row toggles the list. */
    private final class DisclosureWidget extends AbstractWidget {
        DisclosureWidget(int x, int y, int width) {
            super(x, y, width, HEADER_ROW_HEIGHT, Component.translatable("wdl.screen.downloads.existing",
                    entries.size()));
        }

        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            String triangle = listCollapsed ? TRIANGLE_COLLAPSED : TRIANGLE_EXPANDED;
            Component header = Component.literal(triangle)
                    .append(Component.translatable("wdl.screen.downloads.existing", entries.size()));
            guiGraphics.drawString(font, header, getX() + 4, getY() + (getHeight() - font.lineHeight) / 2,
                    HEADER_ARGB);
        }

        @Override
        public void onClick(MouseButtonEvent mouseButtonEvent, boolean doubleClick) {
            toggleCollapsed();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            defaultButtonNarrationText(narrationElementOutput);
        }
    }

    /** The "Open Saves Folder" link: a focusable, narratable control that opens the saves directory. */
    private final class OpenSavesWidget extends AbstractWidget {
        OpenSavesWidget(int x, int y, int width) {
            super(x, y, width, HEADER_ROW_HEIGHT, Component.translatable("wdl.screen.downloads.open_saves"));
        }

        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            int color = isHovered() ? LINK_HOVER_ARGB : LINK_REST_ARGB;
            guiGraphics.drawString(font, openSavesText(), getX() + 4,
                    getY() + (getHeight() - font.lineHeight) / 2, color);
        }

        @Override
        public void onClick(MouseButtonEvent mouseButtonEvent, boolean doubleClick) {
            Util.getPlatform().openPath(savesDirectory);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            defaultButtonNarrationText(narrationElementOutput);
        }
    }

    /** The banner's bordered band: an amber outline over a subtle panel, with the glyph and prose inside. */
    private final class BannerPanelWidget extends AbstractWidget {
        private final Component prose;

        BannerPanelWidget(int x, int y, int width, Component prose) {
            super(x, y, width, BANNER_HEIGHT, prose);
            this.prose = prose;
            this.active = false; // decorative and inert, like a vanilla StringWidget
        }

        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            guiGraphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), BANNER_FILL_ARGB);
            guiGraphics.renderOutline(getX(), getY(), getWidth(), getHeight(), BANNER_OUTLINE_ARGB);
            int textY = getY() + (getHeight() - font.lineHeight) / 2;
            guiGraphics.drawString(font, WARNING_GLYPH, getX() + BANNER_GAP, textY, BANNER_GLYPH_ARGB);
            guiGraphics.drawString(font, this.prose, getX() + BANNER_GAP + font.width(WARNING_GLYPH), textY,
                    BANNER_TEXT_ARGB);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {}
    }

    /** One named banner link: an underlined teal label opening its release page in the OS browser. */
    private final class BannerLinkWidget extends AbstractWidget {
        private final Component label;
        private final String url;

        BannerLinkWidget(int x, int y, int width, String label, String url) {
            super(x, y, width, BANNER_HEIGHT, Component.literal(label));
            this.label = Component.literal(label).withStyle(ChatFormatting.UNDERLINE);
            this.url = url;
            setTooltip(Tooltip.create(Component.literal(url)));
        }

        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            int color = isHovered() ? BANNER_LINK_HOVER_ARGB : BANNER_LINK_ARGB;
            guiGraphics.drawString(font, this.label, getX(), getY() + (getHeight() - font.lineHeight) / 2, color);
        }

        @Override
        public void onClick(MouseButtonEvent mouseButtonEvent, boolean doubleClick) {
            Util.getPlatform().openUri(this.url);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            defaultButtonNarrationText(narrationElementOutput);
        }
    }

    /** The banner's dismiss control: hides the banner for the rest of the launch. */
    private final class BannerDismissWidget extends AbstractWidget {
        BannerDismissWidget(int x, int y, int width) {
            super(x, y, width, BANNER_HEIGHT,
                    Component.translatable("wdl.screen.downloads.update_dismiss"));
        }

        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            int color = isHovered() ? LINK_HOVER_ARGB : LINK_REST_ARGB;
            // The glyph comes from the fallback font, whose ink sits high in its line box, so the shared
            // centering formula reads a pixel high without the nudge.
            guiGraphics.drawString(font, DISMISS_GLYPH, getX(),
                    getY() + (getHeight() - font.lineHeight) / 2 + 1, color);
        }

        @Override
        public void onClick(MouseButtonEvent mouseButtonEvent, boolean doubleClick) {
            Wdl.updateCheck().dismissBanner();
            rebuildWidgets();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            defaultButtonNarrationText(narrationElementOutput);
        }
    }

    /** The passive capture-disabled caution: an inert amber glyph and label with an explanatory tooltip. */
    private final class CaptureWarningWidget extends AbstractWidget {
        private final Component text;

        CaptureWarningWidget(int x, int y, int width, Component text) {
            super(x, y, width, HEADER_ROW_HEIGHT, text);
            this.text = text;
            this.active = false; // a passive indicator, like the update banner's panel, not a control
            setTooltip(Tooltip.create(Component.translatable("wdl.screen.downloads.capture_disabled.tooltip")));
        }

        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            int textY = getY() + (getHeight() - font.lineHeight) / 2;
            guiGraphics.drawString(font, WARNING_GLYPH, getX(), textY, BANNER_GLYPH_ARGB);
            guiGraphics.drawString(font, this.text, getX() + font.width(WARNING_GLYPH), textY, NAME_ARGB);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {}
    }

    private void toggleCollapsed() {
        listCollapsed = !listCollapsed;
        if (this.list == null) {
            return;
        }
        if (listCollapsed) {
            removeWidget(this.list);
        } else {
            addRenderableWidget(this.list);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
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
    public void removed() {
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
    public @Nullable ScreenRectangle restoreChipBox(String folderName) {
        DownloadList downloadList = this.list;
        if (downloadList == null) {
            return null;
        }
        for (DownloadList.Row row : downloadList.children()) {
            if (row.entry.folderName().equals(folderName)) {
                return row.restoreChipBox();
            }
        }
        return null;
    }

    /** The name field; Enter submits the primary action (download or resume) only while the button is enabled. */
    private final class NameField extends EditBox {
        NameField(Font font, int x, int y, int width, int height, Component message) {
            super(font, x, y, width, height, message);
        }

        @Override
        public boolean keyPressed(KeyEvent keyEvent) {
            if (keyEvent.key() == GLFW.GLFW_KEY_ENTER || keyEvent.key() == GLFW.GLFW_KEY_KP_ENTER) {
                if (primaryButton != null && primaryButton.active) {
                    onPrimary();
                    return true;
                }
            }
            return super.keyPressed(keyEvent);
        }
    }

    /** The row list: a vanilla selection list whose selection prefills the name field. */
    private final class DownloadList extends ObjectSelectionList<DownloadList.Row> {
        private boolean suppressPrefill;

        DownloadList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        @Override
        public int getRowWidth() {
            return this.width - 14; // scale rows to the (centered) list band, less the scrollbar gutter
        }

        void populate(List<DownloadEntry> rows) {
            for (DownloadEntry entry : rows) {
                addEntry(new Row(entry));
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean doubleClick) {
            // An arrow / Recover click highlights the row like a body click, but does not prefill the field.
            Row row = getEntryAtPosition(mouseButtonEvent.x(), mouseButtonEvent.y());
            if (row != null && row.handleEdgeClick((int) mouseButtonEvent.x(), (int) mouseButtonEvent.y())) {
                this.suppressPrefill = true;
                setSelected(row);
                this.suppressPrefill = false;
                return true;
            }
            return super.mouseClicked(mouseButtonEvent, doubleClick);
        }

        @Override
        public void setSelected(@Nullable Row entry) {
            super.setSelected(entry);
            if (entry != null && !this.suppressPrefill) { // a body click prefills; an edge click only highlights
                onRowSelected(entry.entry);
            }
        }

        void closeIcons() {
            for (Row row : children()) {
                row.close();
            }
        }

        /** One download row: icon, name, last-played date, and either the summary plus size or a Recover chip. */
        final class Row extends ObjectSelectionList.Entry<Row> {
            private final DownloadEntry entry;
            private final String displayName;
            private final String lastPlayed;
            private final Path folder;
            private final @Nullable FaviconTexture icon;
            private int line2Top;
            private int arrowLeft;
            private int arrowRight;
            private int recoverLeft = -1;
            private int recoverRight = -1;
            private int restoreLeft = -1;
            private int restoreRight = -1;

            Row(DownloadEntry entry) {
                this.entry = entry;
                // Strip legacy section codes so a hostile recorded name cannot color or obfuscate the row.
                this.displayName = ChatFormatting.stripFormatting(entry.displayName());
                this.lastPlayed = dateFormat.format(Instant.ofEpochMilli(entry.lastPlayedEpochMillis()));
                this.folder = savesDirectory.resolve(entry.folderName());
                this.icon = loadIcon(entry);
            }

            private @Nullable FaviconTexture loadIcon(DownloadEntry entry) {
                byte[] bytes = entry.iconBytes();
                if (bytes == null || minecraft == null) {
                    return null;
                }
                try {
                    FaviconTexture texture = FaviconTexture.forWorld(minecraft.getTextureManager(),
                            entry.folderName());
                    texture.upload(NativeImage.read(bytes));
                    return texture;
                } catch (IOException | RuntimeException e) {
                    return null; // a validated-but-undecodable icon is dropped, not fatal
                }
            }

            @Override
            public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovering,
                    float partialTick) {
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
                if (this.icon != null) {
                    guiGraphics.blit(RenderPipelines.GUI_TEXTURED, this.icon.textureLocation(), rowX + 2, rowY + 3,
                            0.0F, 0.0F, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
                }
                int textX = rowX + 4 + ICON_ADVANCE;
                int dateX = rightEdge - font.width(this.lastPlayed);
                guiGraphics.drawString(font, this.lastPlayed, dateX, rowY + 4, GRAY_ARGB);
                int labelMax = entryWidth - font.width(this.lastPlayed) - 12 - ICON_ADVANCE;
                guiGraphics.drawString(font, ClientText.ellipsize(font, this.displayName, labelMax),
                        textX, rowY + 4, NAME_ARGB);

                this.line2Top = rowY + 14;
                int slotLeft = renderStatus(guiGraphics, rightEdge, this.line2Top, mouseX, mouseY, hovering);
                renderSummary(guiGraphics, textX, this.line2Top, slotLeft - 4);
            }

            private void renderSummary(GuiGraphics guiGraphics, int textX, int y, int rightLimit) {
                DownloadCounts counts = this.entry.counts();
                if (counts == null) {
                    return; // a recoverable download has no trustworthy summary
                }
                int pairX = textX;
                boolean any = false;
                if (counts.chunks() != 0) {
                    pairX = cell(guiGraphics, pairX, y, "wdl.screen.downloads.summary.chunks", counts.chunks(),
                            false, rightLimit);
                    any = true;
                }
                if (this.entry.isChunksOnly()) {
                    // a resume knows the cumulative chunk total but no cumulative entity or container count;
                    // mark those two not applicable rather than show a session number beside cumulative chunks
                    pairX = cell(guiGraphics, pairX, y, "wdl.screen.downloads.summary.entities", SUMMARY_ABSENT,
                            any, rightLimit);
                    cell(guiGraphics, pairX, y, "wdl.screen.downloads.summary.containers", SUMMARY_ABSENT,
                            true, rightLimit);
                    return;
                }
                pairX = cell(guiGraphics, pairX, y, "wdl.screen.downloads.summary.entities", counts.entities(),
                        any, rightLimit);
                cell(guiGraphics, pairX, y, "wdl.screen.downloads.summary.containers", counts.containers(),
                        true, rightLimit);
            }

            /**
             * One summary cell clamped at {@code rightLimit} (the slot's left edge): a piece that crosses the limit is
             * ellipsized and freezes the cursor there, so the cells that follow draw nothing.
             */
            private int cell(GuiGraphics guiGraphics, int pairX, int y, String labelKey, int value,
                    boolean separator, int rightLimit) {
                return cell(guiGraphics, pairX, y, labelKey, Integer.toString(value), separator, rightLimit);
            }

            private int cell(GuiGraphics guiGraphics, int pairX, int y, String labelKey, String text,
                    boolean separator, int rightLimit) {
                if (pairX >= rightLimit) {
                    return rightLimit;
                }
                if (separator) {
                    if (pairX + 6 + font.width(DOT) + 6 >= rightLimit) {
                        return rightLimit;
                    }
                    guiGraphics.drawString(font, DOT, pairX + 6, y, GRAY_ARGB);
                    pairX += 6 + font.width(DOT) + 6;
                }
                String label = Component.translatable(labelKey).getString();
                String clampedLabel = ClientText.ellipsize(font, label, rightLimit - pairX);
                guiGraphics.drawString(font, clampedLabel, pairX, y, GRAY_ARGB);
                if (!clampedLabel.equals(label)) {
                    return rightLimit;
                }
                int valueX = pairX + font.width(label) + 4;
                if (valueX >= rightLimit) {
                    return rightLimit;
                }
                String clampedText = ClientText.ellipsize(font, text, rightLimit - valueX);
                guiGraphics.drawString(font, clampedText, valueX, y, GRAY_ARGB);
                if (!clampedText.equals(text)) {
                    return rightLimit;
                }
                return valueX + font.width(text);
            }

            /**
             * Draw the arrow and the slot content, returning the slot content's left edge (the arrow's when the slot is
             * empty) so the summary can clamp against it.
             */
            private int renderStatus(GuiGraphics guiGraphics, int rightEdge, int y, int mouseX, int mouseY,
                    boolean hovering) {
                this.arrowLeft = rightEdge - font.width(ARROW);
                this.arrowRight = rightEdge;
                this.recoverLeft = -1;
                this.recoverRight = -1;
                this.restoreLeft = -1;
                this.restoreRight = -1;
                boolean overArrow = hovering && inLine(mouseX, mouseY, this.arrowLeft, this.arrowRight, y);
                guiGraphics.drawString(font, ARROW, this.arrowLeft, y, overArrow ? LINK_HOVER_ARGB : LINK_REST_ARGB);

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
                        String restoreChip = Component.translatable("wdl.screen.downloads.restore").getString();
                        this.restoreLeft = slotRight - font.width(restoreChip);
                        this.restoreRight = slotRight;
                        boolean overRestore = hovering
                                && inLine(mouseX, mouseY, this.restoreLeft, this.restoreRight, y);
                        drawRestoreChip(guiGraphics, restoreChip, this.restoreLeft, y,
                                overRestore ? LINK_HOVER_ARGB : RECOVER_ARGB);
                        if (overRestore) {
                            guiGraphics.setTooltipForNextFrame(font,
                                    font.split(restoreTooltip(source), TOOLTIP_WRAP_WIDTH), mouseX, mouseY);
                        }
                        slotRight = this.restoreLeft - 4;
                    }
                    String chip = Component.translatable("wdl.screen.downloads.tainted").getString();
                    int chipLeft = slotRight - font.width(chip);
                    guiGraphics.drawString(font, chip, chipLeft, y, TAINTED_ARGB);
                    if (hovering && inLine(mouseX, mouseY, chipLeft, slotRight, y)) {
                        // With the Restore chip present the tooltip drops the fresh-download advice: the
                        // chip beside it is the better way out.
                        guiGraphics.setTooltipForNextFrame(font, font.split(
                                Component.translatable(restorable
                                        ? "wdl.screen.downloads.tooltip.tainted_restorable"
                                        : "wdl.screen.downloads.tooltip.tainted"),
                                TOOLTIP_WRAP_WIDTH),
                                mouseX, mouseY);
                    }
                    slotLeft = chipLeft;
                } else if (this.entry.health() == DownloadHealth.RECOVERABLE) {
                    String chip = Component.translatable("wdl.screen.downloads.recover").getString();
                    this.recoverLeft = slotRight - font.width(chip);
                    this.recoverRight = slotRight;
                    boolean overRecover = hovering && inLine(mouseX, mouseY, this.recoverLeft, this.recoverRight, y);
                    guiGraphics.drawString(font, chip, this.recoverLeft, y,
                            overRecover ? LINK_HOVER_ARGB : RECOVER_ARGB);
                    slotLeft = this.recoverLeft;
                } else if (this.entry.health() == DownloadHealth.PARTIAL) {
                    String chip = Component.translatable("wdl.screen.downloads.partial").getString();
                    int chipLeft = slotRight - font.width(chip);
                    guiGraphics.drawString(font, chip, chipLeft, y, PARTIAL_ARGB);
                    if (hovering && inLine(mouseX, mouseY, chipLeft, slotRight, y)) {
                        guiGraphics.setTooltipForNextFrame(font, font.split(
                                Component.translatable("wdl.toast.partial.title"), TOOLTIP_WRAP_WIDTH),
                                mouseX, mouseY);
                    }
                    slotLeft = chipLeft;
                } else {
                    OptionalLong size = effectiveSize();
                    if (size.isPresent()) {
                        SizeFormatter.Size formatted = SizeFormatter.format(size.getAsLong());
                        Component text = Component.translatable(formatted.unitKey(), formatted.number());
                        guiGraphics.drawString(font, text, slotRight - font.width(text), y, GRAY_ARGB);
                        slotLeft = slotRight - font.width(text);
                    }
                }

                if (overArrow) {
                    guiGraphics.setTooltipForNextFrame(font, List.of(
                            Component.translatable("wdl.screen.downloads.tooltip.folder", entry.folderName()),
                            Component.translatable("wdl.screen.downloads.tooltip.version", modVersion, mcVersion)),
                            Optional.empty(), mouseX, mouseY);
                }
                return slotLeft;
            }

            /**
             * The restore glyph comes from the fallback font, whose ink sits high in its line box, so the glyph alone
             * is drawn a pixel lower while the label stays on the row baseline.
             */
            private void drawRestoreChip(GuiGraphics guiGraphics, String chip, int x, int y, int color) {
                if (chip.startsWith(RESTORE_GLYPH)) {
                    guiGraphics.drawString(font, RESTORE_GLYPH, x, y + 1, color);
                    guiGraphics.drawString(font, chip.substring(RESTORE_GLYPH.length()),
                            x + font.width(RESTORE_GLYPH), y, color);
                } else {
                    guiGraphics.drawString(font, chip, x, y, color);
                }
            }

            /** The restore chip's tooltip: the action, the source zip, and the snapshot fate by zipOnResume. */
            private Component restoreTooltip(Path source) {
                String sourceName = source.getFileName().toString();
                return zipOnResume
                        ? Component.translatable("wdl.screen.downloads.tooltip.restore", sourceName,
                                ResumeConfirm.snapshotZipName(this.entry.folderName()))
                        : Component.translatable("wdl.screen.downloads.tooltip.restore_no_backup", sourceName);
            }

            /** The on-disk total once its walk lands; absent until then. */
            private OptionalLong effectiveSize() {
                Long walked = walkedSizes.get(this.folder);
                return walked != null ? OptionalLong.of(walked) : OptionalLong.empty();
            }

            private boolean inLine(int mouseX, int mouseY, int left, int right, int top) {
                return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= top + font.lineHeight;
            }

            boolean handleEdgeClick(int mouseX, int mouseY) {
                if (inLine(mouseX, mouseY, this.arrowLeft, this.arrowRight, this.line2Top)) {
                    Util.getPlatform().openPath(this.folder); // the per-row open-folder affordance
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

            /**
             * The restore chip's live hit box, or null while the chip is absent. The live hit test treats both edges as
             * inclusive, so the width and height carry the extra pixel: every point inside the reported rectangle
             * clicks the chip.
             */
            @Nullable
            ScreenRectangle restoreChipBox() {
                if (this.restoreLeft < 0) {
                    return null;
                }
                return new ScreenRectangle(this.restoreLeft, this.line2Top,
                        this.restoreRight - this.restoreLeft + 1, font.lineHeight + 1);
            }

            void close() {
                // removed() closes the icons when a confirm screen is pushed on top, then init() closes them again
                // on the way back; FaviconTexture.close throws if already closed, so skip a second close.
                if (this.icon != null && !this.icon.isClosed()) {
                    this.icon.close();
                }
            }

            @Override
            public Component getNarration() {
                return Component.translatable("wdl.screen.downloads.narration", this.displayName, this.lastPlayed);
            }
        }
    }
}
