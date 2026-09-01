// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.reobftest;

// Positive reobf fixture. Under Unimined a wrong compiled Minecraft reference cannot compile (the classic
// checkReobf reference scan is largely redundant here), so the residual mis-bind surface is a reflective,
// SRG-shaped string literal, the kind a runtime binder feeding Class.getDeclaredField/getDeclaredMethod uses
// to reach a Forge-obfuscated member by name. This literal names net/minecraft/entity/Entity's "world" field
// (MCP name) under its searge id; checkShipJar resolves it against the pinned joined.srg oracle, where it must
// succeed.
public final class ReobfFixture {

    private ReobfFixture() {}

    public static final String WORLD_FIELD_SEARGE_ID = "field_70170_p";
}
