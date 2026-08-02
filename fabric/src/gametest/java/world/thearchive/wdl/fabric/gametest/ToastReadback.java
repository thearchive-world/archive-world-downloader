// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * Reads the client's live toasts back for the restore-flow gametests: the mod surfaces every restore refusal (occupant,
 * folder-missing, tainted, and the per-cause restore-blocked family) as a {@code SystemToast} whose body and title are
 * the only durable evidence of which refusal fired. No production seam exposes the dispatched copy, so this reflects
 * the queued and visible toast lists (their fields are private on the vanilla types) and renders each
 * {@code SystemToast}'s title and message lines back to plain text. Reflection is test-only and adds no shipped-jar
 * footprint; the gametest source set carries no portability constraint.
 *
 * <p>The refusal path dedupes on a shared toast id (one refusal on screen suppresses a second), so a test that asserts
 * several refusals in turn calls {@link #clear} between them: it empties both toast lists so the next refusal is
 * neither suppressed nor confused with the prior one.
 */
final class ToastReadback {
    private ToastReadback() {}

    /**
     * The plain text (title then message lines) of the newest {@code SystemToast} the client holds, or null when none
     * is present. Read on the client thread; the toast lists are not thread-safe.
     */
    static @Nullable String latestText(Minecraft minecraft) {
        SystemToast toast = newestSystemToast(minecraft.getToastManager());
        if (toast == null) {
            return null;
        }
        StringBuilder text = new StringBuilder(titleOf(toast));
        for (String line : bodyLinesOf(toast)) {
            text.append(' ').append(line);
        }
        return text.toString();
    }

    /**
     * Empty the queued and visible toast lists, so a following refusal is neither deduped nor mistaken for a prior one.
     */
    static void clear(Minecraft minecraft) {
        ToastManager manager = minecraft.getToastManager();
        queued(manager).clear();
        visibleInstances(manager).clear();
    }

    private static @Nullable SystemToast newestSystemToast(ToastManager manager) {
        SystemToast newest = null;
        for (Object visible : visibleInstances(manager)) {
            Toast toast = toastOfInstance(visible);
            if (toast instanceof SystemToast systemToast) {
                newest = systemToast;
            }
        }
        // The queue holds the freshly added, not-yet-promoted toasts; the last one added is the newest.
        for (Object queued : queued(manager)) {
            if (queued instanceof SystemToast systemToast) {
                newest = systemToast;
            }
        }
        return newest;
    }

    private static String titleOf(SystemToast toast) {
        Object title = readField(toast, SystemToast.class, "title");
        return title instanceof Component component ? component.getString() : "";
    }

    private static List<String> bodyLinesOf(SystemToast toast) {
        List<String> rendered = new ArrayList<>();
        Object lines = readField(toast, SystemToast.class, "messageLines");
        if (!(lines instanceof Collection<?> collection)) {
            return rendered;
        }
        for (Object line : collection) {
            if (line instanceof FormattedCharSequence sequence) {
                StringBuilder builder = new StringBuilder();
                sequence.accept((index, style, codePoint) -> {
                    builder.appendCodePoint(codePoint);
                    return true;
                });
                rendered.add(builder.toString());
            }
        }
        return rendered;
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> queued(ToastManager manager) {
        Object value = readField(manager, ToastManager.class, "queued");
        return value instanceof Collection ? (Collection<Object>) value : new ArrayDeque<>();
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> visibleInstances(ToastManager manager) {
        Object value = readField(manager, ToastManager.class, "visibleToasts");
        return value instanceof Collection ? (Collection<Object>) value : new ArrayList<>();
    }

    private static Toast toastOfInstance(Object instance) {
        try {
            return (Toast) instance.getClass().getMethod("getToast").invoke(instance);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("toast instance did not expose getToast()", e);
        }
    }

    private static @Nullable Object readField(Object target, Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read " + owner.getSimpleName() + "." + name, e);
        }
    }
}
