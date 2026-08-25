// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.reobftest;

// Negative reobf fixture. Its literal is SRG-shaped but names no real 1.12.2 member; checkReobfNegative scans
// this fixture's own class output for it and must fail the build when the pinned oracle rejects it. A gate
// never seen to fire is not trusted.
public final class ReobfNegativeFixture {

    private ReobfNegativeFixture() {}

    public static final String BOGUS_FIELD_SEARGE_ID = "field_00000000_zz";
}
