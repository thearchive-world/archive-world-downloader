// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.platform;

import com.google.common.collect.ImmutableList;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.impl.RenderSurfaceImpl;
import world.thearchive.wdl.compat.flashback.FlashbackReplayProbe;
import world.thearchive.wdl.core.ChatCopy;
import world.thearchive.wdl.core.ToastCopy;
import world.thearchive.wdl.core.browse.DownloadFolders;

public abstract class AbstractPlatformBridge implements PlatformBridge {
    private static final Logger LOGGER = LogManager.getLogger(AbstractPlatformBridge.class);

    // A repeated click cannot queue a parade: one refusal on screen or in the queue is the whole message.
    private static final SystemToast.SystemToastIds TOAST_ID = SystemToast.SystemToastIds.WORLD_BACKUP;
    private static final SystemToast.SystemToastIds REFUSAL_TOAST_ID = SystemToast.SystemToastIds.TUTORIAL_HINT;

    private static final int ROW_PITCH = 24;

    // Above vanilla's half-row button width (98 on every band) and below its full-row width (200 through 1.13.2,
    // 204 from 1.14.4), so the floor admits a full-row button on every band and no half-row one.
    private static final int MIN_ANCHOR_WIDTH = 100;

    // Read only on the client main thread, so the lazy init needs no synchronization.
    private @Nullable FlashbackReplayProbe replayProbe;

    // Client main thread only.
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
     * Register an unbound key mapping for {@code keyId} under the wdl category; {@code onPress} runs on the client main
     * thread.
     */
    protected abstract void registerKeybind(String keyId, Runnable onPress);

    @Override
    public boolean isRemoteWorld() {
        Minecraft mc = Minecraft.getInstance();
        // getConnection() is null until Minecraft.player is assigned, which is what keeps this false during the
        // world-load window where a replay server is already installed.
        if (mc.getConnection() == null) {
            return false;
        }
        return !mc.isLocalServer() || isReplayPlayback();
    }

