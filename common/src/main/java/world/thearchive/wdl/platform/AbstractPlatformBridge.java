// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.platform;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import world.thearchive.wdl.compat.flashback.FlashbackReplayProbe;
import world.thearchive.wdl.core.ChatCopy;
import world.thearchive.wdl.core.ToastCopy;
import world.thearchive.wdl.core.browse.DownloadFolders;

/**
 * Partial {@link PlatformBridge} carrying the methods that are pure vanilla on every loader: {@link #isRemoteWorld()},
 * {@link #sendChat(ChatCopy)}, and {@link #sendToast(ToastCopy)} use only {@code Minecraft}/{@code Component} APIs that
 * live on the {@code common} classpath, so each loader bridge inherits them instead of duplicating the logic. The
 * loader-specific hooks (keybind, ticks, disconnect, config directory, command registration) stay abstract for the
 * per-loader subclass.
 */
public abstract class AbstractPlatformBridge implements PlatformBridge {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Below 1.20.3 SystemToast has no custom-id class, so these reuse two distinct vanilla toast categories
    // (PERIODIC_NOTIFICATION for job-done, TUTORIAL_HINT for refusals), which keep the vanilla default display
    // time (which the accessibility notification-time multiplier scales). Job-done toasts are always constructed
    // fresh, never addOrUpdate-reset, so each event surfaces its own toast. Refusals use their own category so a
    // repeated click cannot queue a parade: one refusal on screen or in the queue is the whole message, and
    // vanilla addOrUpdate cannot be used instead (its reset path rebuilds the body unwrapped).
    private static final SystemToast.SystemToastIds TOAST_ID = SystemToast.SystemToastIds.PERIODIC_NOTIFICATION;
    private static final SystemToast.SystemToastIds REFUSAL_TOAST_ID = SystemToast.SystemToastIds.TUTORIAL_HINT;

    private static final int ROW_PITCH = 24;

    // Above vanilla's half-row button width (98 on every band) and below its full-row width (200 through 1.13.2,
    // 204 from 1.14.4), so the floor admits a full-row button on every band and no half-row one.
    private static final int MIN_ANCHOR_WIDTH = 100;

    // Resolved on first use, not in the constructor: FabricPlatformBridge pins that every loader call happens
    // inside the methods, never the constructor, and isModLoaded is a loader call. Read only on the client
    // main thread, so the lazy init needs no synchronization.
    private @Nullable FlashbackReplayProbe replayProbe;

    // The row this bridge last built, held to recognize a widget list that was never rebuilt. Client main thread only.
    private @Nullable AbstractWidget lastPrimary;

    @Override
    public final void registerToggleKeybind(Runnable onToggle) {
        registerKeybind("key.wdl.toggle", onToggle);
    }

    @Override
    public final void registerDownloadsKeybind(Runnable onOpen) {
        registerKeybind("key.wdl.open_downloads", onOpen);
    }

    /**
     * Register an unbound key mapping for {@code keyId} under the wdl category; {@code onPress} runs once per press on
     * the client main thread.
     */
    protected abstract void registerKeybind(String keyId, Runnable onPress);

    @Override
    public boolean isRemoteWorld() {
        Minecraft mc = Minecraft.getInstance();
        // getConnection() is null until Minecraft.player is assigned, which is what keeps this false during
        // the world-load window where a replay server is already installed and the client is already ticking.
        if (mc.getConnection() == null) {
            return false;
        }
        return !mc.isLocalServer() || isReplayPlayback();
    }

    // Kept private so nothing invites an off-main-thread read from the coverage overlay, which would break the
    // lazy init above. isInstance(null) is false, which is the sole guard over the shutdown window where the
    // singleplayer server field is already cleared but isLocalServer is not.
    private boolean isReplayPlayback() {
        if (replayProbe == null) {
            replayProbe = FlashbackReplayProbe.resolve(isModLoaded(FlashbackReplayProbe.MOD_ID));
        }
        return replayProbe.isReplayServer(Minecraft.getInstance().getSingleplayerServer());
    }

    @Override
    public boolean isBlockingScreenOpen() {
        Screen screen = Minecraft.getInstance().screen;
        return screen != null && !(screen instanceof ChatScreen);
    }

    @Override
    public boolean isHudHidden() {
        return Minecraft.getInstance().options.hideGui;
    }

