// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

import world.thearchive.wdl.adapter.RenderSurface;
import world.thearchive.wdl.adapter.impl.RenderSurfaceImpl;
import world.thearchive.wdl.core.BrandColors;

/**
 * Refusals dedupe and job-done notifications do not: a refusal arriving while another refusal is live is dropped, so a
 * repeated click cannot queue a parade. Every job-done event surfaces its own toast.
 */
public final class WdlToastOverlay {
    private static final int WIDTH = 160;
    private static final int MIN_HEIGHT = 32;
    private static final int LINE_STEP = 12;
    private static final int PADDING_X = 8;
    private static final int TITLE_Y = 7;
    private static final int BODY_Y = 18;
    private static final int MARGIN = 8;
    private static final int SLOT_GAP = 4;
    private static final int VISIBLE_SLOTS = 3;
    private static final long DWELL_MS = 5000L;
    private static final long SLIDE_MS = 300L;
    /** Opaque enough to read over bright terrain. */
    private static final int PANEL_ALPHA = 0xE0;
    private static final int TITLE_COLOR = BrandColors.AMBER;

    private static final Deque<Notification> queued = new ArrayDeque<>();
    private static final List<Notification> visible = new ArrayList<>(VISIBLE_SLOTS);

    private WdlToastOverlay() {}

    private static final class Notification {
        private final String title;
        private final String body;
        private final int bodyColor;
        private final boolean refusal;
        private long deadline;
        private long shownAt;

        Notification(String title, String body, int bodyColor, boolean refusal) {
            this.title = title;
            this.body = body;
            this.bodyColor = bodyColor;
            this.refusal = refusal;
        }
    }

    /**
     * Called on the client thread, so the queue needs no synchronization.
     */
    public static void show(String title, String body, int bodyColor, boolean refusal) {
        if (refusal && anyRefusalLive()) {
            return;
        }
        queued.addLast(new Notification(title, body, bodyColor, refusal));
    }

    private static boolean anyRefusalLive() {
        for (Notification live : visible) {
            if (live.refusal) {
                return true;
            }
        }
        for (Notification pending : queued) {
            if (pending.refusal) {
                return true;
            }
        }
        return false;
    }

    /**
     * Draw the tray.
     */
    public static void render() {
        Minecraft minecraft = Minecraft.getMinecraft();
        FontRenderer font = minecraft.fontRenderer;
        if (font == null) {
            return;
        }
        long now = System.currentTimeMillis();
        promote(now);
        if (visible.isEmpty()) {
            return;
        }
        RenderSurface surface = new RenderSurfaceImpl();
        int right = surface.guiWidth() - MARGIN;
        int y = MARGIN;
        for (Notification live : visible) {
            y += draw(surface, font, live, right, y, now) + SLOT_GAP;
        }
    }

    private static void promote(long now) {
        for (int i = visible.size() - 1; i >= 0; i--) {
            if (now >= visible.get(i).deadline) {
                visible.remove(i);
            }
        }
        while (visible.size() < VISIBLE_SLOTS && !queued.isEmpty()) {
            Notification next = queued.removeFirst();
            next.shownAt = now;
            next.deadline = now + DWELL_MS;
            visible.add(next);
        }
    }

    private static int draw(RenderSurface surface, FontRenderer font, Notification live, int right, int y, long now) {
        List<String> lines = font.listFormattedStringToWidth(live.body, WIDTH - PADDING_X * 2);
        int height = Math.max(MIN_HEIGHT, BODY_Y + lines.size() * LINE_STEP);
        long shown = now - live.shownAt;
        int offset = shown >= SLIDE_MS ? 0 : (int) (WIDTH - WIDTH * shown / SLIDE_MS);
        int x = right - WIDTH + offset;
        surface.fill(x, y, x + WIDTH, y + height, PANEL_ALPHA << 24 | BrandColors.PANEL);
        surface.text(font, live.title, x + PADDING_X, y + TITLE_Y, 0xFF000000 | TITLE_COLOR, true);
        for (int i = 0; i < lines.size(); i++) {
            surface.text(font, lines.get(i), x + PADDING_X, y + BODY_Y + i * LINE_STEP, live.bodyColor, true);
        }
        return height;
    }
}
