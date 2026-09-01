// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.gui.GuiPageButtonList;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
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
public final class WdlSettingsScreen extends GuiScreen {
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

    /**
     * The keys stay declared in {@link ConfigSchema} and laid out in {@link SettingsLayout}, which ship byte-identical
     * to every band, so refusing the row here is what keeps them unforked.
     */
    private static final Set<String> HIDDEN_ROWS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("captureAdvancements", "lockDownloadedMaps", "saveItemCoordinates")));

    private final @Nullable GuiScreen parent;
    private final SettingsDraft draft;
    private final Consumer<WdlConfig> onSave;
    private final Map<String, CuratedGameRule> curatedById;
    private final Predicate<String> modLoaded;

    private int activeTab;
    private @Nullable GuiButton defaults;
    private @Nullable SettingsList list;
    // Below 1.19.3 there is no per-widget Tooltip, and a control's tooltip inside the scrolling list clips to the
    // list scissor, so hovered tooltips are recorded here and drawn screen-side after the list in render.
    private final List<Map.Entry<Gui, ITextComponent>> hoverTooltips = new ArrayList<>();

    public WdlSettingsScreen(@Nullable GuiScreen parent, WdlConfig live, Consumer<WdlConfig> onSave,
            List<CuratedGameRule> curatedGameRules, Predicate<String> modLoaded) {
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

    private void rebuildWidgets() {
        this.buttonList.clear();
        initGui();
    }

    @Override
    public void initGui() {
        this.hoverTooltips.clear();
        SettingsList settingsList = new SettingsList(this.mc, this.width, this.height,
                LIST_TOP, this.height - FOOTER_HEIGHT, ROW_HEIGHT);
        populate(settingsList);
        this.list = settingsList;
        buildTabStrip();
        buildFooter();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button instanceof ActionButton) {
            ((ActionButton) button).press();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (this.list != null) {
            // Drop focus first so a click re-focuses only the seed field it lands on, matching the higher bands
            // where the list owns focus; the field's own mouseClicked below re-takes it when the click hits it.
            this.list.clearFieldFocus();
            this.list.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (this.list != null) {
            this.list.mouseReleased(mouseX, mouseY, state);
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        if (this.list != null) {
            this.list.handleMouseInput();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) { // Esc commits the draft, matching the Done button, rather than discarding
            closeAndSave();
            return;
        }
        if (this.list != null && this.list.forwardKeyToFocused(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        if (this.list != null) {
            this.list.tickControls();
        }
    }

    /**
     * A button that runs an action on press, since the pre-1.13 GuiButton has no onPress; dispatched by
     * actionPerformed.
     */
    private final class ActionButton extends GuiButton {
        private final Runnable onPress;

        ActionButton(int x, int y, int width, int height, String label, Runnable onPress) {
            super(0, x, y, width, height, label);
            this.onPress = onPress;
        }

        void press() {
            this.onPress.run();
        }
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
            ITextComponent title = new TextComponentTranslation(
                    SettingsLayout.tabLabelKey(SettingsLayout.TABS.get(i).id()));
            ActionButton tab = new ActionButton(tabX, TAB_TOP, thisTabWidth, TAB_HEIGHT, title.getUnformattedText(),
                    () -> selectTab(index));
            tab.enabled = i != this.activeTab; // the active tab reads as pressed by being inert
            addButton(tab);
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
    private static final class ColumnBounds {
        private final int left;
        private final int right;

        ColumnBounds(int left, int right) {
            this.left = left;
            this.right = right;
        }

        int left() {
            return left;
        }

        int right() {
            return right;
        }

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
        addButton(new ActionButton(doneX, footerY, buttonWidth, 20, I18n.format("gui.done"), this::closeAndSave));
        // This band's button carries no hover-tooltip parameter, so the Defaults button has no hover explanation.
        this.defaults = addButton(new ActionButton(defaultsX, footerY, buttonWidth, 20,
                I18n.format("wdl.settings.defaults"), this::onDefaults));
        // the last button absorbs the division remainder so the footer's right edge meets the control edge
        addButton(new ActionButton(discardX, footerY, column.right() - discardX, 20,
                I18n.format("wdl.settings.discard"), this::onDiscard));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        this.drawDefaultBackground();
        // The Defaults button would only revert to a state the draft is already in, so it grays out (never
        // hides, keeping the footer stable and the feature discoverable) while the draft equals defaults, and
        // a tooltip on the gray state says why. Kept current with live edits by resolving before the widgets
        // draw; the equality check gates the write so the tooltip is rebuilt only when the state flips.
        if (this.defaults != null) {
            boolean atDefaults = this.draft.isAtDefaults(this::isRowVisible);
            if (this.defaults.enabled == atDefaults) {
                this.defaults.enabled = !atDefaults;
            }
        }
        // Below the 1.19.4 GUI additions GuiScreen.render paints only its buttons, so the list added as a widget is
        // drawn by hand. It draws before the buttons here, not after as the renderable order on the higher bands:
        // below 1.20.2 this list fills tiled dirt above and below itself, which is what hides its unclipped row
        // overflow at the top and bottom edges (it does not scissor), so drawing it first lets that fill hide the
        // overflow while the tab strip and footer buttons paint on top of it. Drawing it after would bury both.
        if (this.list != null) {
            this.list.drawScreen(mouseX, mouseY, partialTick);
        }
        super.drawScreen(mouseX, mouseY, partialTick);
        // The recorded hover tooltips are drawn here, unclipped, after the list.
        for (Map.Entry<Gui, ITextComponent> tip : this.hoverTooltips) {
            if (isControlHovered(tip.getKey(), mouseX, mouseY)) {
                this.drawHoveringText(
                        this.fontRenderer.listFormattedStringToWidth(tip.getValue().getUnformattedText(), 200),
                        mouseX, mouseY);
                break;
            }
        }
    }

    // Below 1.13 GuiButton and GuiTextField share only the Gui base, so a row's control is held as Gui and narrowed
    // at each site through these: a button exposes position, width, render, and hover directly, while a GuiTextField
    // carries its own coordinate fields and a fixed width and has no hover state, so its hover is tested against the
    // control box.
    private static void setControlActive(Gui control, boolean active) {
        if (control instanceof GuiButton) {
            ((GuiButton) control).enabled = active;
        } else {
            ((GuiTextField) control).setEnabled(active);
        }
    }

    private static void setControlPosition(Gui control, int x, int y) {
        if (control instanceof GuiButton) {
            GuiButton button = (GuiButton) control;
            button.x = x;
            button.y = y;
        } else {
            GuiTextField box = (GuiTextField) control;
            box.x = x;
            box.y = y;
        }
    }

    private static int controlWidth(Gui control) {
        return control instanceof GuiButton ? ((GuiButton) control).getButtonWidth() : CONTROL_WIDTH;
    }

    private static void renderControl(Gui control, int mouseX, int mouseY) {
        if (control instanceof GuiButton) {
            ((GuiButton) control).drawButton(Minecraft.getMinecraft(), mouseX, mouseY);
        } else {
            ((GuiTextField) control).drawTextBox();
        }
    }

    private static boolean isControlHovered(Gui control, int mouseX, int mouseY) {
        if (control instanceof GuiButton) {
            return ((GuiButton) control).isMouseOver();
        }
        GuiTextField box = (GuiTextField) control;
        return mouseX >= box.x && mouseX < box.x + CONTROL_WIDTH
                && mouseY >= box.y && mouseY < box.y + CONTROL_HEIGHT;
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
                continue; // a section left with no row to show, install-gated or hidden here, drops its header
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

    /**
     * Whether {@code key}'s row shows: a hidden row never appears, and a row that names required mods appears only when
     * one of them is loaded.
     */
    private boolean isRowVisible(String key) {
        if (HIDDEN_ROWS.contains(key)) {
            return false;
        }
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
                WdlCycleButton<Boolean> toggle = new WdlCycleButton<>(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT,
                        ImmutableList.of(Boolean.TRUE, Boolean.FALSE), this.draft.getBoolean(key),
                        value -> value ? new TextComponentTranslation("options.on")
                                : new TextComponentTranslation("options.off"),
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
                return cycleControl(key, HudAnchor.class, ImmutableList.copyOf(HudAnchor.values()),
                        this.draft.getEnum(key, HudAnchor.class));
            case "hudPeekMode":
                return cycleControl(key, HudPeekMode.class, ImmutableList.copyOf(HudPeekMode.values()),
                        this.draft.getEnum(key, HudPeekMode.class));
            case "worldType":
                return cycleControl(key, WorldType.class, ImmutableList.copyOf(WorldType.values()),
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
        WdlCycleButton<E> cycle = new WdlCycleButton<>(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT, values, initial,
                value -> new TextComponentTranslation(valueLabelKey(value)),
                (button, value) -> this.draft.set(key, value.name()));
        return new Control(cycle, () -> cycle.setValue(this.draft.getEnum(key, type)));
    }

    private Control recaptureControl(String key) {
        WdlCycleButton<RecaptureMode> cycle = new WdlCycleButton<>(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT,
                ImmutableList.copyOf(RecaptureMode.values()), this.draft.getEnum(key, RecaptureMode.class),
                value -> new TextComponentTranslation(valueLabelKey(value)),
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
        Minecraft minecraft = Minecraft.getMinecraft();
        Consumer<Boolean> choice = confirmed -> {
            if (confirmed) {
                this.draft.set(key, value.name());
            }
            minecraft.displayGuiScreen(this);
        };
        // The lost capability differs by target: switching to OFF freezes every area after its first capture,
        // while switching to NEARBY only drops the revisit overwrite (the area around you stays current).
        String messageKey = value == RecaptureMode.OFF
                ? "wdl.settings.confirm.recapture.to_off.message"
                : "wdl.settings.confirm.recapture.to_nearby.message";
        minecraft.displayGuiScreen(new WdlCaptureDisableConfirmScreen(choice,
                new TextComponentTranslation("wdl.settings.confirm.recapture.title",
                        amberComponent(new TextComponentTranslation(valueLabelKey(value)))),
                new TextComponentTranslation(messageKey),
                new TextComponentTranslation("wdl.settings.confirm.recapture.confirm"),
                new TextComponentTranslation("gui.cancel")));
    }

    private Control textControl(String key) {
        GuiTextField box = new GuiTextField(0, this.fontRenderer, 0, 0, CONTROL_WIDTH, CONTROL_HEIGHT);
        box.setMaxStringLength(32);
        box.setText(currentText(key));
        box.setGuiResponder(new GuiPageButtonList.GuiResponder() {
            @Override
            public void setEntryValue(int id, boolean value) {}

            @Override
            public void setEntryValue(int id, float value) {}

            @Override
            public void setEntryValue(int id, String value) {
                draft.set(key, value);
            }
        });
        return new Control(box, () -> box.setText(currentText(key)));
    }

    private String currentText(String key) {
        String raw = this.draft.get(key);
        return raw != null ? raw : "";
    }

    private static String valueLabelKey(Enum<?> value) {
        return SettingsLayout.valueLabelKey(value);
    }

    /** Attach the option's help tooltip to its control, only when the language file carries the key (helpKey). */
    private void applyTooltip(Gui widget, String key) {
        String tooltipKey = SettingsLayout.optionTooltipKey(key);
        if (I18n.hasKey(tooltipKey)) {
            this.hoverTooltips.add(Maps.immutableEntry(widget, new TextComponentTranslation(tooltipKey)));
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
        Minecraft minecraft = Minecraft.getMinecraft();
        Consumer<Boolean> choice = confirmed -> {
            if (confirmed) {
                this.draft.set(key, "false");
            }
            minecraft.displayGuiScreen(this);
        };
        minecraft.displayGuiScreen(new WdlCaptureDisableConfirmScreen(choice,
                new TextComponentTranslation(base + ".title",
                        amberComponent(new TextComponentTranslation(SettingsLayout.optionLabelKey(key)))),
                new TextComponentTranslation(SettingsLayout.confirmMessageKey(key)),
                new TextComponentTranslation(base + ".confirm"),
                new TextComponentTranslation("gui.cancel")));
    }

    private void onDefaults() {
        Minecraft minecraft = Minecraft.getMinecraft();
        minecraft.displayGuiScreen(new GuiYesNo((confirmed, dialogId) -> {
            if (confirmed) {
                this.draft.revertAllToDefaults();
            }
            minecraft.displayGuiScreen(this);
        }, amberComponent(new TextComponentTranslation("wdl.settings.defaults.title")).getFormattedText(),
                new TextComponentTranslation("wdl.settings.defaults.message").getUnformattedText(),
                I18n.format("wdl.settings.defaults.confirm"),
                I18n.format("gui.cancel"), 0));
    }

    private void onDiscard() {
        Minecraft minecraft = Minecraft.getMinecraft();
        minecraft.displayGuiScreen(new GuiYesNo(
                (confirmed, dialogId) -> minecraft.displayGuiScreen(confirmed ? this.parent
                        : this),
                amberComponent(new TextComponentTranslation("wdl.settings.discard.title")).getFormattedText(),
                new TextComponentTranslation("wdl.settings.discard.message").getUnformattedText(),
                I18n.format("wdl.settings.discard.confirm"),
                I18n.format("gui.cancel"), 0));
    }

    private void closeAndSave() {
        // Only write when something actually changed: a transient read failure at open seeds the draft from
        // DEFAULTS, so an unconditional close-write would overwrite an intact on-disk config with defaults, and
        // even a clean open+close would needlessly re-canonicalize the file.
        if (this.draft.isDirty()) {
            this.onSave.accept(this.draft.toConfig());
        }
        this.mc.displayGuiScreen(this.parent);
    }

    private static final class Control {
        private final Gui widget;
        private final Runnable refresh;

        Control(Gui widget, Runnable refresh) {
            this.widget = widget;
            this.refresh = refresh;
        }

        Gui widget() {
            return widget;
        }

        Runnable refresh() {
            return refresh;
        }
    }

    /** The scrollable list of rows for the active tab; rebuilt whole on a tab switch or resize. */
    private final class SettingsList extends GuiListExtended {
        private final List<SettingsEntry> entries = new ArrayList<>();

        SettingsList(Minecraft minecraft, int width, int height, int top, int bottom, int itemHeight) {
            super(minecraft, width, height, top, bottom, itemHeight);
        }

        void add(SettingsEntry entry) {
            this.entries.add(entry);
        }

        @Override
        protected int getSize() {
            return this.entries.size();
        }

        @Override
        public GuiListExtended.IGuiListEntry getListEntry(int index) {
            return this.entries.get(index);
        }

        @Override
        public int getListWidth() {
            return contentBandWidth();
        }

        @Override
        protected int getScrollBarX() {
            // This band's default is a fixed width/2 + 124 that assumes the 220-wide default row, so with this
            // screen's wider row it lands inside each row and cuts through the controls; later bands derive it
            // from the row right edge, which this follows.
            return this.left + this.width / 2 + getListWidth() / 2 + 10;
        }

        boolean forwardKeyToFocused(char typedChar, int keyCode) {
            boolean handled = false;
            for (SettingsEntry entry : this.entries) {
                handled |= entry.keyTyped(typedChar, keyCode);
            }
            return handled;
        }

        void tickControls() {
            for (SettingsEntry entry : this.entries) {
                entry.updateCursor();
            }
        }

        void clearFieldFocus() {
            for (SettingsEntry entry : this.entries) {
                entry.clearFocus();
            }
        }
    }

    /** A list row: a section header, an option, or a game rule. */
    private abstract class SettingsEntry implements GuiListExtended.IGuiListEntry {
        private int contentX;
        private int contentY;
        private int contentWidth;

        @Override
        public void drawEntry(int slotIndex, int x, int y, int listWidth, int slotHeight, int mouseX, int mouseY,
                boolean isSelected) {
            this.contentX = x;
            this.contentY = y;
            this.contentWidth = listWidth;
            renderContent(mouseX, mouseY, isSelected);
        }

        @Override
        public void setSelected(int slotIndex, int x, int y) {}

        @Override
        public boolean mousePressed(int slotIndex, int mouseX, int mouseY, int mouseEvent, int relativeX,
                int relativeY) {
            return false;
        }

        @Override
        public void mouseReleased(int slotIndex, int x, int y, int mouseEvent, int relativeX, int relativeY) {}

        boolean keyTyped(char typedChar, int keyCode) {
            return false;
        }

        void updateCursor() {}

        void clearFocus() {}

        abstract void renderContent(int mouseX, int mouseY, boolean hovering);

        int getContentX() {
            return contentX;
        }

        int getContentY() {
            return contentY;
        }

        int getContentWidth() {
            return contentWidth;
        }
    }

    /** A non-interactive section caption drawn in the brand accent. */
    private final class HeaderRow extends SettingsEntry {
        private final ITextComponent label;

        HeaderRow(String labelKey) {
            this.label = new TextComponentTranslation(labelKey);
        }

        @Override
        public void renderContent(int mouseX, int mouseY, boolean hovering) {
            RenderSurface surface = new RenderSurfaceImpl();
            int textY = getContentY() + (ROW_HEIGHT - fontRenderer.FONT_HEIGHT) / 2 + 2;
            surface.text(fontRenderer, this.label, getContentX(), textY, BrandColors.opaque(BrandColors.AMBER));
        }
    }

    /** A row with a label, one editing control, and a revert affordance, master-gated when applicable. */
    private abstract class ControlRow extends SettingsEntry {
        private final ITextComponent label;
        private final GuiButton revert;
        private final @Nullable String masterKey;

        ControlRow(ITextComponent label, @Nullable String masterKey) {
            this.label = label;
            this.masterKey = masterKey;
            this.revert = new GuiButton(0, 0, 0, REVERT_WIDTH, CONTROL_HEIGHT, "");
            WdlSettingsScreen.this.hoverTooltips.add(
                    Maps.immutableEntry(this.revert, new TextComponentTranslation("wdl.settings.defaults")));
        }

        abstract Gui control();

        abstract boolean isModified();

        abstract void doRevert();

        abstract void refresh();

        /** Whether this row shows the passive "capture disabled" mark beside its control (off core toggles). */
        boolean showsCaptureWarning() {
            return false;
        }

        @Override
        public void renderContent(int mouseX, int mouseY, boolean hovering) {
            RenderSurface surface = new RenderSurfaceImpl();
            Gui control = control();
            boolean enabled = this.masterKey == null || draft.getBoolean(this.masterKey);
            int rowX = getContentX();
            int rowTop = getContentY();
            int controlY = rowTop + (ROW_HEIGHT - CONTROL_HEIGHT) / 2;
            int revertX = rowX + getContentWidth() - REVERT_WIDTH;
            int controlX = revertX - GAP - controlWidth(control);

            int textY = rowTop + (ROW_HEIGHT - fontRenderer.FONT_HEIGHT) / 2;
            surface.text(fontRenderer, this.label, rowX, textY, BrandColors.opaque(enabled ? BrandColors.IVORY
                    : BrandColors.GRAY));

            setControlActive(control, enabled);
            setControlPosition(control, controlX, controlY);
            renderControl(control, mouseX, mouseY);

            // Passive indicator: an amber caution mark sits just left of an off core-capture toggle, a
            // defense-in-depth reminder that the download will be missing that data beyond the confirm-at-change.
            if (showsCaptureWarning()) {
                int glyphY = controlY + (CONTROL_HEIGHT - fontRenderer.FONT_HEIGHT) / 2;
                surface.text(fontRenderer, CAPTURE_WARNING_GLYPH,
                        controlX - GAP - fontRenderer.getStringWidth(CAPTURE_WARNING_GLYPH), glyphY,
                        BrandColors.opaque(BrandColors.AMBER));
            }

            // The revert affordance is shown only when the row differs from its default, hidden otherwise
            // (not grayed). The control slot above stays reserved either way, so nothing reflows as it appears.
            boolean modified = isModified();
            this.revert.visible = modified;
            this.revert.enabled = enabled && modified;
            this.revert.x = revertX;
            this.revert.y = controlY;
            this.revert.drawButton(Minecraft.getMinecraft(), mouseX, mouseY);

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

        // The list routes a row click, drag, and key to the entry, not to its inner widgets, so the row forwards
        // each to its control (a cycle button advances, a slider grabs, a seed field focuses and takes keys) and
        // to its revert button.
        @Override
        public boolean mousePressed(int slotIndex, int mouseX, int mouseY, int mouseEvent, int relativeX,
                int relativeY) {
            Gui control = control();
            boolean controlHit;
            if (control instanceof GuiButton) {
                controlHit = ((GuiButton) control).mousePressed(Minecraft.getMinecraft(), mouseX, mouseY);
            } else {
                // GuiTextField.mouseClicked returns void at this band, where the higher bands hand the hit back.
                // It sets its own focus from the bounds test, so isFocused after the call is that bounds test,
                // which holds because nothing here clears canLoseFocus.
                GuiTextField field = (GuiTextField) control;
                field.mouseClicked(mouseX, mouseY, mouseEvent);
                controlHit = field.isFocused();
            }
            if (controlHit) {
                return true;
            }
            if (this.revert.enabled && this.revert.mousePressed(Minecraft.getMinecraft(), mouseX, mouseY)) {
                doRevert();
                refresh();
                return true;
            }
            return false;
        }

        @Override
        public void mouseReleased(int slotIndex, int x, int y, int mouseEvent, int relativeX, int relativeY) {
            Gui control = control();
            if (control instanceof GuiButton) {
                ((GuiButton) control).mouseReleased(x, y);
            }
        }

        @Override
        boolean keyTyped(char typedChar, int keyCode) {
            Gui control = control();
            return control instanceof GuiTextField && ((GuiTextField) control).textboxKeyTyped(typedChar, keyCode);
        }

        @Override
        void updateCursor() {
            Gui control = control();
            if (control instanceof GuiTextField) {
                ((GuiTextField) control).updateCursorCounter();
            }
        }

        @Override
        void clearFocus() {
            Gui control = control();
            if (control instanceof GuiTextField) {
                ((GuiTextField) control).setFocused(false);
            }
        }
    }

    /** A descriptor-backed option row: reverts to the descriptor default. */
    private final class OptionRow extends ControlRow {
        private final String key;
        private final Gui control;
        private final Runnable refresher;

        OptionRow(String key, Gui control, Runnable refresher, @Nullable String masterKey) {
            super(new TextComponentTranslation(SettingsLayout.optionLabelKey(key)), masterKey);
            this.key = key;
            this.control = control;
            this.refresher = refresher;
        }

        @Override
        Gui control() {
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
        private final WdlCycleButton<Boolean> toggle;

        GameRuleRow(CuratedGameRule rule) {
            super(new TextComponentTranslation(SettingsLayout.gameRuleLabelKey(rule.id())), "overrideGamerules");
            this.rule = rule;
            this.toggle = new WdlCycleButton<>(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT,
                    ImmutableList.of(Boolean.TRUE, Boolean.FALSE), isOn(),
                    value -> value ? new TextComponentTranslation("options.on")
                            : new TextComponentTranslation("options.off"),
                    (button, value) -> draft.setGameRule(rule.bandId(),
                            value ? rule.enabledValue() : rule.disabledValue(), rule.curatedValue()));
        }

        @Override
        Gui control() {
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
     * A slider over a descriptor range; its message renders the current value through the option's value key. This band
     * ships no reusable slider primitive (the vanilla one is bound to a game Option), so the slider is driven on the
     * button base directly: a press or held drag sets the fraction from the mouse, the handle is drawn in the button
     * background pass, and a subclass supplies only the value quantization and formatting (int versus tenth-quantized
     * float).
     */
    private abstract class RangeSlider extends GuiButton {
        final String key;
        protected double value;
        private boolean sliding;

        RangeSlider(String key, double initialFraction) {
            super(0, 0, 0, CONTROL_WIDTH, CONTROL_HEIGHT, "");
            this.key = key;
            this.value = initialFraction;
        }

        /** The current slider value, quantized and formatted for both the label and the draft. */
        abstract String currentText();

        /** The slider fraction for the value currently staged in the draft. */
        abstract double draftFraction();

        protected void updateMessage() {
            this.displayString = new TextComponentTranslation(SettingsLayout.optionValueKey(this.key), currentText())
                    .getUnformattedText();
        }

        protected void applyValue() {
            draft.set(this.key, currentText());
        }

        void syncFromDraft() {
            this.value = draftFraction();
            updateMessage();
        }

        // The flat button face stands in for the slider groove (getHoverState returning 0 selects the base texture
        // row), and the handle is drawn over it in mouseDragged, which drawButton calls each frame between the face
        // and the label, matching where vanilla's own slider draws its handle.
        @Override
        protected int getHoverState(boolean mouseOver) {
            return 0;
        }

        @Override
        protected void mouseDragged(Minecraft minecraft, int mouseX, int mouseY) {
            if (this.sliding) {
                setValueFromMouse(mouseX);
            }
            minecraft.getTextureManager().bindTexture(BUTTON_TEXTURES);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            int handleX = this.x + (int) (this.value * (this.width - 8));
            this.drawTexturedModalRect(handleX, this.y, 0, 66, 4, 20);
            this.drawTexturedModalRect(handleX + 4, this.y, 196, 66, 4, 20);
        }

        @Override
        public boolean mousePressed(Minecraft minecraft, int mouseX, int mouseY) {
            if (!super.mousePressed(minecraft, mouseX, mouseY)) {
                return false;
            }
            setValueFromMouse(mouseX);
            this.sliding = true;
            return true;
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            this.sliding = false;
        }

        private void setValueFromMouse(double mouseX) {
            this.value = MathHelper.clamp((mouseX - (this.x + 4)) / (this.width - 8), 0.0, 1.0);
            updateMessage();
            applyValue();
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

    /** Tint {@code component} the nearest vanilla color to the brand amber (gold), mutating its style in place. */
    private static ITextComponent amberComponent(ITextComponent component) {
        component.getStyle().setColor(TextFormatting.GOLD);
        return component;
    }
}
