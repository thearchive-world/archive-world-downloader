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
import java.net.URI;
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

    // The no-argument ids keep the vanilla default display time (which the accessibility
    // notification-time multiplier scales). Job-done toasts are always constructed fresh, never
    // addOrUpdate-reset, so each event surfaces its own toast. Refusals get their own id so a repeated
    // click cannot queue a parade: one refusal on screen or in the queue is the whole message, and
    // vanilla addOrUpdate cannot be used instead (its reset path rebuilds the body unwrapped).
    private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId();
    private static final SystemToast.SystemToastId REFUSAL_TOAST_ID = new SystemToast.SystemToastId();

    // Resolved on first use, not in the constructor: FabricPlatformBridge pins that every loader call happens
    // inside the methods, never the constructor, and isModLoaded is a loader call. Read only on the client
    // main thread, so the lazy init needs no synchronization.
    private @Nullable FlashbackReplayProbe replayProbe;

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
                rendered = rendered.withColor(argument.color().getAsInt());
            }
            ChatCopy.Click click = argument.click();
            if (click != null) {
                ClickEvent clickEvent = click.kind() == ChatCopy.Click.Kind.OPEN_URL
                        ? new ClickEvent.OpenUrl(URI.create(click.target()))
                        : new ClickEvent.OpenFile(click.target());
                rendered = rendered.withStyle(style -> style.withClickEvent(clickEvent)
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(click.target()))));
                linkTargets.append(" <").append(click.target()).append('>');
            }
            renderedArguments[slot++] = rendered;
        }
        MutableComponent rendered = Component.translatable(line.translationKey(), renderedArguments);
        if (line.templateColor().isPresent()) {
            rendered = rendered.withColor(line.templateColor().getAsInt());
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
                rendered = rendered.withColor(argument.color().getAsInt());
            }
            renderedArguments[slot++] = rendered;
        }
        MutableComponent body = Component.translatable(toast.bodyKey(), renderedArguments);
        if (toast.bodyColor().isPresent()) {
            body = body.withColor(toast.bodyColor().getAsInt());
        }
        Minecraft mc = Minecraft.getInstance();
        SystemToast.SystemToastId id = toast.refusal() ? REFUSAL_TOAST_ID : TOAST_ID;
        if (toast.refusal() && mc.getToastManager().getToast(SystemToast.class, id) != null) {
            return;
        }
        mc.getToastManager().addToast(SystemToast.multiline(mc, id,
                Component.translatable(toast.titleKey()), body));
    }

    /**
     * Build the wdl pause-menu row (a primary action button plus a settings button) above {@code anchor}, shifting the
     * anchor down to open the row. Returns the widgets for the loader to add through its own screen hook; the layout is
     * loader-agnostic. Returns none in the user's own local world, leaving the anchor unshifted so the vanilla menu
     * keeps its own spacing.
     */
    protected List<AbstractWidget> buildPauseMenuRow(AbstractWidget anchor,
            Supplier<String> primaryLabelKey, BooleanSupplier primaryEnabled, Runnable onPrimary,
            Runnable onConfig) {
        // A local world refuses every action this row leads to, and the /wdl commands and downloads keybind
        // still reach the settings and downloads screens there, so the row is hidden rather than disabled.
        // Replay playback is a remote world, so the row is present there and its actions work.
        if (!isRemoteWorld()) {
            return List.of();
        }
        int x = anchor.getX();
        int y = anchor.getY();
        int width = anchor.getWidth();
        anchor.setY(y + 24); // shift the bottom button (Disconnect) down to open a row above it
        Button primary = Button.builder(Component.translatable(primaryLabelKey.get()), button -> onPrimary.run())
                .bounds(x, y, width - 24, 20).build();
        primary.active = primaryEnabled.getAsBoolean();
        Button config = Button.builder(Component.literal("..."), button -> onConfig.run())
                .tooltip(Tooltip.create(Component.translatable("wdl.pause.settings.tooltip")))
                .bounds(x + width - 20, y, 20, 20).build();
        return List.of(primary, config);
    }

    /** The lowest existing pause-menu button (Disconnect), the anchor to insert the wdl row above. */
    protected static @Nullable AbstractWidget lowest(List<AbstractWidget> widgets) {
        AbstractWidget lowest = null;
        for (AbstractWidget widget : widgets) {
            if (lowest == null || widget.getY() > lowest.getY()) {
                lowest = widget;
            }
        }
        return lowest;
    }

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
