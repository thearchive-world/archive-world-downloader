// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
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

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.adapter.OutlineDrawSet;
import world.thearchive.wdl.adapter.OutlineRenderContext;
import world.thearchive.wdl.adapter.OutlineRim;
import world.thearchive.wdl.adapter.RimRenderer;
import world.thearchive.wdl.core.BrandColors;
import world.thearchive.wdl.core.CaptureState;
import world.thearchive.wdl.core.RimFace;
import world.thearchive.wdl.core.TimingWindow;

/**
 * The draw is gated on the live capture being recording, so the to-do rims stop the instant the capture ends even
 * before the next maintenance tick clears the draw-set.
 */
public final class WdlOutlineRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();

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
     * Build the per-frame render context from the loader's pose, buffers, and frustum, then cull and draw the live set.
     */
    public static void render(PoseStack pose, MultiBufferSource consumers, @Nullable Frustum frustum,
            RimRenderer rimRenderer) {
        float lineWidth = Wdl.config().outline().lineWidthScale();
        OutlineRenderContext context = new OutlineRenderContext(pose, consumers, frustum,
                Minecraft.getInstance().gameRenderer.getMainCamera().getPosition(), lineWidth);
        render(context, Wdl.outlineDrawSet(), rimRenderer);
    }

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
