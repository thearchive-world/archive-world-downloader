// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import java.util.OptionalLong;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.adapter.RenderSurface;
import world.thearchive.wdl.adapter.impl.RenderSurfaceImpl;
import world.thearchive.wdl.core.BrandColors;
import world.thearchive.wdl.core.CaptureCounts;
import world.thearchive.wdl.core.CaptureState;
import world.thearchive.wdl.core.CaptureStatus;
import world.thearchive.wdl.core.HudAnchor;
import world.thearchive.wdl.core.HudConfig;
import world.thearchive.wdl.core.HudPeekMode;
import world.thearchive.wdl.core.SaveStage;
import world.thearchive.wdl.platform.PlatformBridge;

/**
 * The shared, state-driven HUD overlay drawn while a download records and saves: the primary live status surface (the
 * chat line stays as the dependency-free fallback). One render body serves both loaders and both layouts, reading all
 * live state through the {@link Wdl} facade and drawing with the vanilla surface's filled-rectangle and text primitives
 * only (no sprites, no pose or matrix), so it carries across the version bands with only the banded surface-name and
 * text-method edits the band map enumerates.
 *
 * <p>The HUD pass runs even behind an open screen, so the overlay self-gates (no player or level, a screen open, or the
 * HUD hidden) rather than relying on the registration to suppress it. It does not yield to the F3 debug screen: that
 * screen draws its own left and right columns and leaves the overlay's space clear, and a lone always-visible debug
 * entry such as the FPS line must not suppress the overlay either.
 */
public final class WdlHudOverlay {
    private static final int PADDING = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int BAR_HEIGHT = 3;
    private static final int SAVING_BAR_HEIGHT = 12; // the compact finalization bar holds its label, so it is tall
    private static final int GLYPH_GAP = 4;
    private static final int COMPACT_WIDTH = 140;
    private static final int DETAILED_WIDTH = 160;
    // The widest shortened count the compact line renders: the chunk label reserves this width so it holds its
    // place as the live count grows and shortens (see CaptureStatus.compactCount).
    private static final String COMPACT_COUNT_TEMPLATE = "99.9M";
    private static final int SCREEN_MARGIN = 4;
    private static final int FADE_TAIL_MILLIS = 500;
    private static final float TRACK_LIGHTEN = 0.30f;

    // The phase markers: a recording-red dot, a saving-gray dot, and a done-teal check. The dots share a shape
    // and read apart by color and by the bar that only saving draws; the check is shape-distinct for done.
    private static final String GLYPH_RECORDING = "●";
    private static final String GLYPH_SAVING = "●";
    private static final String GLYPH_DONE = "✔";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean errorLogged;

    private enum Phase {
        RECORDING,
        SAVING,
        DONE
    }

    private record Frame(Phase phase, CaptureCounts counts, long elapsedMillis, SaveStage stage, float progress,
            float alpha) {}

    // The peek key, registered per loader and bound here, plus the toggle-mode latch the overlay flips per press.
    private static @Nullable KeyMapping peekKey;
    private static boolean peekToggled;

    private WdlHudOverlay() {}

    /** Bind the per-loader peek keybind so the overlay can reveal the detailed layout on hold or toggle. */
    public static void bindPeekKey(KeyMapping key) {
        peekKey = key;
    }

