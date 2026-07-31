// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package com.moulberry.flashback.playback;

/**
 * Bare stand-in at Flashback's real class name so FlashbackReplayProbe's Class.forName resolution and isInstance check
 * can be exercised without a dependency on the Flashback mod. It extends the local-server stand-in because the real
 * Flashback ReplayServer extends IntegratedServer, and that relation is the point: the probe must match the subclass
 * and refuse the superclass.
 */
public final class ReplayServer extends LocalServer {}