    @Override
    public void sendChat(ChatCopy line) {
        StringBuilder linkTargets = new StringBuilder();
        MutableComponent rendered = render(line, linkTargets);
        LOGGER.info(rendered.getString() + linkTargets);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(rendered, false);
        }
    }

    private static MutableComponent render(ChatCopy line, StringBuilder linkTargets) {
        Object[] renderedArguments = new Object[line.arguments().size()];
        int slot = 0;
        for (ChatCopy.Argument argument : line.arguments()) {
            MutableComponent rendered = argument.translationKey() == null
                    ? Component.literal(argument.text())
                    : argument.text().isEmpty()
                            ? Component.translatable(argument.translationKey())
                            : Component.translatable(argument.translationKey(), argument.text());
            if (argument.color().isPresent()) {
                rendered = rendered.withStyle(style -> style.withColor(argument.color().getAsInt()));
            }
            ChatCopy.Click click = argument.click();
            if (click != null) {
                ClickEvent clickEvent = click.kind() == ChatCopy.Click.Kind.OPEN_URL
                        ? new ClickEvent(ClickEvent.Action.OPEN_URL, click.target())
                        : new ClickEvent(ClickEvent.Action.OPEN_FILE, click.target());
                rendered = rendered.withStyle(style -> style.withClickEvent(clickEvent)
                        .withHoverEvent(
                                new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(click.target()))));
                linkTargets.append(" <").append(click.target()).append('>');
            }
            renderedArguments[slot++] = rendered;
        }
        MutableComponent rendered = Component.translatable(line.translationKey(), renderedArguments);
        if (line.templateColor().isPresent()) {
            rendered = rendered.withStyle(style -> style.withColor(line.templateColor().getAsInt()));
        }
        return rendered;
    }

    @Override
    public void sendToast(ToastCopy toast) {
        Object[] renderedArguments = new Object[toast.arguments().size()];
        int slot = 0;
        for (ToastCopy.Argument argument : toast.arguments()) {
            MutableComponent rendered = argument.translationKey() == null
                    ? Component.literal(argument.text())
                    : argument.text().isEmpty()
                            ? Component.translatable(argument.translationKey())
                            : Component.translatable(argument.translationKey(), argument.text());
            if (argument.color().isPresent()) {
                rendered = rendered.withStyle(style -> style.withColor(argument.color().getAsInt()));
            }
            renderedArguments[slot++] = rendered;
        }
        MutableComponent body = Component.translatable(toast.bodyKey(), renderedArguments);
        if (toast.bodyColor().isPresent()) {
            body = body.withStyle(style -> style.withColor(toast.bodyColor().getAsInt()));
        }
        Minecraft mc = Minecraft.getInstance();
        SystemToast.SystemToastIds id = toast.refusal() ? REFUSAL_TOAST_ID : TOAST_ID;
        if (toast.refusal() && mc.getToasts().getToast(SystemToast.class, id) != null) {
            return;
        }
        mc.getToasts().addToast(SystemToast.multiline(mc, id,
                Component.translatable(toast.titleKey()), body));
    }

    /**
     * Build the wdl pause-menu row (a primary action button plus a settings button) above the vanilla disconnect
     * button, shifting that button and everything below it in the same column down to open the row. Returns the widgets
     * for the loader to add through its own screen hook; the layout is loader-agnostic. Returns none in the user's own
     * local world and on a pause screen whose anchor cannot be identified, shifting nothing in those cases.
     */
    protected List<AbstractWidget> buildPauseMenuRow(Screen screen, List<AbstractWidget> widgets,
            Supplier<String> primaryLabelKey, BooleanSupplier primaryEnabled, Runnable onPrimary,
            Runnable onConfig) {
        // A local world refuses every action this row leads to, and the /wdl commands and downloads keybind
        // still reach the settings and downloads screens there, so the row is hidden rather than disabled.
        // Replay playback is a remote world, so the row is present there and its actions work.
        if (!isRemoteWorld()) {
            return List.of();
        }
        // A loader can fire its screen-init hook against a widget list that was never rebuilt, and building again
        // against the surviving list stacks a second row and shifts the column another 24 on every open.
        if (lastPrimary != null && widgets.contains(lastPrimary)) {
            return List.of();
        }
        AbstractWidget anchor = anchor(screen, widgets);
        if (anchor == null) {
            return List.of();
        }
        int x = anchor.getX();
        int y = anchor.getY();
        int width = anchor.getWidth();
        // Bounded to the anchor's own column. Shifting everything lower drags a corner button off the screen edge;
        // shifting only what spans the center strands the half-width and non-button rows a mod appends below.
        for (AbstractWidget widget : widgets) {
            if (movesWithAnchor(widget, x, y, width)) {
                widget.setY(widget.getY() + ROW_PITCH);
            }
        }
        Button primary = Button.builder(Component.translatable(primaryLabelKey.get()), button -> onPrimary.run())
                .bounds(x, y, width - 24, 20).build();
        primary.active = primaryEnabled.getAsBoolean();
        Button config = Button.builder(Component.literal("..."), button -> onConfig.run())
                .tooltip(Tooltip.create(Component.translatable("wdl.pause.settings.tooltip")))
                .bounds(x + width - 20, y, 20, 20).build();
        lastPrimary = primary;
        return List.of(primary, config);
    }

    static boolean movesWithAnchor(AbstractWidget widget, int anchorX, int anchorY, int anchorWidth) {
        return widget.getY() >= anchorY && widget.getX() < anchorX + anchorWidth
                && widget.getX() + widget.getWidth() > anchorX;
    }

    private @Nullable AbstractWidget anchor(Screen screen, List<AbstractWidget> widgets) {
        return anchor(disconnectButton(screen), widgets, screen.width, screen.height);
    }

    static @Nullable AbstractWidget anchor(@Nullable AbstractWidget disconnect, List<AbstractWidget> widgets,
            int screenWidth, int screenHeight) {
        // The named button is read off the screen rather than out of the list, and Init.Post can fire on a loader
        // where another mod cancelled Init.Pre so init() never ran, leaving a stale field beside a cleared list.
        if (disconnect != null && usable(disconnect, screenWidth, screenHeight) && widgets.contains(disconnect)) {
            return disconnect;
        }
        return lowestColumnButton(widgets, screenWidth, screenHeight);
    }

    // Vanilla's own half-row button is 98 wide on every band and never spans the center, so these tests reject it
    // and every corner button. They do not separate the column from a mod's own full-width centered button, which is
    // why the named button wins where the band has one. The Button test excludes the screen title, a StringWidget.
    private static @Nullable AbstractWidget lowestColumnButton(List<AbstractWidget> widgets, int screenWidth,
            int screenHeight) {
        int center = screenWidth / 2;
        AbstractWidget lowest = null;
        for (AbstractWidget widget : widgets) {
            if (!(widget instanceof Button) || !usable(widget, screenWidth, screenHeight)
                    || widget.getX() > center || widget.getX() + widget.getWidth() < center) {
                continue;
            }
            if (lowest == null || widget.getY() > lowest.getY()) {
                lowest = widget;
            }
        }
        return lowest;
    }

    // A mod may leave vanilla's disconnect button in the list but park it off the screen and put its own button in
    // the slot, so a candidate not wholly on screen carries the row off screen with it.
    private static boolean usable(AbstractWidget widget, int screenWidth, int screenHeight) {
        return widget.getWidth() >= MIN_ANCHOR_WIDTH
                && widget.getX() >= 0 && widget.getX() + widget.getWidth() <= screenWidth
                && widget.getY() >= 0 && widget.getY() + widget.getHeight() <= screenHeight;
    }

    /**
     * Vanilla's own reference to the pause screen's disconnect button, or null on a band whose pause screen keeps no
     * such field. The per-loader plug widens or transforms the non-public field.
     */
    protected abstract @Nullable AbstractWidget disconnectButton(Screen pauseScreen);

    /** Run a /wdl action and report Brigadier success. */
    private static int run(Runnable action) {
        action.run();
        return 1;
    }

    /** Suggest the wdl-managed download names for {@code /wdl resume} tab-completion. */
    private static CompletableFuture<Suggestions> suggestDownloads(SuggestionsBuilder builder) {
        Path savesDirectory = Minecraft.getInstance().getLevelSource().getBaseDir();
        try {
            return SharedSuggestionProvider.suggest(DownloadFolders.listManaged(savesDirectory), builder);
        } catch (IOException exception) {
            return builder.buildFuture(); // no suggestions if the saves directory cannot be read
        }
    }

    /**
     * Build the /wdl command tree once, source-generic, so each loader registers the same grammar with only its own
     * literal and argument builder factories. The action lambdas never read the command source.
     */
    protected static <S> LiteralArgumentBuilder<S> wdlCommandTree(WdlCommands commands,
            Function<String, LiteralArgumentBuilder<S>> literal,
            BiFunction<String, ArgumentType<String>, RequiredArgumentBuilder<S, String>> argument) {
        return literal.apply("wdl")
                .executes(context -> run(commands.status())) // bare /wdl shows status, never auto-starts
                .then(literal.apply("start")
                        .executes(context -> run(commands.start()))
                        .then(argument.apply("name", StringArgumentType.greedyString())
                                .executes(context -> run(() -> commands.startNamed()
                                        .accept(StringArgumentType.getString(context, "name"))))))
                .then(literal.apply("stop").executes(context -> run(commands.stop())))
                .then(literal.apply("status").executes(context -> run(commands.status())))
                .then(literal.apply("config").executes(context -> run(commands.config())))
                .then(literal.apply("downloads").executes(context -> run(commands.openDownloads())))
                .then(literal.apply("resume").then(argument.apply("folder", StringArgumentType.greedyString())
                        .suggests((context, builder) -> suggestDownloads(builder))
                        .executes(context -> run(() -> commands.resume()
                                .accept(StringArgumentType.getString(context, "folder"))))));
    }
}
