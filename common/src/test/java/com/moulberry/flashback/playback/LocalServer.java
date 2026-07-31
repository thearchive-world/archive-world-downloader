// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package com.moulberry.flashback.playback;

/**
 * Stand-in for vanilla's IntegratedServer: what MC holds in genuine singleplayer and in a LAN-hosted world. Present so
 * the probe test can assert that a plain local server does not match, which an unrelated Object cannot express because
 * every class is an Object.
 */
public class LocalServer {}
