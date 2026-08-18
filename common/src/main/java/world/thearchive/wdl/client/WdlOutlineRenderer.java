// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.adapter.OutlineDrawSet;
import world.thearchive.wdl.adapter.OutlineRenderContext;
import world.thearchive.wdl.adapter.OutlineRim;
import world.thearchive.wdl.adapter.OutlineTracker;
import world.thearchive.wdl.adapter.RimRenderer;
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
 * section frustum cull plus the delegated draw. Its only per-frame allocation is one section box per visible section
 * for the frustum test. Runs on the render thread, the vanilla client thread.
 */
public final class WdlOutlineRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(WdlOutlineRenderer.class);

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
            Frustum frustum = context.frustum();
            ObjectIterator<Long2ObjectMap.Entry<List<OutlineRim>>> entries = Long2ObjectMaps.fastIterator(sections);
            while (entries.hasNext()) {
                Long2ObjectMap.Entry<List<OutlineRim>> entry = entries.next();
                // A null frustum is a band whose loader render event exposes none (26.x fabric-api): draw every
                // section and let the GPU clip, rather than skip the off-screen build.
                if (frustum != null && !frustum.isVisible(sectionBox(entry.getLongKey()))) {
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
     * Build the per-frame render context from the loader's pose, buffers, and frustum, resolving the effective rim line
     * width from the config scale and the band's appropriate width, then cull and draw the live set. A null frustum (a
     * band whose loader render event no longer exposes one) draws every section, the GPU clipping off-screen.
     */
    public static void render(PoseStack pose, MultiBufferSource consumers, @Nullable Frustum frustum,
            RimRenderer rimRenderer) {
        // getAppropriateLineWidth and per-vertex line width do not exist below 1.21.5, so the line-width scale is a
        // no-op at this band: lines draw at the fixed GL width regardless of the shipped knob.
        float lineWidth = (float) Wdl.config().outline().lineWidthScale();
        OutlineRenderContext context = new OutlineRenderContext(pose, consumers, frustum,
                Minecraft.getInstance().gameRenderer.getMainCamera().getPosition(), lineWidth);
        render(context, Wdl.outlineDrawSet(), rimRenderer);
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

    private static AABB sectionBox(long sectionKey) {
        int x = SectionPos.sectionToBlockCoord(SectionPos.x(sectionKey));
        int y = SectionPos.sectionToBlockCoord(SectionPos.y(sectionKey));
        int z = SectionPos.sectionToBlockCoord(SectionPos.z(sectionKey));
        return new AABB(x, y, z, x + 16.0, y + 16.0, z + 16.0);
    }
}
