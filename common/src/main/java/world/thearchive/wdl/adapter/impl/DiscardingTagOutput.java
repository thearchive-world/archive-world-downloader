// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.core.RegistryAccess;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * A server-free {@link TagValueOutput} for capture serialization: the DISCARDING problem reporter stands in for the
 * server's, so a decode problem is dropped rather than surfaced. Every capture sink writes into one.
 */
final class DiscardingTagOutput {
    private DiscardingTagOutput() {}

    static TagValueOutput create(RegistryAccess registries) {
        return TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
    }
}
