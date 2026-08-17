// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.RenderSurface;
import world.thearchive.wdl.adapter.impl.RenderSurfaceImpl;
import world.thearchive.wdl.core.BrandColors;
import world.thearchive.wdl.core.CaptureToggleGuard;
import world.thearchive.wdl.core.ConfigOption;
import world.thearchive.wdl.core.ConfigSchema;
import world.thearchive.wdl.core.CuratedGameRule;
import world.thearchive.wdl.core.HudAnchor;
import world.thearchive.wdl.core.HudPeekMode;
import world.thearchive.wdl.core.MarkerHue;
import world.thearchive.wdl.core.RecaptureMode;
import world.thearchive.wdl.core.SettingsDraft;
import world.thearchive.wdl.core.SettingsLayout;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.core.WorldType;

/**
 * The in-mod settings screen: a tabbed, staged editor over the MC-free config model. Each descriptor value-type becomes
 * a vanilla widget (boolean/game-rule toggle, ranged slider, enum/marker cycle, seed/hex field), built from the public
 * widget primitives rather than vanilla's package-private option wrapper so the feature needs no access-widener and no
 * mixin. Edits stage into a {@link SettingsDraft} held on this instance (surviving a resize rebuild); Done and Esc
 * commit, Discard abandons behind a confirm, and disabling a core capture toggle confirms at the change. The rows,
 * their order, and the gamerule group come from {@link SettingsLayout}.
 */
public final class WdlSettingsScreen extends Screen {
    private static final int TAB_TOP = 6;
    private static final int TAB_HEIGHT = 20;
    private static final int ROW_BAND_MAX = 345;
    private static final int LIST_TOP = TAB_TOP + TAB_HEIGHT + 6;
    private static final int FOOTER_HEIGHT = 32;
    private static final int ROW_HEIGHT = 24;
    private static final int CONTROL_WIDTH = 100;
    private static final int CONTROL_HEIGHT = 20;
    private static final int REVERT_WIDTH = 20;
    private static final int GAP = 4;
    private static final String CAPTURE_WARNING_GLYPH = "⚠";
    private static final String REVERT_ICON = "revert";
    private static final int REVERT_ICON_WIDTH = 10;
    private static final int REVERT_ICON_HEIGHT = 10;

    private final @Nullable Screen parent;
    private final SettingsDraft draft;
    private final Consumer<WdlConfig> onSave;
    private final Map<String, CuratedGameRule> curatedById;
    private final Predicate<String> modLoaded;

    private int activeTab;
    private @Nullable Button defaults;

    public WdlSettingsScreen(@Nullable Screen parent, WdlConfig live, Consumer<WdlConfig> onSave,
            List<CuratedGameRule> curatedGameRules, Predicate<String> modLoaded) {
        super(Component.translatable("wdl.settings.title"));
        this.parent = parent;
        this.draft = SettingsDraft.of(live);
        this.onSave = onSave;
        this.curatedById = index(curatedGameRules);
        this.modLoaded = modLoaded;
    }

    private static Map<String, CuratedGameRule> index(List<CuratedGameRule> rules) {
        Map<String, CuratedGameRule> byId = new HashMap<>();
        for (CuratedGameRule rule : rules) {
            byId.put(rule.id(), rule);
        }
        return byId;
    }

    @Override
    protected void init() {
        int listHeight = Math.max(this.height - LIST_TOP - FOOTER_HEIGHT, ROW_HEIGHT * 3);
        SettingsList settingsList = new SettingsList(Minecraft.getInstance(), this.width, listHeight, LIST_TOP,
                ROW_HEIGHT);
        populate(settingsList);
        addRenderableWidget(settingsList);

        buildTabStrip();
        buildFooter();
    }

