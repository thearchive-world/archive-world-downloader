// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.adapter.OutlineDrawSet;
import world.thearchive.wdl.adapter.OutlineRenderContext;
import world.thearchive.wdl.adapter.OutlineRim;
import world.thearchive.wdl.adapter.OutlineTracker;
import world.thearchive.wdl.adapter.RimRenderer;
import world.thearchive.wdl.adapter.SectionKey;
import world.thearchive.wdl.core.BrandColors;
import world.thearchive.wdl.core.CaptureState;
import world.thearchive.wdl.core.RimFace;
import world.thearchive.wdl.core.TimingWindow;

/**
 * The band-agnostic outline cull: each frame it iterates the populated sections of the draw-set, frustum-rejects whole
 * sections, and delegates the draw of each surviving rim, on the exposed face the rim carries, to the injected per-band
 * {@link RimRenderer}. Per-frame cost scales with on-screen sections, not the total tracked set. The draw is gated on
 * the live capture being recording, so the to-do rims stop the instant the capture ends even before the next
 * maintenance tick clears the draw-set.
 *
 * <p>The renderer reads no MC world state: each rim's exposed face is computed and stamped on the client tick by
 * {@link OutlineTracker}, since the neighbors it seals against change only on the tick, so the per-frame path is a pure
 * section frustum cull plus the delegated draw. On this pre-blaze3d band the cull owns the line pass itself: it sets
 * the GL line state, begins the shared Tessellator buffer on the POSITION_COLOR line format, lets each rim write its
 * edges camera-relative into that buffer, then flushes and restores state in a finally so a throwing rim never leaves
 * the buffer building or the state dirty. Runs on the render thread, the vanilla client thread.
 */
public final class WdlOutlineRenderer {
    private static final Logger LOGGER = LogManager.getLogger(WdlOutlineRenderer.class);

    // The outlineDebugTiming window, in frames: the rolling render-thread cost is read from here.
    private static final int TIMING_WINDOW_FRAMES = 200;
    private static final TimingWindow renderTiming = new TimingWindow(TIMING_WINDOW_FRAMES);
    private static boolean errorLogged;

    private WdlOutlineRenderer() {}

    private static void render(OutlineRenderContext context, OutlineDrawSet drawSet, RimRenderer rimRenderer) {
        try {
            if (Wdl.state() != CaptureState.RECORDING) {
                return;
            }
            Long2ObjectMap<List<OutlineRim>> sections = drawSet.sections();
            if (sections.isEmpty()) {
                return;
            }
            boolean debugTiming = Wdl.config().outline().debugTiming();
            long startNanos = debugTiming ? System.nanoTime() : 0L;
            int rimsDrawn = 0;
            int sectionsVisible = 0;
            ICamera frustum = context.frustum();
            setupLineState(context.lineWidth());
            context.lines().begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            try {
                ObjectIterator<Long2ObjectMap.Entry<List<OutlineRim>>> entries = sections.long2ObjectEntrySet()
                        .iterator();
                while (entries.hasNext()) {
                    Long2ObjectMap.Entry<List<OutlineRim>> entry = entries.next();
                    if (frustum != null && !frustum.isBoundingBoxInFrustum(sectionBox(entry.getLongKey()))) {
                        continue;
                    }
                    sectionsVisible++;
                    for (OutlineRim rim : entry.getValue()) {
                        RimFace face = rim.face();
                        if (face != RimFace.NONE) {
                            rimRenderer.drawRim(context, rim.cellBox(), rim.box(), face,
                                    BrandColors.opaque(rim.hue().rgb()));
                            rimsDrawn++;
                        }
                    }
                }
            } finally {
                Tessellator.getInstance().draw();
                restoreLineState();
            }
            if (debugTiming) {
                recordRenderTiming(System.nanoTime() - startNanos, rimsDrawn, sectionsVisible, sections.size());
            }
        } catch (RuntimeException e) {
            // A per-frame draw must never crash the render pass or abort the download over a cosmetic overlay.
            if (!errorLogged) {
                errorLogged = true;
                LOGGER.warn("outline render failed; suppressing further errors", e);
            }
        }
    }

    /**
     * Build the per-frame render context from the loader's frustum and the shared line buffer, resolving the effective
     * rim line width from the config scale, then cull and draw the live set. A null frustum (a loader whose render
     * event exposes none) draws every section, the GPU clipping off-screen.
     */
    public static void render(@Nullable ICamera frustum, RimRenderer rimRenderer) {
        // The vanilla block-selection line width (LevelRenderer.renderHitOutline), which the config scale multiplies
        // per its documented contract; the legacy GL line width is settable on this pre-blaze3d band.
        float base = Math.max(2.5F, Minecraft.getMinecraft().displayWidth / 1920.0F * 2.5F);
        float lineWidth = (float) (Wdl.config().outline().lineWidthScale() * base);
        OutlineRenderContext context = new OutlineRenderContext(Tessellator.getInstance().getBuffer(), frustum,
                cameraPos(), lineWidth);
        render(context, Wdl.outlineDrawSet(), rimRenderer);
    }

    // The world modelview this overlay draws into puts the camera entity's interpolated feet position at its
    // origin (the eye height is folded into the view translate), the same origin vanilla's own block-outline and
    // block-break overlays subtract, so the rim draw subtracts exactly this. There is no Camera type before 1.14.
    private static Vec3d cameraPos() {
        Minecraft minecraft = Minecraft.getMinecraft();
        Entity camera = minecraft.getRenderViewEntity();
        float partialTick = minecraft.getRenderPartialTicks();
        return new Vec3d(camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTick,
                camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTick,
                camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTick);
    }

    // Mirrors LevelRenderer.renderHitOutline, minus its projection-scale trick: the rim's surface standoff is the
    // z-fight guard instead. The depth test is left on so a rim is occluded by nearer terrain.
    private static void setupLineState(float lineWidth) {
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        GlStateManager.glLineWidth(lineWidth);
        GlStateManager.disableTexture2D();
        GlStateManager.depthMask(false);
    }

    private static void restoreLineState() {
        GlStateManager.depthMask(true);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }

    /** Roll the per-frame render-thread cost into a windowed log line. */
    private static void recordRenderTiming(long elapsedNanos, int rimsDrawn, int sectionsVisible,
            int sectionsPopulated) {
        if (renderTiming.record(elapsedNanos)) {
            LOGGER.info("outline render: {} frames, avg {} us, max {} us; last frame {} rims in {}/{} sections",
                    renderTiming.count(), renderTiming.averageMicros(), renderTiming.maxMicros(), rimsDrawn,
                    sectionsVisible, sectionsPopulated);
            renderTiming.reset();
        }
    }

    private static AxisAlignedBB sectionBox(long sectionKey) {
        int x = SectionKey.sectionToBlockCoord(SectionKey.x(sectionKey));
        int y = SectionKey.sectionToBlockCoord(SectionKey.y(sectionKey));
        int z = SectionKey.sectionToBlockCoord(SectionKey.z(sectionKey));
        return new AxisAlignedBB(x, y, z, x + 16.0, y + 16.0, z + 16.0);
    }
}
