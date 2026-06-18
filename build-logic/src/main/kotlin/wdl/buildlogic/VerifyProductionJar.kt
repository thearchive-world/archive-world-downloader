package wdl.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named

// Guard the production jar. A producing jar task (Loom's remapJar, or the plain jar) can be marked
// up-to-date while its output is actually missing under the configuration-cache + build-cache instability,
// so an incremental build silently leaves build/libs empty while the dev jar still lands in build/devlibs.
// The registered task has no outputs, so it always runs and never goes falsely up-to-date itself; it turns
// that silent miss into a failed build. Regenerate with --rerun-tasks or a clean build.
fun Project.registerVerifyProductionJar(producingTask: String) {
    val archivesName = extensions.getByType<BasePluginExtension>().archivesName
    val productionJar = layout.buildDirectory.file("libs/${archivesName.get()}-$version.jar")
    val verifyProductionJar = tasks.register("verifyProductionJar") {
        group = "verification"
        description = "Fails the build if the production jar is missing from build/libs."
        dependsOn(producingTask)
        doLast {
            val file = productionJar.get().asFile
            if (!file.exists()) {
                throw GradleException("Production jar missing: $file. The $producingTask task was skipped as "
                    + "stale; re-run with --rerun-tasks or ./gradlew clean build.")
            }
        }
    }
    tasks.named("build") { dependsOn(verifyProductionJar) }

    // The authoritative production-jar path, derived from the SAME archiveFile provider publishMods uploads
    // (remapJar / jar) so attest subject, reproducibility-record hash, and upload can never disagree. Printed
    // as an absolute path for the release/guard workflows; invoke with -q so only the path reaches stdout.
    val producedJar = tasks.named<AbstractArchiveTask>(producingTask).flatMap { it.archiveFile }
    tasks.register("printProductionJar") {
        inputs.files(producedJar)
        doLast { println(producedJar.get().asFile.absolutePath) }
    }
}
