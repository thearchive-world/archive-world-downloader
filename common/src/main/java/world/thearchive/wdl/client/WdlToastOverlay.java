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
 * The band-local notification tray, standing in for the vanilla toast host this band does not have.
 * {@code net/minecraft/client/gui/toasts} yields zero classes below 1.12, taking {@code GuiToast}, {@code SystemToast}
 * and its category enum with it, so both halves of the shared notification path are rebuilt here: the multi-line
 * job-done toast and the single-line refusal that rode a vanilla {@code SystemToast} on the bands above.
 * {@code GuiAchievement}, which does exist here, is not a substitute, since both of its entry points take a vanilla
 * achievement.
 *
 * <p>The tray reads as a vanilla toast drawn in WDL's own colors. It anchors top-right, where vanilla puts toasts on
 * every band the mod supports, and slides in from the right edge. That anchor is deliberately independent of
 * {@code hudAnchor}: the two surfaces must not be able to collide, and a user moving the HUD must not be able to move
 * notifications on top of it. The panel is always painted, in {@link BrandColors#PANEL} at a fixed opacity, and
 * explicitly does not read {@code hudBackground}. That setting defaults to false, so binding the tray to it would leave
 * notifications as bare text over bright terrain for most users, and would let switching the HUD off silently take the
 * notification surface with it. {@code hudPanelOpacity} is a HUD setting for the same reason and is not consulted
 * either. Text is drawn with a shadow, matching what the HUD does when its own background is on.
 *
 * <p>Three toasts are visible at once and the rest queue oldest-first. Each visible slot holds an absolute deadline
 * stamped when it was filled, never a per-render accumulator: this tray is drawn from the HUD pass with no screen open
 * and from a WDL screen's own render when one is, and a clock that accumulated per draw would run at whatever rate the
 * frame loop happens to call it.
 *
 * <p>Refusals dedupe and job-done notifications do not, which is what the vanilla-hosted path did: a refusal arriving
 * while another refusal is live is dropped, so a repeated click cannot queue a parade, and the live one keeps its own
 * remaining dwell rather than being reset. Every job-done event surfaces its own toast.
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
    /** Opaque enough to read over bright terrain, matching the fill the HUD paints behind its own progress track. */
    private static final int PANEL_ALPHA = 0xE0;
    private static final int TITLE_COLOR = BrandColors.AMBER;

    private static final Deque<Notification> queued = new ArrayDeque<>();
    private static final List<Notification> visible = new ArrayList<>(VISIBLE_SLOTS);

    private WdlToastOverlay() {}

    /** One notification: already-flattened text, since nothing here re-renders a translation per frame. */
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
     * Queue a notification. Called from the platform bridge on the client thread, the same thread every render runs on,
     * so the queue needs no synchronization.
     */
    public static void show(String title, String body, int bodyColor, boolean refusal) {
        if (refusal && anyRefusalLive()) {
            return; // drop the new one; the live refusal keeps its own remaining dwell
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
     * Draw the tray. Exactly one caller may reach this per frame: the loader's HUD hook draws it while no blocking
     * screen is open, and a WDL screen draws it from its own render when one is, because the HUD pass runs before the
     * screen and would otherwise be painted over by it.
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

    /** Draw one slot and return its height, so the caller can stack the next one below it. */
    private static int draw(RenderSurface surface, FontRenderer font, Notification live, int right, int y, long now) {
        List<String> lines = font.listFormattedStringToWidth(live.body, WIDTH - PADDING_X * 2);
        int height = Math.max(MIN_HEIGHT, BODY_Y + lines.size() * LINE_STEP);
        // Slide in from the right edge: fully off-screen at the moment the slot fills, fully in after SLIDE_MS.
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
