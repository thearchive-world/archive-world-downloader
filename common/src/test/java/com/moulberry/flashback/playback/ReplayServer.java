// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package com.moulberry.flashback.playback;

/**
 * Bare stand-in at Flashback's real class name so {@code FlashbackReplayProbe}'s {@code Class.forName} resolution and
 * {@code isInstance} check can be exercised without a dependency on the Flashback mod. It extends the local-server
 * stand-in because the real Flashback {@code ReplayServer} extends {@code IntegratedServer}, and that relation is the
 * point: the probe must match the subclass and refuse the superclass.
 */
public final class ReplayServer extends LocalServer {}
