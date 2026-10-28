// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.platform;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.resources.I18n;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.impl.RenderSurfaceImpl;
import world.thearchive.wdl.client.WdlToastOverlay;
import world.thearchive.wdl.compat.flashback.FlashbackReplayProbe;
import world.thearchive.wdl.core.ChatCopy;
import world.thearchive.wdl.core.ToastCopy;
import world.thearchive.wdl.core.browse.DownloadFolders;

public abstract class AbstractPlatformBridge implements PlatformBridge {
    private static final Logger LOGGER = LogManager.getLogger(AbstractPlatformBridge.class);

    private static final int ROW_PITCH = 24;

    // Above vanilla's half-row button width (98 on every band) and below its full-row width (200 through 1.13.2,
    // 204 from 1.14.4), so the floor admits a full-row button on every band and no half-row one.
    private static final int MIN_ANCHOR_WIDTH = 100;

    // Read only on the client main thread, so the lazy init needs no synchronization.
    private @Nullable FlashbackReplayProbe replayProbe;

    // Client main thread only.
    private @Nullable GuiButton lastPrimary;

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
        Minecraft mc = Minecraft.getMinecraft();
        // getConnection() is null until Minecraft.player is assigned, which is what keeps this false during the
        // world-load window where a replay server is already installed.
        if (mc.getConnection() == null) {
            return false;
        }
        return !mc.isSingleplayer() || isReplayPlayback();
    }

    @Override
    public boolean isConnectionClosed() {
        NetHandlerPlayClient connection = Minecraft.getMinecraft().getConnection();
        return connection == null || !connection.getNetworkManager().isChannelOpen();
    }

    private boolean isReplayPlayback() {
        if (replayProbe == null) {
            replayProbe = FlashbackReplayProbe.resolve(isModLoaded(FlashbackReplayProbe.MOD_ID));
        }
        return replayProbe.isReplayServer(Minecraft.getMinecraft().getIntegratedServer());
    }

    @Override
    public boolean isBlockingScreenOpen() {
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        return screen != null && !(screen instanceof GuiChat);
    }

    @Override
    public boolean isHudHidden() {
        return Minecraft.getMinecraft().gameSettings.hideGUI;
    }

    @Override
    public void sendChat(ChatCopy line) {
        StringBuilder linkTargets = new StringBuilder();
        ITextComponent rendered = render(line, linkTargets);
        LOGGER.info(rendered.getUnformattedText() + linkTargets);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player != null) {
            mc.player.sendStatusMessage(rendered);
        }
    }

    private static ITextComponent render(ChatCopy line, StringBuilder linkTargets) {
        Object[] renderedArguments = new Object[line.arguments().size()];
        int slot = 0;
        for (ChatCopy.Argument argument : line.arguments()) {
            ITextComponent rendered = argument.translationKey() == null
                    ? new TextComponentString(argument.text())
                    : argument.text().isEmpty()
                            ? new TextComponentTranslation(argument.translationKey())
                            : new TextComponentTranslation(argument.translationKey(), argument.text());
            if (argument.color().isPresent()) {
                rendered.getStyle().setColor(nearestFormatting(argument.color().getAsInt()));
            }
            ChatCopy.Click click = argument.click();
            if (click != null) {
                ClickEvent clickEvent = click.kind() == ChatCopy.Click.Kind.OPEN_URL
                        ? new ClickEvent(ClickEvent.Action.OPEN_URL, click.target())
                        : new ClickEvent(ClickEvent.Action.OPEN_FILE, click.target());
                rendered.getStyle().setClickEvent(clickEvent)
                        .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                new TextComponentString(click.target())));
                linkTargets.append(" <").append(click.target()).append('>');
            }
            renderedArguments[slot++] = rendered;
        }
        ITextComponent rendered = new TextComponentTranslation(line.translationKey(), renderedArguments);
        if (line.templateColor().isPresent()) {
            rendered.getStyle().setColor(nearestFormatting(line.templateColor().getAsInt()));
        }
        return rendered;
    }

    private static TextFormatting nearestFormatting(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        TextFormatting nearest = TextFormatting.WHITE;
        int best = Integer.MAX_VALUE;
        for (TextFormatting formatting : TextFormatting.values()) {
            if (!formatting.isColor()) {
                continue;
            }
            int color = colorTableRgb(formatting.getColorIndex());
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

    private static int colorTableRgb(int index) {
        int base = (index >> 3 & 1) * 85;
        int red = (index >> 2 & 1) * 170 + base;
        int green = (index >> 1 & 1) * 170 + base;
        int blue = (index & 1) * 170 + base;
        if (index == 6) {
            red += 85;
        }
        return (red & 255) << 16 | (green & 255) << 8 | (blue & 255);
    }

    @Override
    public void sendToast(ToastCopy toast) {
        Object[] renderedArguments = new Object[toast.arguments().size()];
        int slot = 0;
        for (ToastCopy.Argument argument : toast.arguments()) {
            ITextComponent rendered = argument.translationKey() == null
                    ? new TextComponentString(argument.text())
                    : argument.text().isEmpty()
                            ? new TextComponentTranslation(argument.translationKey())
                            : new TextComponentTranslation(argument.translationKey(), argument.text());
            if (argument.color().isPresent()) {
                rendered.getStyle().setColor(nearestFormatting(argument.color().getAsInt()));
            }
            renderedArguments[slot++] = rendered;
        }
        ITextComponent body = new TextComponentTranslation(toast.bodyKey(), renderedArguments);
        if (toast.bodyColor().isPresent()) {
            body.getStyle().setColor(nearestFormatting(toast.bodyColor().getAsInt()));
        }
        int bodyRgb = toast.bodyColor().isPresent() ? 0xFF000000 | toast.bodyColor().getAsInt() : -1;
        WdlToastOverlay.show(new TextComponentTranslation(toast.titleKey()).getUnformattedText(),
                body.getUnformattedText(), bodyRgb, toast.refusal());
    }

    /**
     * Build the wdl pause-menu row (a primary action button plus a settings button) above the lowest button of the menu
     * column, shifting that button and everything below it in the same column down to open the row. Returns the widgets
     * for the loader to add. Returns none in the user's own local world and on a pause screen with no identifiable
     * anchor, shifting nothing in those cases.
     */
    protected List<GuiButton> buildPauseMenuRow(List<GuiButton> widgets,
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
        GuiButton anchor = lowestColumnButton(widgets, new ScaledResolution(Minecraft.getMinecraft()).getScaledWidth());
        if (anchor == null) {
            return ImmutableList.of();
        }
        int x = anchor.xPosition;
        int y = anchor.yPosition;
        int width = anchor.width;
        // Bounded to the anchor's own column. Shifting everything lower drags a corner button off the screen edge;
        // shifting only what spans the center strands the half-width rows a mod appends below.
        for (GuiButton widget : widgets) {
            if (movesWithAnchor(widget, x, y, width)) {
                widget.yPosition = widget.yPosition + ROW_PITCH;
            }
        }
        GuiButton primary = new WdlMenuButton(x, y, width - 24, 20, I18n.format(primaryLabelKey.get()), onPrimary);
        primary.enabled = primaryEnabled.getAsBoolean();
        GuiButton config = new WdlMenuButton(x + width - 20, y, 20, 20, "...", onConfig,
                "wdl.pause.settings.tooltip");
        lastPrimary = primary;
        return ImmutableList.of(primary, config);
    }

    static boolean movesWithAnchor(GuiButton widget, int anchorX, int anchorY, int anchorWidth) {
        return widget.yPosition >= anchorY && widget.xPosition < anchorX + anchorWidth
                && widget.xPosition + widget.width > anchorX;
    }

    static @Nullable GuiButton lowestColumnButton(List<GuiButton> widgets, int screenWidth) {
        int center = screenWidth / 2;
        GuiButton lowest = null;
        for (GuiButton widget : widgets) {
            if (widget.width < MIN_ANCHOR_WIDTH || widget.xPosition > center
                    || widget.xPosition + widget.width < center) {
                continue;
            }
            if (lowest == null || widget.yPosition > lowest.yPosition) {
                lowest = widget;
            }
        }
        return lowest;
    }

    /**
     * The pause-menu buttons this band builds: a plain {@link GuiButton} carrying its own press action. The loader's
     * action-performed hook calls {@link #press()}.
     */
    public static final class WdlMenuButton extends GuiButton {
        // A pause-menu button id outside GuiIngameMenu's own 0..12 range, so the vanilla screen's actionPerformed
        // switch never claims a click on this row.
        private static final int ID = 0x77646C01;
        private final Runnable action;
        private final @Nullable String tooltipKey;

        public WdlMenuButton(int x, int y, int width, int height, String label, Runnable action) {
            this(x, y, width, height, label, action, null);
        }

        public WdlMenuButton(int x, int y, int width, int height, String label, Runnable action,
                @Nullable String tooltipKey) {
            super(ID, x, y, width, height, label);
            this.action = action;
            this.tooltipKey = tooltipKey;
        }

        @Override
        public void drawButton(Minecraft minecraft, int mouseX, int mouseY) {
            super.drawButton(minecraft, mouseX, mouseY);
            GuiScreen current = minecraft.currentScreen;
            if (this.tooltipKey != null && this.visible && this.hovered && current != null) {
                new RenderSurfaceImpl(current).tooltip(minecraft.fontRendererObj,
                        ImmutableList.of(new TextComponentTranslation(this.tooltipKey)), mouseX, mouseY);
            }
        }

        /** Run the button's action. */
        public void press() {
            action.run();
        }
    }

    /**
     * Build the {@code /wdl} client command over the {@link WdlCommands} actions: a classic {@link CommandBase} whose
     * actions run on the client main thread (the client command handler dispatches there).
     */
    protected static ICommand wdlCommand(WdlCommands commands) {
        return new WdlClientCommand(commands);
    }

    private static final class WdlClientCommand extends CommandBase {
        private static final List<String> SUBCOMMANDS = ImmutableList.of("start", "stop", "status", "config",
                "downloads", "resume");

        private final WdlCommands commands;

        WdlClientCommand(WdlCommands commands) {
            this.commands = commands;
        }

        @Override
        public String getName() {
            return "wdl";
        }

        @Override
        public String getUsage(ICommandSender sender) {
            return "/wdl [start [name] | stop | status | config | downloads | resume <folder>]";
        }

        // A client command must run for any player on a foreign server, so it bypasses the vanilla
        // operator-permission gate the default checkPermission applies.
        @Override
        public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
            return true;
        }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
            if (args.length == 0) {
                commands.status().run(); // bare /wdl shows status, never auto-starts
                return;
            }
            switch (args[0]) {
                case "start":
                    if (args.length == 1) {
                        commands.start().run();
                    } else {
                        commands.startNamed().accept(buildString(args, 1));
                    }
                    break;
                case "stop":
                    commands.stop().run();
                    break;
                case "status":
                    commands.status().run();
                    break;
                case "config":
                    commands.config().run();
                    break;
                case "downloads":
                    commands.openDownloads().run();
                    break;
                case "resume":
                    if (args.length >= 2) {
                        commands.resume().accept(buildString(args, 1));
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                @Nullable BlockPos targetPos) {
            if (args.length == 1) {
                return getListOfStringsMatchingLastWord(args, SUBCOMMANDS);
            }
            if (args.length >= 2 && "resume".equals(args[0])) {
                return getListOfStringsMatchingLastWord(args, managedDownloadNames());
            }
            return ImmutableList.of();
        }

        private static List<String> managedDownloadNames() {
            Path savesDirectory = Minecraft.getMinecraft().mcDataDir.toPath().resolve("saves");
            try {
                return DownloadFolders.listManaged(savesDirectory);
            } catch (IOException exception) {
                return ImmutableList.of();
            }
        }
    }
}
