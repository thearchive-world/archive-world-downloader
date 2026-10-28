// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.core.RegistryAccess;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueOutput;

final class DiscardingTagOutput {
    private DiscardingTagOutput() {}

    static TagValueOutput create(RegistryAccess registries) {
        return TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
    }
}
