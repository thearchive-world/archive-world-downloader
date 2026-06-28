// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.platform;

import com.mojang.logging.LogUtils;
import java.net.URI;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;

import world.thearchive.wdl.core.ChatCopy;

/**
 * The loader-agnostic half of {@link PlatformBridge}: everything a loader implementation would otherwise duplicate
 * because it is answered by the vanilla client rather than by the loader. Each loader subclass supplies only what its
 * own API knows.
 */
public abstract class AbstractPlatformBridge implements PlatformBridge {
    private static final Logger LOGGER = LogUtils.getLogger();

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
        // the world-load window where the client is already ticking.
        if (mc.getConnection() == null) {
            return false;
        }
        return !mc.isLocalServer();
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
}