    private void buildTabStrip() {
        int count = SettingsLayout.TABS.size();
        ColumnBounds column = optionColumn();
        int tabWidth = column.width() / count;
        for (int i = 0; i < count; i++) {
            int index = i;
            int tabX = column.left() + i * tabWidth;
            // the last tab absorbs the division remainder so the strip's right edge meets the control edge
            int thisTabWidth = i == count - 1 ? column.right() - tabX : tabWidth;
            Component title = Component.translatable(SettingsLayout.tabLabelKey(SettingsLayout.TABS.get(i).id()));
            Button tab = Button.builder(title, button -> selectTab(index))
                    .bounds(tabX, TAB_TOP, thisTabWidth, TAB_HEIGHT).build();
            tab.active = i != this.activeTab; // the active tab reads as pressed by being inert
            addRenderableWidget(tab);
        }
    }

    /** The option content band width, the single source the tab strip, footer, and list rows all size to. */
    private int contentBandWidth() {
        return Math.min(this.width - 20, ROW_BAND_MAX);
    }

    /**
     * The visible option column the tab strip and footer both bracket: labels start at the row content inset, and the
     * right-anchored control stops a revert-affordance slot short of the band edge, so neither spans the raw band, only
     * the column between them. Matching the list's own width/2 - band/2 origin keeps them aligned.
     */
    private ColumnBounds optionColumn() {
        int band = contentBandWidth();
        int left = this.width / 2 - band / 2 + 2;
        int right = left + (band - 4) - REVERT_WIDTH - GAP;
        return new ColumnBounds(left, right);
    }

    /** The visible option column's horizontal bounds. */
    private record ColumnBounds(int left, int right) {
        int width() {
            return this.right - this.left;
        }
    }

