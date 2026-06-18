package wdl.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project

// The two-line license header every Java file must carry (CONTRIBUTING). It is the repo's one otherwise
// ungated invariant; a Spotless licenseHeader step silently skips package-info.java, so this line-scan
// gate (a sibling of common's checkCoreImports) enforces it uniformly across every source set.
private val LICENSE_HEADER = listOf(
    "// Copyright (C) Archive World Downloader contributors",
    "// SPDX-License-Identifier: LGPL-3.0-or-later",
)

fun Project.registerCheckLicenseHeader() {
    // Scan the physical src/ tree (every source set lives under it), matching checkCoreImports' own
    // dir-based scan. A future source set rooted outside src/ would need adding here.
    val javaFiles = layout.projectDirectory.dir("src").asFileTree.matching { include("**/*.java") }
    val baseDir = layout.projectDirectory.asFile
    val checkLicenseHeader = tasks.register("checkLicenseHeader") {
        group = "verification"
        description = "Fails if any Java file is missing the two-line license header."
        inputs.files(javaFiles)
        doLast {
            val offenders = javaFiles.files.filter { it.readLines().take(2) != LICENSE_HEADER }
            if (offenders.isNotEmpty()) {
                throw GradleException(
                    "license header missing or wrong on ${offenders.size} file(s):\n" +
                        offenders.joinToString("\n") { "  - ${it.relativeTo(baseDir)}" } +
                        "\nEvery Java file must begin with:\n" +
                        LICENSE_HEADER.joinToString("\n") { "  $it" },
                )
            }
        }
    }
    tasks.named("check") { dependsOn(checkLicenseHeader) }
}
