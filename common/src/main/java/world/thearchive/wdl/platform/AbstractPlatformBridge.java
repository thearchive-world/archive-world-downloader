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

import world.thearchive.wdl.client.WdlToastOverlay;
import world.thearchive.wdl.compat.flashback.FlashbackReplayProbe;
import world.thearchive.wdl.core.ChatCopy;
import world.thearchive.wdl.core.ToastCopy;
import world.thearchive.wdl.core.browse.DownloadFolders;

/**
 * Partial {@link PlatformBridge} carrying the methods that are pure vanilla on every loader: {@link #isRemoteWorld()},
 * {@link #sendChat(ChatCopy)}, and {@link #sendToast(ToastCopy)} use only {@code Minecraft}/{@code ITextComponent} APIs
 * that live on the {@code common} classpath, so each loader bridge inherits them instead of duplicating the logic. The
 * loader-specific hooks (keybind, ticks, disconnect, config directory, command registration) stay abstract for the
 * per-loader subclass.
 */
public abstract class AbstractPlatformBridge implements PlatformBridge {
    private static final Logger LOGGER = LogManager.getLogger(AbstractPlatformBridge.class);

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
        Minecraft mc = Minecraft.getMinecraft();
        // getConnection() is null until Minecraft.player is assigned, which is what keeps this false during
        // the world-load window where a replay server is already installed and the client is already ticking.
        if (mc.getConnection() == null) {
            return false;
        }
        return !mc.isSingleplayer() || isReplayPlayback();
    }

    // Kept private so nothing invites an off-main-thread read from the coverage overlay, which would break the
    // lazy init above. isInstance(null) is false, which is the sole guard over the shutdown window where the
    // singleplayer server field is already cleared but isSingleplayer is not.
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
            mc.player.sendStatusMessage(rendered, false);
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

    /**
     * The nearest of the sixteen named colors to an arbitrary RGB value. Pre-1.16 {@code Style} carries only a
     * {@link TextFormatting} color, not the free RGB {@code TextColor} the shared copy supplies, so an exact color is
     * quantized to the closest vanilla color by squared distance. This band's {@code TextFormatting} exposes no RGB
     * accessor either, so each named color's value is recomputed from its color index by the vanilla color-table
     * formula.
     */
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

    // The RGB of a named color from its 0..15 index, the vanilla FontRenderer color-table computation: a base of
    // 0 or 85 from bit 3, each channel then 0 or 170 above that base from its own bit, and the gold slot (index 6)
    // biased 85 brighter on red.
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
        // There is no vanilla toast host at this band, so both halves of the path go to WDL's own tray: the
        // multi-line job-done notification and the single-line refusal that rode a vanilla SystemToast above 1.12.
        // The tray owns the dedup that the two SystemToast categories used to provide, on the same rule.
        int bodyRgb = toast.bodyColor().isPresent() ? 0xFF000000 | toast.bodyColor().getAsInt() : -1;
        WdlToastOverlay.show(new TextComponentTranslation(toast.titleKey()).getUnformattedText(),
                body.getUnformattedText(), bodyRgb, toast.refusal());
    }

    /**
     * Build the wdl pause-menu row (a primary action button plus a settings button) above {@code anchor}, shifting the
     * anchor down to open the row. Returns the widgets for the loader to add through its own screen hook; the layout is
     * loader-agnostic. Returns none in the user's own local world, leaving the anchor unshifted so the vanilla menu
     * keeps its own spacing.
     */
    protected List<GuiButton> buildPauseMenuRow(GuiButton anchor,
            Supplier<String> primaryLabelKey, BooleanSupplier primaryEnabled, Runnable onPrimary,
            Runnable onConfig) {
        // A local world refuses every action this row leads to, and the /wdl commands and downloads keybind
        // still reach the settings and downloads screens there, so the row is hidden rather than disabled.
        // Replay playback is a remote world, so the row is present there and its actions work.
        if (!isRemoteWorld()) {
            return ImmutableList.of();
        }
        int x = anchor.x;
        int y = anchor.y;
        int width = anchor.width;
        anchor.y = y + 24; // shift the bottom button (Disconnect) down to open a row above it
        GuiButton primary = new WdlMenuButton(x, y, width - 24, 20, I18n.format(primaryLabelKey.get()), onPrimary);
        primary.enabled = primaryEnabled.getAsBoolean();
        // This band's button carries no hover-tooltip parameter, so the settings button has no hover label.
        GuiButton config = new WdlMenuButton(x + width - 20, y, 20, 20, "...", onConfig);
        return ImmutableList.of(primary, config);
    }

    /** The lowest existing pause-menu button (Disconnect), the anchor to insert the wdl row above. */
    protected static @Nullable GuiButton lowest(List<GuiButton> widgets) {
        GuiButton lowest = null;
        for (GuiButton widget : widgets) {
            if (lowest == null || widget.y > lowest.y) {
                lowest = widget;
            }
        }
        return lowest;
    }

    /**
     * The pause-menu buttons this band builds: a plain {@link GuiButton} carrying its own press action, since below the
     * 1.13 GUI rewrite {@code GuiButton} has no onPress callback and a click is dispatched by the screen's
     * action-performed path off the button identity instead. The loader's action-performed hook calls {@link #press()}.
     */
    public static final class WdlMenuButton extends GuiButton {
        // A pause-menu button id outside GuiIngameMenu's own 0..12 range, so the vanilla screen's actionPerformed
        // switch never claims a click on this row; the press is dispatched by the loader's action-performed hook.
        private static final int ID = 0x77646C01;
        private final Runnable action;

        public WdlMenuButton(int x, int y, int width, int height, String label, Runnable action) {
            super(ID, x, y, width, height, label);
            this.action = action;
        }

        /** Run the button's action; called by the loader's action-performed hook when this button is clicked. */
        public void press() {
            action.run();
        }
    }

    /**
     * Build the {@code /wdl} client command, source-generic over the loader-agnostic {@link WdlCommands} actions. Below
     * the 1.13 command rewrite there is no Brigadier tree: this is a classic {@link CommandBase} whose {@code execute}
     * and {@code getTabCompletions} branch on the raw argument array, and whose actions run on the client main thread
     * (the client command handler dispatches there). The loader registers it through its own client-command surface.
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

        /** The wdl-managed download names under the saves directory, empty if the folder cannot be read. */
        private static List<String> managedDownloadNames() {
            // This band's save format exposes no saves-root accessor, and the saves folder is the game directory's
            // own saves child at this band, so it is resolved from there.
            Path savesDirectory = Minecraft.getMinecraft().mcDataDir.toPath().resolve("saves");
            try {
                return DownloadFolders.listManaged(savesDirectory);
            } catch (IOException exception) {
                return ImmutableList.of(); // no suggestions if the saves directory cannot be read
            }
        }
    }
}