    private void buildFooter() {
        ColumnBounds column = optionColumn();
        int buttonWidth = (column.width() - 2 * GAP) / 3;
        int footerY = this.height - 26;
        int doneX = column.left();
        int defaultsX = doneX + buttonWidth + GAP;
        int discardX = defaultsX + buttonWidth + GAP;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(doneX, footerY, buttonWidth, 20).build());
        this.defaults = addRenderableWidget(Button.builder(Component.translatable("wdl.settings.defaults"),
                button -> onDefaults()).bounds(defaultsX, footerY, buttonWidth, 20).build());
        // the last button absorbs the division remainder so the footer's right edge meets the control edge
        addRenderableWidget(Button.builder(Component.translatable("wdl.settings.discard"), button -> onDiscard())
                .bounds(discardX, footerY, column.right() - discardX, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        // The Defaults button would only revert to a state the draft is already in, so it grays out (never
        // hides, keeping the footer stable and the feature discoverable) while the draft equals defaults, and
        // a tooltip on the gray state says why. Kept current with live edits by resolving before the widgets
        // draw; the equality check gates the write so the tooltip is rebuilt only when the state flips.
        if (this.defaults != null) {
            boolean atDefaults = this.draft.isAtDefaults();
            if (this.defaults.active == atDefaults) {
                this.defaults.active = !atDefaults;
                this.defaults.setTooltip(atDefaults
                        ? Tooltip.create(Component.translatable("wdl.settings.defaults.tooltip"))
                        : null);
            }
        }
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void selectTab(int index) {
        this.activeTab = index;
        rebuildWidgets();
    }

    private void populate(SettingsList settingsList) {
        SettingsLayout.Tab tab = SettingsLayout.TABS.get(this.activeTab);
        for (SettingsLayout.Section section : tab.sections()) {
            List<String> visibleKeys = new ArrayList<>();
            for (String key : section.optionKeys()) {
                if (isRowVisible(key)) {
                    visibleKeys.add(key);
                }
            }
            boolean hasGameRuleRows = section.isGameRuleGroup() && !this.curatedById.isEmpty();
            if (visibleKeys.isEmpty() && !hasGameRuleRows) {
                continue; // an install-gated section with nothing left to show drops its header too
            }
            if (section.labelKey() != null) {
                settingsList.add(new HeaderRow(section.labelKey()));
            }
            for (String key : visibleKeys) {
                Control control = buildControl(key);
                applyTooltip(control.widget(), key);
                settingsList.add(new OptionRow(key, control.widget(), control.refresh(),
                        SettingsLayout.masterKey(key)));
            }
            if (section.isGameRuleGroup()) {
                for (String id : SettingsLayout.GAME_RULE_ORDER) {
                    CuratedGameRule rule = this.curatedById.get(id);
                    if (rule != null) {
                        settingsList.add(new GameRuleRow(rule));
                    }
                }
            }
        }
    }

    /** Whether {@code key}'s row shows: a row that names required mods appears only when one of them is loaded. */
    private boolean isRowVisible(String key) {
        Set<String> required = SettingsLayout.requiredMods(key);
        if (!required.isEmpty() && required.stream().noneMatch(this.modLoaded)) {
            return false;
        }
        return true;
    }

    private Control buildControl(String key) {
        ConfigOption option = ConfigSchema.option(key);
        switch (option.type()) {
            case BOOLEAN:
                CycleButton<Boolean> toggle = CycleButton.onOffBuilder(this.draft.getBoolean(key))
                        .displayOnlyValue()
                        .create(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT, Component.empty(),
                                (button, value) -> onBoolChange(key, value));
                return new Control(toggle, () -> toggle.setValue(this.draft.getBoolean(key)));
            case INTEGER:
                IntSlider intSlider = new IntSlider(key, (int) option.min(), (int) option.max());
                return new Control(intSlider, intSlider::syncFromDraft);
            case FLOAT:
                FloatSlider floatSlider = new FloatSlider(key, (float) option.min(), (float) option.max());
                return new Control(floatSlider, floatSlider::syncFromDraft);
            case ENUM:
                return enumControl(key);
            case LONG:
                return textControl(key);
            default:
                throw new IllegalStateException("no settings widget for " + option.type() + " (" + key + ")");
        }
    }

    private Control enumControl(String key) {
        switch (key) {
            case "hudAnchor":
                return cycleControl(key, HudAnchor.class, List.of(HudAnchor.values()),
                        this.draft.getEnum(key, HudAnchor.class));
            case "hudPeekMode":
                return cycleControl(key, HudPeekMode.class, List.of(HudPeekMode.values()),
                        this.draft.getEnum(key, HudPeekMode.class));
            case "worldType":
                return cycleControl(key, WorldType.class, List.of(WorldType.values()),
                        this.draft.getEnum(key, WorldType.class));
            case "recaptureChunks":
                return recaptureControl(key);
            case "unscannedColor":
                return markerControl(key, MarkerHue.RED);
            case "recoveredColor":
                return markerControl(key, MarkerHue.VIOLET);
            case "overlayCoveredColor":
                return markerControl(key, MarkerHue.TEAL);
            case "overlaySuspectColor":
                return markerControl(key, MarkerHue.AMBER);
            default:
                throw new IllegalStateException("unmapped enum option " + key);
        }
    }

    private Control markerControl(String key, MarkerHue brandDefault) {
        MarkerHue current = this.draft.getEnum(key, MarkerHue.class);
        List<MarkerHue> values = new ArrayList<>(MarkerHue.presetCycle(brandDefault));
        if (!values.contains(current)) {
            values.add(0, current); // a hand-set out-of-cycle hue shows as the current step, then cycles into preset
        }
        return cycleControl(key, MarkerHue.class, values, current);
    }

    private <E extends Enum<E>> Control cycleControl(String key, Class<E> type, List<E> values, E initial) {
        CycleButton<E> cycle = CycleButton.<E>builder(value -> Component.translatable(valueLabelKey(value)), initial)
                .withValues(values)
                .displayOnlyValue()
                .create(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT, Component.empty(),
                        (button, value) -> this.draft.set(key, value.name()));
        return new Control(cycle, () -> cycle.setValue(this.draft.getEnum(key, type)));
    }

    private Control recaptureControl(String key) {
        CycleButton<RecaptureMode> cycle = CycleButton.<RecaptureMode>builder(
                value -> Component.translatable(valueLabelKey(value)), this.draft.getEnum(key, RecaptureMode.class))
                .withValues(List.of(RecaptureMode.values()))
                .displayOnlyValue()
                .create(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT, Component.empty(),
                        (button, value) -> onRecaptureChange(key, value));
        return new Control(cycle, () -> cycle.setValue(this.draft.getEnum(key, RecaptureMode.class)));
    }

    private void onRecaptureChange(String key, RecaptureMode value) {
        RecaptureMode current = this.draft.getEnum(key, RecaptureMode.class);
        if (CaptureToggleGuard.whenReducingRecapture(current, value) != CaptureToggleGuard.NONE) {
            showReduceRecaptureConfirm(key, value);
        } else {
            this.draft.set(key, value.name());
        }
    }

    /**
     * The confirm for reducing recapture freshness (the enum sibling of {@link #showDisableConfirm}): the widget has
     * already cycled to the lower mode, so confirming stages it and canceling leaves the draft untouched, and returning
     * to this screen re-reads the mode from the draft so a cancel visibly snaps it back.
     */
    private void showReduceRecaptureConfirm(String key, RecaptureMode value) {
        Minecraft minecraft = Minecraft.getInstance();
        BooleanConsumer choice = confirmed -> {
            if (confirmed) {
                this.draft.set(key, value.name());
            }
            minecraft.gui.setScreen(this);
        };
        // The lost capability differs by target: switching to OFF freezes every area after its first capture,
        // while switching to NEARBY only drops the revisit overwrite (the area around you stays current).
        String messageKey = value == RecaptureMode.OFF
                ? "wdl.settings.confirm.recapture.to_off.message"
                : "wdl.settings.confirm.recapture.to_nearby.message";
        minecraft.gui.setScreen(new WdlCaptureDisableConfirmScreen(choice,
                Component.translatable("wdl.settings.confirm.recapture.title",
                        Component.translatable(valueLabelKey(value)).withColor(BrandColors.AMBER)),
                Component.translatable(messageKey),
                Component.translatable("wdl.settings.confirm.recapture.confirm"),
                CommonComponents.GUI_CANCEL));
    }

    private Control textControl(String key) {
        EditBox box = new EditBox(this.font, 0, 0, CONTROL_WIDTH, CONTROL_HEIGHT,
                Component.translatable(SettingsLayout.optionLabelKey(key)));
        box.setMaxLength(32);
        box.setValue(currentText(key));
        box.setResponder(text -> this.draft.set(key, text));
        return new Control(box, () -> box.setValue(currentText(key)));
    }

    private String currentText(String key) {
        String raw = this.draft.get(key);
        return raw != null ? raw : "";
    }

    private static String valueLabelKey(Enum<?> value) {
        return SettingsLayout.valueLabelKey(value);
    }

    /** Attach the option's help tooltip to its control, only when the language file carries the key (helpKey). */
    private static void applyTooltip(AbstractWidget widget, String key) {
        String tooltipKey = SettingsLayout.optionTooltipKey(key);
        if (Language.getInstance().has(tooltipKey)) {
            widget.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
        }
    }

    private void onBoolChange(String key, boolean value) {
        if (!value && CaptureToggleGuard.whenDisabling(key) != CaptureToggleGuard.NONE) {
            showDisableConfirm(key);
        } else {
            this.draft.set(key, Boolean.toString(value));
        }
    }

    /**
     * The confirm for turning a download-harming capture toggle off: the widget has already flipped to off, so
     * confirming stages the disable and canceling leaves the draft untouched; either way returning to this screen
     * re-reads the toggle from the draft, so a cancel visibly snaps it back on. The body names the per-toggle outcome,
     * and the confirm focuses the safe keep choice by default.
     */
    private void showDisableConfirm(String key) {
        String base = "wdl.settings.confirm.capture"; // only the boolean capture toggles route here
        Minecraft minecraft = Minecraft.getInstance();
        BooleanConsumer choice = confirmed -> {
            if (confirmed) {
                this.draft.set(key, "false");
            }
            minecraft.gui.setScreen(this);
        };
        minecraft.gui.setScreen(new WdlCaptureDisableConfirmScreen(choice,
                Component.translatable(base + ".title",
                        Component.translatable(SettingsLayout.optionLabelKey(key)).withColor(BrandColors.AMBER)),
                Component.translatable(SettingsLayout.confirmMessageKey(key)),
                Component.translatable(base + ".confirm"),
                CommonComponents.GUI_CANCEL));
    }

    private void onDefaults() {
        Minecraft minecraft = Minecraft.getInstance();
        BooleanConsumer choice = confirmed -> {
            if (confirmed) {
                this.draft.revertAllToDefaults();
            }
            minecraft.gui.setScreen(this);
        };
        minecraft.gui.setScreen(new ConfirmScreen(choice,
                Component.translatable("wdl.settings.defaults.title").withColor(BrandColors.AMBER),
                Component.translatable("wdl.settings.defaults.message"),
                Component.translatable("wdl.settings.defaults.confirm"),
                CommonComponents.GUI_CANCEL));
    }

    private void onDiscard() {
        Minecraft minecraft = Minecraft.getInstance();
        BooleanConsumer choice = confirmed -> minecraft.gui.setScreen(confirmed ? this.parent : this);
        minecraft.gui.setScreen(new ConfirmScreen(choice,
                Component.translatable("wdl.settings.discard.title").withColor(BrandColors.AMBER),
                Component.translatable("wdl.settings.discard.message"),
                Component.translatable("wdl.settings.discard.confirm"),
                CommonComponents.GUI_CANCEL));
    }

    @Override
    public void onClose() {
        // Only write when something actually changed: a transient read failure at open seeds the draft from
        // DEFAULTS, so an unconditional close-write would overwrite an intact on-disk config with defaults, and
        // even a clean open+close would needlessly re-canonicalize the file.
        if (this.draft.isDirty()) {
            this.onSave.accept(this.draft.toConfig());
        }
        Minecraft.getInstance().gui.setScreen(this.parent);
    }

    private record Control(AbstractWidget widget, Runnable refresh) {}

    /** The scrollable list of rows for the active tab; rebuilt whole on a tab switch or resize. */
    private final class SettingsList extends ContainerObjectSelectionList<SettingsEntry> {
        SettingsList(Minecraft minecraft, int width, int height, int top, int itemHeight) {
            super(minecraft, width, height, top, itemHeight);
        }

        void add(SettingsEntry entry) {
            addEntry(entry);
        }

        @Override
        public int getRowWidth() {
            return contentBandWidth();
        }
    }

    /** A list row: a section header, an option, or a game rule. */
    private abstract class SettingsEntry extends ContainerObjectSelectionList.Entry<SettingsEntry> {}

    /** A non-interactive section caption drawn in the brand accent. */
    private final class HeaderRow extends SettingsEntry {
        private final Component label;

        HeaderRow(String labelKey) {
            this.label = Component.translatable(labelKey).withStyle(ChatFormatting.BOLD);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hovering,
                float partialTick) {
            RenderSurface surface = new RenderSurfaceImpl(guiGraphics);
            int textY = getContentY() + (ROW_HEIGHT - font.lineHeight) / 2 + 2;
            surface.text(font, this.label, getContentX(), textY, BrandColors.opaque(BrandColors.AMBER));
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    /** A row with a label, one editing control, and a revert affordance, master-gated when applicable. */
    private abstract class ControlRow extends SettingsEntry {
        private final Component label;
        private final Button revert;
        private final @Nullable String masterKey;

        ControlRow(Component label, @Nullable String masterKey) {
            this.label = label;
            this.masterKey = masterKey;
            this.revert = Button.builder(Component.empty(), button -> {
                doRevert();
                refresh();
            }).tooltip(Tooltip.create(Component.translatable("wdl.settings.defaults")))
                    .bounds(0, 0, REVERT_WIDTH, CONTROL_HEIGHT).build();
        }

        abstract AbstractWidget control();

        abstract boolean isModified();

        abstract void doRevert();

        abstract void refresh();

        /** Whether this row shows the passive "capture disabled" mark beside its control (off core toggles). */
        boolean showsCaptureWarning() {
            return false;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hovering,
                float partialTick) {
            RenderSurface surface = new RenderSurfaceImpl(guiGraphics);
            AbstractWidget control = control();
            boolean enabled = this.masterKey == null || draft.getBoolean(this.masterKey);
            int rowX = getContentX();
            int rowTop = getContentY();
            int controlY = rowTop + (ROW_HEIGHT - CONTROL_HEIGHT) / 2;
            int revertX = rowX + getContentWidth() - REVERT_WIDTH;
            int controlX = revertX - GAP - control.getWidth();

            int textY = rowTop + (ROW_HEIGHT - font.lineHeight) / 2;
            surface.text(font, this.label, rowX, textY, BrandColors.opaque(enabled ? BrandColors.IVORY
                    : BrandColors.GRAY));

            control.active = enabled;
            control.setX(controlX);
            control.setY(controlY);
            control.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

            // Passive indicator: an amber caution mark sits just left of an off core-capture toggle, a
            // defense-in-depth reminder that the download will be missing that data beyond the confirm-at-change.
            if (showsCaptureWarning()) {
                int glyphY = controlY + (CONTROL_HEIGHT - font.lineHeight) / 2;
                surface.text(font, CAPTURE_WARNING_GLYPH,
                        controlX - GAP - font.width(CAPTURE_WARNING_GLYPH), glyphY,
                        BrandColors.opaque(BrandColors.AMBER));
            }

            // The revert affordance is shown only when the row differs from its default, hidden otherwise
            // (not grayed). The control slot above stays reserved either way, so nothing reflows as it appears.
            boolean modified = isModified();
            this.revert.visible = modified;
            this.revert.active = enabled && modified;
            this.revert.setX(revertX);
            this.revert.setY(controlY);
            this.revert.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

            // The revert icon is a bundled sprite, not a font glyph: the revert codepoint resolves only through
            // the tiny unifont fallback, which no scale renders crisply. The button carries a blank label so
            // vanilla draws nothing underneath. The white source is tinted to carry the enabled/disabled color,
            // and a second offset draw gives it the same drop shadow the surrounding button text has.
            if (modified) {
                int iconColor = BrandColors.opaque(enabled ? BrandColors.WHITE : BrandColors.GRAY);
                // Quarter-brightness of the foreground, matching vanilla Font's drop-shadow derivation.
                int shadowColor = (iconColor & 0xFCFCFC) >> 2 | (iconColor & 0xFF000000);
                // Center on the button face, which excludes the one-pixel bottom shadow row of the widget sprite.
                int iconX = revertX + (REVERT_WIDTH - REVERT_ICON_WIDTH) / 2;
                int iconY = controlY + (CONTROL_HEIGHT - 1 - REVERT_ICON_HEIGHT) / 2;
                surface.blitSprite(REVERT_ICON, iconX + 1, iconY + 1,
                        REVERT_ICON_WIDTH, REVERT_ICON_HEIGHT, shadowColor);
                surface.blitSprite(REVERT_ICON, iconX, iconY,
                        REVERT_ICON_WIDTH, REVERT_ICON_HEIGHT, iconColor);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(control(), this.revert);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(control(), this.revert);
        }
    }

    /** A descriptor-backed option row: reverts to the descriptor default. */
    private final class OptionRow extends ControlRow {
        private final String key;
        private final AbstractWidget control;
        private final Runnable refresher;

        OptionRow(String key, AbstractWidget control, Runnable refresher, @Nullable String masterKey) {
            super(Component.translatable(SettingsLayout.optionLabelKey(key)), masterKey);
            this.key = key;
            this.control = control;
            this.refresher = refresher;
        }

        @Override
        AbstractWidget control() {
            return this.control;
        }

        @Override
        boolean isModified() {
            return draft.isModifiedFromDefault(this.key);
        }

        @Override
        boolean showsCaptureWarning() {
            return CaptureToggleGuard.whenDisabling(this.key) == CaptureToggleGuard.CAPTURE
                    && !draft.getBoolean(this.key);
        }

        @Override
        void doRevert() {
            draft.revert(this.key);
        }

        @Override
        void refresh() {
            this.refresher.run();
        }
    }

    /** A curated game-rule row: a toggle over the sparse override map, reverting by deleting its entry. */
    private final class GameRuleRow extends ControlRow {
        private final CuratedGameRule rule;
        private final CycleButton<Boolean> toggle;

        GameRuleRow(CuratedGameRule rule) {
            super(Component.translatable(SettingsLayout.gameRuleLabelKey(rule.id())), "overrideGamerules");
            this.rule = rule;
            this.toggle = CycleButton.onOffBuilder(isOn()).displayOnlyValue()
                    .create(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT, Component.empty(),
                            (button, value) -> draft.setGameRule(rule.bandId(),
                                    value ? rule.enabledValue() : rule.disabledValue(), rule.curatedValue()));
        }

        @Override
        AbstractWidget control() {
            return this.toggle;
        }

        private boolean isOn() {
            return draft.gameRuleValue(this.rule.bandId(), this.rule.curatedValue()).equals(this.rule.enabledValue());
        }

        @Override
        boolean isModified() {
            return draft.isGameRuleModified(this.rule.bandId(), this.rule.curatedValue());
        }

        @Override
        void doRevert() {
            draft.revertGameRule(this.rule.id());
        }

        @Override
        void refresh() {
            this.toggle.setValue(isOn());
        }
    }

    /**
     * A slider over a descriptor range; its message renders the current value through the option's value key. The
     * message and the draft write both use {@link #currentText()}, so a subclass supplies only the value quantization
     * and formatting (int versus tenth-quantized float).
     */
    private abstract class RangeSlider extends AbstractSliderButton {
        final String key;

        RangeSlider(String key, double initialFraction) {
            super(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT, Component.empty(), initialFraction);
            this.key = key;
        }

        /** The current slider value, quantized and formatted for both the label and the draft. */
        abstract String currentText();

        /** The slider fraction for the value currently staged in the draft. */
        abstract double draftFraction();

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(SettingsLayout.optionValueKey(this.key), currentText()));
        }

        @Override
        protected void applyValue() {
            draft.set(this.key, currentText());
        }

        void syncFromDraft() {
            this.value = draftFraction();
            updateMessage();
        }
    }

    private final class IntSlider extends RangeSlider {
        private final int min;
        private final int max;

        IntSlider(String key, int min, int max) {
            super(key, fraction(draft.getInteger(key), min, max));
            this.min = min;
            this.max = max;
            updateMessage();
        }

        private int current() {
            return this.min + (int) Math.round(this.value * (this.max - this.min));
        }

        @Override
        String currentText() {
            return Integer.toString(current());
        }

        @Override
        double draftFraction() {
            return fraction(draft.getInteger(this.key), this.min, this.max);
        }
    }

    /** Quantized to a tenth, for the outline rim-width scale. */
    private final class FloatSlider extends RangeSlider {
        private final float min;
        private final float max;

        FloatSlider(String key, float min, float max) {
            super(key, fraction(draft.getFloat(key), min, max));
            this.min = min;
            this.max = max;
            updateMessage();
        }

        private float current() {
            float raw = (float) (this.min + this.value * (this.max - this.min));
            return Math.round(raw * 10.0f) / 10.0f;
        }

        @Override
        String currentText() {
            return String.format(Locale.ROOT, "%.1f", current());
        }

        @Override
        double draftFraction() {
            return fraction(draft.getFloat(this.key), this.min, this.max);
        }
    }

    private static double fraction(double value, double min, double max) {
        return max == min ? 0.0 : (value - min) / (max - min);
    }
}