    /** Draw one frame; called per render from each loader's registered HUD element/layer. */
    public static void render(PoseStack poseStack, float partialTick) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            HudConfig config = Wdl.config().hud();
            updatePeekToggle(config);
            if (!config.showHud() || isHidden(minecraft)) {
                return;
            }
            Frame frame = resolveFrame(config);
            if (frame == null) {
                return; // idle, or past the done linger: the overlay draws nothing
            }
            boolean detailed = config.detailed() || isPeeking(config);
            draw(new RenderSurfaceImpl(poseStack), minecraft.font, config, frame, detailed);
        } catch (RuntimeException e) {
            // A per-frame draw must never crash the HUD pass or spam the log; surface the first failure only.
            if (!errorLogged) {
                errorLogged = true;
                LOGGER.warn("HUD overlay render failed; suppressing further errors", e);
            }
        }
    }

    private static boolean isHidden(Minecraft minecraft) {
        PlatformBridge platform = Wdl.platformBridge();
        return minecraft.player == null
                || minecraft.level == null
                || platform.isBlockingScreenOpen()
                || platform.isHudHidden();
    }

    private static @Nullable Frame resolveFrame(HudConfig config) {
        CaptureState state = Wdl.state();
        CaptureCounts counts = Wdl.counts();
        long elapsed = Wdl.elapsedMillis();
        switch (state) {
            case RECORDING:
                return new Frame(Phase.RECORDING, counts, elapsed, SaveStage.NONE, 0.0f, 1.0f);
            case SAVING:
                return new Frame(Phase.SAVING, counts, elapsed, Wdl.saveStage(), Wdl.saveProgress(), 1.0f);
            case IDLE:
            default:
                return doneFrame(config, counts, elapsed);
        }
    }

    private static @Nullable Frame doneFrame(HudConfig config, CaptureCounts counts, long elapsed) {
        OptionalLong done = Wdl.doneElapsedMillis();
        if (done.isEmpty()) {
            return null;
        }
        long sinceDone = done.getAsLong();
        long lingerMillis = config.doneLingerSeconds() * 1000L;
        if (sinceDone > lingerMillis + FADE_TAIL_MILLIS) {
            return null;
        }
        float alpha = sinceDone <= lingerMillis ? 1.0f : 1.0f - (sinceDone - lingerMillis) / (float) FADE_TAIL_MILLIS;
        return new Frame(Phase.DONE, counts, elapsed, SaveStage.NONE, 0.0f, Mth.clamp(alpha, 0.0f, 1.0f));
    }

    private static void updatePeekToggle(HudConfig config) {
        KeyMapping key = peekKey;
        if (key == null) {
            return;
        }
        if (config.peekMode() == HudPeekMode.TOGGLE) {
            while (key.consumeClick()) {
                peekToggled = !peekToggled;
            }
        } else {
            peekToggled = false; // hold mode reads the live key state, so a stale toggle never lingers
        }
    }

    private static boolean isPeeking(HudConfig config) {
        KeyMapping key = peekKey;
        if (key == null) {
            return false;
        }
        return config.peekMode() == HudPeekMode.HOLD ? key.isDown() : peekToggled;
    }

    private static void draw(RenderSurface surface, Font font, HudConfig config, Frame frame, boolean detailed) {
        int boxWidth = detailed ? DETAILED_WIDTH : COMPACT_WIDTH;
        int boxHeight = 2 * PADDING + contentHeight(frame.phase(), detailed);

        int screenWidth = surface.guiWidth();
        int screenHeight = surface.guiHeight();
        int boxX = Mth.clamp(anchorX(config.anchor(), screenWidth, boxWidth) + config.offsetX(),
                0, Math.max(0, screenWidth - boxWidth));
        int boxY = Mth.clamp(anchorY(config.anchor(), screenHeight, boxHeight) + config.offsetY(),
                0, Math.max(0, screenHeight - boxHeight));

        float alpha = frame.alpha();
        boolean shadow = !config.background();
        if (config.background()) {
            int panelAlpha = Mth.clamp(Math.round(config.panelOpacity() / 100.0f * 255.0f * alpha), 0, 255);
            surface.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight,
                    (panelAlpha << 24) | (BrandColors.PANEL & 0xFFFFFF));
        }

        int contentX = boxX + PADDING;
        int contentY = boxY + PADDING;
        int contentWidth = boxWidth - 2 * PADDING;
        if (detailed) {
            drawDetailed(surface, font, frame, contentX, contentY, contentWidth, alpha, shadow);
        } else {
            drawCompact(surface, font, frame, contentX, contentY, contentWidth, alpha, shadow);
        }
    }

    private static int contentHeight(Phase phase, boolean detailed) {
        if (detailed) {
            int rows = phase == Phase.SAVING ? 5 : 4; // four count rows, plus the saving stage row
            return rows * LINE_HEIGHT + (phase == Phase.SAVING ? BAR_HEIGHT + 2 : 0);
        }
        if (phase == Phase.SAVING) {
            return LINE_HEIGHT + SAVING_BAR_HEIGHT; // the status row, then the tall label-bearing bar
        }
        return LINE_HEIGHT;
    }

    private static void drawCompact(RenderSurface surface, Font font, Frame frame,
            int contentX, int contentY, int contentWidth, float alpha, boolean shadow) {
        String glyph = glyph(frame.phase());
        int textColor = withAlpha(BrandColors.IVORY, alpha);
        surface.text(font, glyph, contentX, contentY, withAlpha(glyphColor(frame.phase()), alpha), shadow);

        int afterGlyph = contentX + font.width(glyph) + GLYPH_GAP;
        String timer = CaptureStatus.elapsed(frame.elapsedMillis());
        surface.text(font, timer, afterGlyph, contentY, textColor, shadow);

        // Anchor the chunk label at a fixed x so a growing count extends to its right rather than shoving the
        // word left; right-aligning the whole label-and-count phrase would drift the word as digits are added.
        // Clamp the anchor past the timer so an over-wide localized label sits behind it rather than overlapping.
        String label = Component.translatable("wdl.hud.label.chunks").getString();
        String count = CaptureStatus.compactCount(frame.counts().chunks());
        int rightEdge = contentX + contentWidth;
        int labelAdvance = font.width(label + " ");
        int afterTimer = afterGlyph + font.width(timer) + GLYPH_GAP;
        int labelX = Math.max(afterTimer, rightEdge - labelAdvance - font.width(COMPACT_COUNT_TEMPLATE));
        surface.text(font, label, labelX, contentY, textColor, shadow);
        int countX = labelX + labelAdvance;
        String shownCount = ClientText.ellipsize(font, count, Math.max(0, rightEdge - countX));
        surface.text(font, shownCount, countX, contentY, textColor, shadow);

        if (frame.phase() == Phase.SAVING) {
            drawLabeledBar(surface, font, contentX, contentY + LINE_HEIGHT, contentWidth,
                    frame.stage(), frame.progress(), alpha);
        }
    }

    /**
     * The compact finalization bar: a full-width track filled to the fraction, with the phase label drawn over the left
     * and the percent over the right. The over-bar text is always shadowed for contrast.
     */
    private static void drawLabeledBar(RenderSurface surface, Font font, int x, int y, int width,
            SaveStage stage, float fraction, float alpha) {
        fillBar(surface, x, y, width, SAVING_BAR_HEIGHT, fraction, alpha);
        int textColor = withAlpha(BrandColors.IVORY, alpha);
        int textY = y + (SAVING_BAR_HEIGHT - font.lineHeight) / 2 + 1;
        surface.text(font, phaseLabel(stage), x + 2, textY, textColor, true);
        int percent = Math.round(Mth.clamp(fraction, 0.0f, 1.0f) * 100.0f);
        Component percentText = Component.translatable("wdl.hud.percent", percent);
        surface.text(font, percentText, x + width - font.width(percentText) - 1, textY, textColor, true);
    }

    private static void drawDetailed(RenderSurface surface, Font font, Frame frame,
            int contentX, int contentY, int contentWidth, float alpha, boolean shadow) {
        String glyph = glyph(frame.phase());
        int glyphAdvance = font.width(glyph) + GLYPH_GAP;
        CaptureCounts counts = frame.counts();
        int rowX = contentX + glyphAdvance;
        int rowWidth = contentWidth - glyphAdvance;
        int textColor = withAlpha(BrandColors.IVORY, alpha);
        int rowY = contentY;
        // The phase glyph sits to the left of the first row, not on a line of its own.
        surface.text(font, glyph, contentX, rowY, withAlpha(glyphColor(frame.phase()), alpha), shadow);
        drawRow(surface, font, "wdl.hud.label.chunks", Integer.toString(counts.chunks()),
                rowX, rowY, rowWidth, textColor, shadow);
        rowY += LINE_HEIGHT;
        drawRow(surface, font, "wdl.hud.label.entities", Integer.toString(counts.entities()),
                rowX, rowY, rowWidth, textColor, shadow);
        rowY += LINE_HEIGHT;
        drawRow(surface, font, "wdl.hud.label.containers", Integer.toString(counts.containers()),
                rowX, rowY, rowWidth, textColor, shadow);
        rowY += LINE_HEIGHT;
        drawRow(surface, font, "wdl.hud.label.time", CaptureStatus.elapsed(frame.elapsedMillis()),
                rowX, rowY, rowWidth, textColor, shadow);

        if (frame.phase() == Phase.SAVING) {
            rowY += LINE_HEIGHT;
            int percent = Math.round(Mth.clamp(frame.progress(), 0.0f, 1.0f) * 100.0f);
            surface.text(font, Component.translatable("wdl.hud.label.stage"), rowX, rowY, textColor, shadow);
            Component stageValue = Component.translatable("wdl.hud.stage_percent", phaseLabel(frame.stage()), percent);
            surface.text(font, stageValue, rowX + rowWidth - font.width(stageValue), rowY,
                    withAlpha(BrandColors.SAVING_GRAY, alpha), shadow);
            fillBar(surface, rowX, rowY + LINE_HEIGHT - 1, rowWidth, BAR_HEIGHT, frame.progress(), alpha);
        }
    }

    private static void drawRow(RenderSurface surface, Font font, String labelKey, String value, int x, int y,
            int width, int color, boolean shadow) {
        surface.text(font, Component.translatable(labelKey), x, y, color, shadow);
        surface.text(font, value, x + width - font.width(value), y, color, shadow);
    }

    private static void fillBar(RenderSurface surface, int x, int y, int width, int height, float fraction,
            float alpha) {
        surface.fill(x, y, x + width, y + height, withAlpha(lighten(BrandColors.PANEL, TRACK_LIGHTEN), alpha));
        int filled = Math.round(width * Mth.clamp(fraction, 0.0f, 1.0f));
        if (filled > 0) {
            surface.fill(x, y, x + filled, y + height, withAlpha(BrandColors.AMBER, alpha));
        }
    }

    private static MutableComponent phaseLabel(SaveStage stage) {
        return switch (stage) {
            case WRITING_CHUNKS -> Component.translatable("wdl.hud.phase.chunks");
            case WRITING_MAPS -> Component.translatable("wdl.hud.phase.maps");
            case COMPRESSING -> Component.translatable("wdl.hud.phase.compressing");
            case NONE -> Component.empty();
        };
    }

    private static String glyph(Phase phase) {
        return switch (phase) {
            case RECORDING -> GLYPH_RECORDING;
            case SAVING -> GLYPH_SAVING;
            case DONE -> GLYPH_DONE;
        };
    }

    private static int glyphColor(Phase phase) {
        return switch (phase) {
            case RECORDING -> BrandColors.RECORDING_RED;
            case SAVING -> BrandColors.SAVING_GRAY;
            case DONE -> BrandColors.TEAL;
        };
    }

    private static int anchorX(HudAnchor anchor, int screenWidth, int boxWidth) {
        return switch (anchor) {
            case TOP_LEFT, MIDDLE_LEFT, BOTTOM_LEFT -> SCREEN_MARGIN;
            case TOP_CENTER, MIDDLE_CENTER -> (screenWidth - boxWidth) / 2;
            case TOP_RIGHT, MIDDLE_RIGHT, BOTTOM_RIGHT -> screenWidth - boxWidth - SCREEN_MARGIN;
        };
    }

    private static int anchorY(HudAnchor anchor, int screenHeight, int boxHeight) {
        return switch (anchor) {
            case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> SCREEN_MARGIN;
            case MIDDLE_LEFT, MIDDLE_CENTER, MIDDLE_RIGHT -> (screenHeight - boxHeight) / 2;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> screenHeight - boxHeight - SCREEN_MARGIN;
        };
    }

    private static int withAlpha(int rgb, float alpha) {
        int alphaByte = Mth.clamp(Math.round(alpha * 255.0f), 0, 255);
        return (alphaByte << 24) | (rgb & 0xFFFFFF);
    }

    private static int lighten(int rgb, float amount) {
        int red = lightenChannel((rgb >> 16) & 0xFF, amount);
        int green = lightenChannel((rgb >> 8) & 0xFF, amount);
        int blue = lightenChannel(rgb & 0xFF, amount);
        return (red << 16) | (green << 8) | blue;
    }

    private static int lightenChannel(int channel, float amount) {
        return Mth.clamp(Math.round(channel + (255 - channel) * amount), 0, 255);
    }
}