    // isInstance(null) is false, which is the sole guard over the shutdown window where the singleplayer server field
    // is already cleared but isLocalServer is not.
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
        Component rendered = render(line, linkTargets);
        LOGGER.info(rendered.getString() + linkTargets);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(rendered, false);
        }
    }

    private static Component render(ChatCopy line, StringBuilder linkTargets) {
        Object[] renderedArguments = new Object[line.arguments().size()];
        int slot = 0;
        for (ChatCopy.Argument argument : line.arguments()) {
            Component rendered = argument.translationKey() == null
                    ? new TextComponent(argument.text())
                    : argument.text().isEmpty()
                            ? new TranslatableComponent(argument.translationKey())
                            : new TranslatableComponent(argument.translationKey(), argument.text());
            if (argument.color().isPresent()) {
                rendered.getStyle().setColor(nearestFormatting(argument.color().getAsInt()));
            }
            ChatCopy.Click click = argument.click();
            if (click != null) {
                ClickEvent clickEvent = click.kind() == ChatCopy.Click.Kind.OPEN_URL
                        ? new ClickEvent(ClickEvent.Action.OPEN_URL, click.target())
                        : new ClickEvent(ClickEvent.Action.OPEN_FILE, click.target());
                rendered.getStyle().setClickEvent(clickEvent)
                        .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponent(click.target())));
                linkTargets.append(" <").append(click.target()).append('>');
            }
            renderedArguments[slot++] = rendered;
        }
        Component rendered = new TranslatableComponent(line.translationKey(), renderedArguments);
        if (line.templateColor().isPresent()) {
            rendered.getStyle().setColor(nearestFormatting(line.templateColor().getAsInt()));
        }
        return rendered;
    }

    private static ChatFormatting nearestFormatting(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        ChatFormatting nearest = ChatFormatting.WHITE;
        int best = Integer.MAX_VALUE;
        for (ChatFormatting formatting : ChatFormatting.values()) {
            Integer color = formatting.getColor();
            if (color == null) {
                continue;
            }
            int deltaRed = ((color >> 16) & 0xFF) - red;
            int deltaGreen = ((color >> 8) & 0xFF) - green;
            int deltaBlue = (color & 0xFF) - blue;
            int distance = deltaRed * deltaRed + deltaGreen * deltaGreen + deltaBlue * deltaBlue;
            if (distance < best) {
                best = distance;
                nearest = formatting;
            }
        }
        return nearest;
    }

    @Override
    public void sendToast(ToastCopy toast) {
        Object[] renderedArguments = new Object[toast.arguments().size()];
        int slot = 0;
        for (ToastCopy.Argument argument : toast.arguments()) {
            Component rendered = argument.translationKey() == null
                    ? new TextComponent(argument.text())
                    : argument.text().isEmpty()
                            ? new TranslatableComponent(argument.translationKey())
                            : new TranslatableComponent(argument.translationKey(), argument.text());
            if (argument.color().isPresent()) {
                rendered.getStyle().setColor(nearestFormatting(argument.color().getAsInt()));
            }
            renderedArguments[slot++] = rendered;
        }
        Component body = new TranslatableComponent(toast.bodyKey(), renderedArguments);
        if (toast.bodyColor().isPresent()) {
            body.getStyle().setColor(nearestFormatting(toast.bodyColor().getAsInt()));
        }
        Minecraft mc = Minecraft.getInstance();
        SystemToast.SystemToastIds id = toast.refusal() ? REFUSAL_TOAST_ID : TOAST_ID;
        if (toast.refusal() && mc.getToasts().getToast(SystemToast.class, id) != null) {
            return;
        }
        if (toast.refusal()) {
            // The vanilla SystemToast keeps the getToast dedup above.
            mc.getToasts().addToast(new SystemToast(id, new TranslatableComponent(toast.titleKey()), body));
            return;
        }
        // A job-done body can carry a newline, which SystemToast draws as one overrunning line; a multiline toast wraps
        // it.
        int bodyRgb = toast.bodyColor().isPresent() ? 0xFF000000 | toast.bodyColor().getAsInt() : -1;
        mc.getToasts().addToast(new WdlMultilineToast(mc.font,
                new TranslatableComponent(toast.titleKey()).getString(), body.getString(), bodyRgb));
    }

    /**
     * Build the wdl pause-menu row (a primary action button plus a settings button) above the lowest button of the menu
     * column, shifting that button and everything below it in the same column down to open the row. Returns the widgets
     * for the loader to add. Returns none in the user's own local world and on a pause screen with no identifiable
     * anchor, shifting nothing in those cases.
     */
    protected List<AbstractWidget> buildPauseMenuRow(Screen screen, List<AbstractWidget> widgets,
            Supplier<String> primaryLabelKey, BooleanSupplier primaryEnabled, Runnable onPrimary,
            Runnable onConfig) {
        if (!isRemoteWorld()) {
            return ImmutableList.of();
        }
        // A loader can fire its screen-init hook against a widget list that was never rebuilt, and building again
        // against the surviving list stacks a second row and shifts the column another 24 on every open.
        if (lastPrimary != null && widgets.contains(lastPrimary)) {
            return ImmutableList.of();
        }
        AbstractWidget anchor = lowestColumnButton(widgets, screen.width);
        if (anchor == null) {
            return ImmutableList.of();
        }
        int x = anchor.x;
        int y = anchor.y;
        int width = anchor.getWidth();
        // Bounded to the anchor's own column. Shifting everything lower drags a corner button off the screen edge;
        // shifting only what spans the center strands the half-width rows a mod appends below.
        for (AbstractWidget widget : widgets) {
            if (movesWithAnchor(widget, x, y, width)) {
                widget.y = widget.y + ROW_PITCH;
            }
        }
        Button primary = new Button(x, y, width - 24, 20, I18n.get(primaryLabelKey.get()),
                button -> onPrimary.run());
        primary.active = primaryEnabled.getAsBoolean();
        Button config = new Button(x + width - 20, y, 20, 20, "...", button -> onConfig.run()) {
            @Override
            public void renderButton(int mouseX, int mouseY, float partialTick) {
                super.renderButton(mouseX, mouseY, partialTick);
                Screen current = Minecraft.getInstance().screen;
                if (this.isHovered() && current != null) {
                    new RenderSurfaceImpl(current).tooltip(Minecraft.getInstance().font,
                            ImmutableList.of(new TranslatableComponent("wdl.pause.settings.tooltip")),
                            mouseX, mouseY);
                }
            }
        };
        lastPrimary = primary;
        return ImmutableList.of(primary, config);
    }

    static boolean movesWithAnchor(AbstractWidget widget, int anchorX, int anchorY, int anchorWidth) {
        return widget.y >= anchorY && widget.x < anchorX + anchorWidth
                && widget.x + widget.getWidth() > anchorX;
    }

    static @Nullable AbstractWidget lowestColumnButton(List<AbstractWidget> widgets, int screenWidth) {
        int center = screenWidth / 2;
        AbstractWidget lowest = null;
        for (AbstractWidget widget : widgets) {
            if (!(widget instanceof Button) || widget.getWidth() < MIN_ANCHOR_WIDTH
                    || widget.x > center || widget.x + widget.getWidth() < center) {
                continue;
            }
            if (lowest == null || widget.y > lowest.y) {
                lowest = widget;
            }
        }
        return lowest;
    }

    private static int run(Runnable action) {
        action.run();
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestDownloads(SuggestionsBuilder builder) {
        Path savesDirectory = Minecraft.getInstance().getLevelSource().getBaseDir();
        try {
            return SharedSuggestionProvider.suggest(DownloadFolders.listManaged(savesDirectory), builder);
        } catch (IOException exception) {
            return builder.buildFuture();
        }
    }

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
