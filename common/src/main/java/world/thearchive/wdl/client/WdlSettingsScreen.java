// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.mojang.blaze3d.platform.GlStateManager;
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
import net.minecraft.client.gui.class_4122;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.class_1802;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.realms.class_356;
import net.minecraft.util.Mth;
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
    private @Nullable class_356 defaults;
    private @Nullable SettingsList list;
    // Below 1.19.3 there is no per-widget Tooltip, and a control's tooltip inside the scrolling list clips to the
    // list scissor, so hovered tooltips are recorded here and drawn screen-side after the list in render.
    private final List<Map.Entry<class_4122, Component>> hoverTooltips = new ArrayList<>();

    public WdlSettingsScreen(@Nullable Screen parent, WdlConfig live, Consumer<WdlConfig> onSave,
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
        this.field_1232.clear();
        this.field_20307.clear();
        init();
    }

    @Override
    protected void init() {
        this.hoverTooltips.clear();
        SettingsList settingsList = new SettingsList(Minecraft.getInstance(), this.field_1230, this.field_1231,
                LIST_TOP, this.field_1231 - FOOTER_HEIGHT, ROW_HEIGHT);
        populate(settingsList);
        this.list = settingsList;
        this.field_20307.add(settingsList);

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
            Component title = new TranslatableComponent(SettingsLayout.tabLabelKey(SettingsLayout.TABS.get(i).id()));
            class_356 tab = new class_356(0, tabX, TAB_TOP, thisTabWidth, TAB_HEIGHT, title.getString()) {
                @Override
                public void method_18374(double mouseX, double mouseY) {
                    selectTab(index);
                }
            };
            tab.field_1055 = i != this.activeTab; // the active tab reads as pressed by being inert
            method_13411(tab);
        }
    }

    /** The option content band width, the single source the tab strip, footer, and list rows all size to. */
    private int contentBandWidth() {
        return Math.min(this.field_1230 - 20, ROW_BAND_MAX);
    }

    /**
     * The visible option column the tab strip and footer both bracket: labels start at the row content inset, and the
     * right-anchored control stops a revert-affordance slot short of the band edge, so neither spans the raw band, only
     * the column between them. Matching the list's own width/2 - band/2 origin keeps them aligned.
     */
    private ColumnBounds optionColumn() {
        int band = contentBandWidth();
        int left = this.field_1230 / 2 - band / 2 + 2;
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
        int footerY = this.field_1231 - 26;
        int doneX = column.left();
        int defaultsX = doneX + buttonWidth + GAP;
        int discardX = defaultsX + buttonWidth + GAP;
        method_13411(new class_356(0, doneX, footerY, buttonWidth, 20, I18n.get("gui.done")) {
            @Override
            public void method_18374(double mouseX, double mouseY) {
                method_18608();
            }
        });
        // This band's button carries no hover-tooltip parameter, so the Defaults button has no hover explanation.
        this.defaults = method_13411(new class_356(0, defaultsX, footerY, buttonWidth, 20,
                I18n.get("wdl.settings.defaults")) {
            @Override
            public void method_18374(double mouseX, double mouseY) {
                onDefaults();
            }
        });
        // the last button absorbs the division remainder so the footer's right edge meets the control edge
        method_13411(new class_356(0, discardX, footerY, column.right() - discardX, 20,
                I18n.get("wdl.settings.discard")) {
            @Override
            public void method_18374(double mouseX, double mouseY) {
                onDiscard();
            }
        });
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTick) {
        this.method_1043();
        // The Defaults button would only revert to a state the draft is already in, so it grays out (never
        // hides, keeping the footer stable and the feature discoverable) while the draft equals defaults, and
        // a tooltip on the gray state says why. Kept current with live edits by resolving before the widgets
        // draw; the equality check gates the write so the tooltip is rebuilt only when the state flips.
        if (this.defaults != null) {
            boolean atDefaults = this.draft.isAtDefaults();
            if (this.defaults.field_1055 == atDefaults) {
                this.defaults.field_1055 = !atDefaults;
            }
        }
        // Below the 1.19.4 GUI additions Screen.render paints only its buttons, so the list added as a widget is
        // drawn by hand. It draws before the buttons here, not after as the renderable order on the higher bands:
        // below 1.20.2 this list fills tiled dirt above and below itself, which is what hides its unclipped row
        // overflow at the top and bottom edges (it does not scissor), so drawing it first lets that fill hide the
        // overflow while the tab strip and footer buttons paint on top of it. Drawing it after would bury both.
        if (this.list != null) {
            this.list.method_1053(mouseX, mouseY, partialTick);
        }
        super.render(mouseX, mouseY, partialTick);
        // The recorded hover tooltips are drawn here, unclipped, after the list.
        for (Map.Entry<class_4122, Component> tip : this.hoverTooltips) {
            if (isControlHovered(tip.getKey(), mouseX, mouseY)) {
                this.renderTooltip(this.field_1234.split(tip.getValue().getString(), 200), mouseX, mouseY);
                break;
            }
        }
    }

    // This band's EditBox and Button share no widget base, so a row's control is held as the common event-listener
    // type and driven through these: the button exposes position, width, render, and hover directly, while the
    // EditBox carries its own coordinate fields, a final width, an editable flag, and no hover state, so its hover is
    // tested against the fixed control box.
    private static void setControlActive(class_4122 control, boolean active) {
        if (control instanceof class_356) {
            ((class_356) control).field_1055 = active;
        } else {
            ((EditBox) control).setEditable(active);
        }
    }

    private static void setControlPosition(class_4122 control, int x, int y) {
        if (control instanceof class_356) {
            class_356 button = (class_356) control;
            button.field_1051 = x;
            button.field_1052 = y;
        } else {
            EditBox box = (EditBox) control;
            box.field_1117 = x;
            box.field_1118 = y;
        }
    }

    private static int controlWidth(class_4122 control) {
        return control instanceof class_356 ? ((class_356) control).getWidth() : CONTROL_WIDTH;
    }

    private static void renderControl(class_4122 control, int mouseX, int mouseY, float partialTick) {
        if (control instanceof class_356) {
            ((class_356) control).renderButton(mouseX, mouseY, partialTick);
        } else {
            ((EditBox) control).renderButton(mouseX, mouseY, partialTick);
        }
    }

    private static boolean isControlHovered(class_4122 control, int mouseX, int mouseY) {
        if (control instanceof class_356) {
            return ((class_356) control).method_4229();
        }
        EditBox box = (EditBox) control;
        return mouseX >= box.field_1117 && mouseX < box.field_1117 + CONTROL_WIDTH
                && mouseY >= box.field_1118 && mouseY < box.field_1118 + CONTROL_HEIGHT;
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
                WdlCycleButton<Boolean> toggle = new WdlCycleButton<>(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT,
                        ImmutableList.of(Boolean.TRUE, Boolean.FALSE), this.draft.getBoolean(key),
                        value -> value ? new TranslatableComponent("options.on")
                                : new TranslatableComponent("options.off"),
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
                value -> new TranslatableComponent(valueLabelKey(value)),
                (button, value) -> this.draft.set(key, value.name()));
        return new Control(cycle, () -> cycle.setValue(this.draft.getEnum(key, type)));
    }

    private Control recaptureControl(String key) {
        WdlCycleButton<RecaptureMode> cycle = new WdlCycleButton<>(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT,
                ImmutableList.copyOf(RecaptureMode.values()), this.draft.getEnum(key, RecaptureMode.class),
                value -> new TranslatableComponent(valueLabelKey(value)),
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
            minecraft.setScreen(this);
        };
        // The lost capability differs by target: switching to OFF freezes every area after its first capture,
        // while switching to NEARBY only drops the revisit overwrite (the area around you stays current).
        String messageKey = value == RecaptureMode.OFF
                ? "wdl.settings.confirm.recapture.to_off.message"
                : "wdl.settings.confirm.recapture.to_nearby.message";
        minecraft.setScreen(new WdlCaptureDisableConfirmScreen(choice,
                new TranslatableComponent("wdl.settings.confirm.recapture.title",
                        amberComponent(new TranslatableComponent(valueLabelKey(value)))),
                new TranslatableComponent(messageKey),
                new TranslatableComponent("wdl.settings.confirm.recapture.confirm"),
                new TranslatableComponent("gui.cancel")));
    }

    private Control textControl(String key) {
        EditBox box = new EditBox(0, this.field_1234, 0, 0, CONTROL_WIDTH, CONTROL_HEIGHT);
        box.setMaxLength(32);
        box.setValue(currentText(key));
        box.method_18387((editId, text) -> this.draft.set(key, text));
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
    private void applyTooltip(class_4122 widget, String key) {
        String tooltipKey = SettingsLayout.optionTooltipKey(key);
        if (I18n.exists(tooltipKey)) {
            this.hoverTooltips.add(Maps.immutableEntry(widget, new TranslatableComponent(tooltipKey)));
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
            minecraft.setScreen(this);
        };
        minecraft.setScreen(new WdlCaptureDisableConfirmScreen(choice,
                new TranslatableComponent(base + ".title",
                        amberComponent(new TranslatableComponent(SettingsLayout.optionLabelKey(key)))),
                new TranslatableComponent(SettingsLayout.confirmMessageKey(key)),
                new TranslatableComponent(base + ".confirm"),
                new TranslatableComponent("gui.cancel")));
    }

    private void onDefaults() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ConfirmScreen((confirmed, dialogId) -> {
            if (confirmed) {
                this.draft.revertAllToDefaults();
            }
            minecraft.setScreen(this);
        }, amberComponent(new TranslatableComponent("wdl.settings.defaults.title")).getColoredString(),
                new TranslatableComponent("wdl.settings.defaults.message").getString(),
                I18n.get("wdl.settings.defaults.confirm"),
                I18n.get("gui.cancel"), 0));
    }

    private void onDiscard() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ConfirmScreen((confirmed, dialogId) -> minecraft.setScreen(confirmed ? this.parent
                : this),
                amberComponent(new TranslatableComponent("wdl.settings.discard.title")).getColoredString(),
                new TranslatableComponent("wdl.settings.discard.message").getString(),
                I18n.get("wdl.settings.discard.confirm"),
                I18n.get("gui.cancel"), 0));
    }

    @Override
    public void method_18608() {
        // Only write when something actually changed: a transient read failure at open seeds the draft from
        // DEFAULTS, so an unconditional close-write would overwrite an intact on-disk config with defaults, and
        // even a clean open+close would needlessly re-canonicalize the file.
        if (this.draft.isDirty()) {
            this.onSave.accept(this.draft.toConfig());
        }
        Minecraft.getInstance().setScreen(this.parent);
    }

    private static final class Control {
        private final class_4122 widget;
        private final Runnable refresh;

        Control(class_4122 widget, Runnable refresh) {
            this.widget = widget;
            this.refresh = refresh;
        }

        class_4122 widget() {
            return widget;
        }

        Runnable refresh() {
            return refresh;
        }
    }

    /** The scrollable list of rows for the active tab; rebuilt whole on a tab switch or resize. */
    private final class SettingsList extends class_1802<SettingsEntry> {
        SettingsList(Minecraft minecraft, int width, int height, int top, int bottom, int itemHeight) {
            super(minecraft, width, height, top, bottom, itemHeight);
        }

        void add(SettingsEntry entry) {
            method_18398(entry);
        }

        @Override
        public int method_6706() {
            return contentBandWidth();
        }

        @Override
        protected int method_1069() {
            // This band's default is a fixed width/2 + 124 that assumes the 220-wide default row, so with this
            // screen's wider row it lands inside each row and cuts through the controls; later bands derive it
            // from the row right edge, which this follows.
            return this.field_7734 + this.field_7733 / 2 + method_6706() / 2 + 10;
        }
    }

    /** A list row: a section header, an option, or a game rule. */
    private abstract class SettingsEntry extends class_1802.class_1803<SettingsEntry> {
        private int contentX;
        private int contentY;
        private int contentWidth;

        @Override
        public void method_6700(int rowWidth, int rowHeight, int mouseX, int mouseY, boolean hovering,
                float partialTick) {
            this.contentX = method_18404();
            this.contentY = method_18403();
            this.contentWidth = rowWidth;
            renderContent(mouseX, mouseY, hovering, partialTick);
        }

        abstract void renderContent(int mouseX, int mouseY, boolean hovering, float partialTick);

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
        private final Component label;

        HeaderRow(String labelKey) {
            this.label = new TranslatableComponent(labelKey);
        }

        @Override
        public void renderContent(int mouseX, int mouseY, boolean hovering, float partialTick) {
            RenderSurface surface = new RenderSurfaceImpl();
            int textY = getContentY() + (ROW_HEIGHT - field_1234.lineHeight) / 2 + 2;
            surface.text(field_1234, this.label, getContentX(), textY, BrandColors.opaque(BrandColors.AMBER));
        }
    }

    /** A row with a label, one editing control, and a revert affordance, master-gated when applicable. */
    private abstract class ControlRow extends SettingsEntry {
        private final Component label;
        private final class_356 revert;
        private final @Nullable String masterKey;

        ControlRow(Component label, @Nullable String masterKey) {
            this.label = label;
            this.masterKey = masterKey;
            this.revert = new class_356(0, 0, 0, REVERT_WIDTH, CONTROL_HEIGHT, "") {
                @Override
                public void method_18374(double mouseX, double mouseY) {
                    doRevert();
                    refresh();
                }
            };
            WdlSettingsScreen.this.hoverTooltips.add(
                    Maps.immutableEntry(this.revert, new TranslatableComponent("wdl.settings.defaults")));
        }

        abstract class_4122 control();

        abstract boolean isModified();

        abstract void doRevert();

        abstract void refresh();

        /** Whether this row shows the passive "capture disabled" mark beside its control (off core toggles). */
        boolean showsCaptureWarning() {
            return false;
        }

        @Override
        public void renderContent(int mouseX, int mouseY, boolean hovering, float partialTick) {
            RenderSurface surface = new RenderSurfaceImpl();
            class_4122 control = control();
            boolean enabled = this.masterKey == null || draft.getBoolean(this.masterKey);
            int rowX = getContentX();
            int rowTop = getContentY();
            int controlY = rowTop + (ROW_HEIGHT - CONTROL_HEIGHT) / 2;
            int revertX = rowX + getContentWidth() - REVERT_WIDTH;
            int controlX = revertX - GAP - controlWidth(control);

            int textY = rowTop + (ROW_HEIGHT - field_1234.lineHeight) / 2;
            surface.text(field_1234, this.label, rowX, textY, BrandColors.opaque(enabled ? BrandColors.IVORY
                    : BrandColors.GRAY));

            setControlActive(control, enabled);
            setControlPosition(control, controlX, controlY);
            renderControl(control, mouseX, mouseY, partialTick);

            // Passive indicator: an amber caution mark sits just left of an off core-capture toggle, a
            // defense-in-depth reminder that the download will be missing that data beyond the confirm-at-change.
            if (showsCaptureWarning()) {
                int glyphY = controlY + (CONTROL_HEIGHT - field_1234.lineHeight) / 2;
                surface.text(field_1234, CAPTURE_WARNING_GLYPH,
                        controlX - GAP - field_1234.width(CAPTURE_WARNING_GLYPH), glyphY,
                        BrandColors.opaque(BrandColors.AMBER));
            }

            // The revert affordance is shown only when the row differs from its default, hidden otherwise
            // (not grayed). The control slot above stays reserved either way, so nothing reflows as it appears.
            boolean modified = isModified();
            this.revert.field_1056 = modified;
            this.revert.field_1055 = enabled && modified;
            this.revert.field_1051 = revertX;
            this.revert.field_1052 = controlY;
            this.revert.renderButton(mouseX, mouseY, partialTick);

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
        // each to its control (the slider needs the click and release; the seed field needs the keys) and its
        // revert button.
        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return control().mouseClicked(mouseX, mouseY, button) || this.revert.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            boolean handledControl = control().mouseReleased(mouseX, mouseY, button);
            boolean handledRevert = this.revert.mouseReleased(mouseX, mouseY, button);
            return handledControl || handledRevert;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return control().keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char codePoint, int modifiers) {
            return control().charTyped(codePoint, modifiers);
        }
    }

    /** A descriptor-backed option row: reverts to the descriptor default. */
    private final class OptionRow extends ControlRow {
        private final String key;
        private final class_4122 control;
        private final Runnable refresher;

        OptionRow(String key, class_4122 control, Runnable refresher, @Nullable String masterKey) {
            super(new TranslatableComponent(SettingsLayout.optionLabelKey(key)), masterKey);
            this.key = key;
            this.control = control;
            this.refresher = refresher;
        }

        @Override
        class_4122 control() {
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
            super(new TranslatableComponent(SettingsLayout.gameRuleLabelKey(rule.id())), "overrideGamerules");
            this.rule = rule;
            this.toggle = new WdlCycleButton<>(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT,
                    ImmutableList.of(Boolean.TRUE, Boolean.FALSE), isOn(),
                    value -> value ? new TranslatableComponent("options.on") : new TranslatableComponent("options.off"),
                    (button, value) -> draft.setGameRule(rule.bandId(),
                            value ? rule.enabledValue() : rule.disabledValue(), rule.curatedValue()));
        }

        @Override
        class_4122 control() {
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
    private abstract class RangeSlider extends class_356 {
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
            this.field_1053 = new TranslatableComponent(SettingsLayout.optionValueKey(this.key), currentText())
                    .getString();
        }

        protected void applyValue() {
            draft.set(this.key, currentText());
        }

        void syncFromDraft() {
            this.value = draftFraction();
            updateMessage();
        }

        // The flat button face stands in for the slider groove (method_892 returning 0 selects the base texture
        // row), and the handle is drawn over it here, matching vanilla's own slider.
        @Override
        protected int method_892(boolean hovered) {
            return 0;
        }

        @Override
        protected void renderBg(Minecraft minecraft, int mouseX, int mouseY) {
            if (this.sliding) {
                setValueFromMouse(mouseX);
            }
            minecraft.getTextureManager().bind(field_6282);
            GlStateManager.method_9825(1.0F, 1.0F, 1.0F, 1.0F);
            int handleX = this.field_1051 + (int) (this.value * (this.field_1049 - 8));
            this.method_992(handleX, this.field_1052, 0, 66, 4, 20);
            this.method_992(handleX + 4, this.field_1052, 196, 66, 4, 20);
        }

        @Override
        public void method_18374(double mouseX, double mouseY) {
            setValueFromMouse(mouseX);
            this.sliding = true;
        }

        @Override
        public void method_18376(double mouseX, double mouseY) {
            this.sliding = false;
        }

        private void setValueFromMouse(double mouseX) {
            this.value = Mth.clamp((mouseX - (this.field_1051 + 4)) / (this.field_1049 - 8), 0.0, 1.0);
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
    private static Component amberComponent(Component component) {
        component.getStyle().setColor(ChatFormatting.GOLD);
        return component;
    }
}
