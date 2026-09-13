import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

// NullAway-based nullness checking, shared by every wdl subproject's production compile.
plugins {
    java
    id("net.ltgt.errorprone")
}

// These coordinates live in the version catalog so Dependabot can track their currency; the type-safe
// libs accessor is not generated inside precompiled script plugins (gradle/gradle#15383), so read them
// back through VersionCatalogsExtension.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// The Error Prone javac plugin runs inside the compile toolchain, and the pinned release (2.50) is a
// Java 21 build that a lower toolchain cannot load. A band below that floor therefore runs
// annotations-only. The nullness verdict is not wholly lost: core is byte-identical to the bands that
// do run NullAway, so it carries their verdict; everything else in this band's tree goes unchecked.
val bandJavaVersion = providers.gradleProperty("java_version").get().toInt()
val errorProneUsable = bandJavaVersion >= 21

val jspecify = libs.findLibrary("jspecify").get()

dependencies {
    // JSpecify nullness annotations (@NullMarked, @Nullable). CLASS-retention and compile-only,
    // so nothing enters the runtime jar; supplied to both main and test compilation.
    compileOnly(jspecify)
    testCompileOnly(jspecify)

    // Error Prone hosts NullAway; every other Error Prone check is disabled on the compile tasks
    // below, so this contributes the nullness checker and nothing that contends with Checkstyle.
    if (errorProneUsable) {
        "errorprone"(libs.findLibrary("errorprone-core").get())
        "errorprone"(libs.findLibrary("nullaway").get())
    }
}

// --- The Java 8 readers' view of JSpecify ---
// The Target on NullMarked names ElementType.MODULE, a constant Java 8 lacks, so javac 8 and javadoc 8 each warn
// "unknown enum constant" whenever they read the class. The fix is on the class-file side: below Java 11 every
// source set compiles and documents against a rewritten jar that drops the MODULE element from that Target and
// copies every other entry as is. The jar is compile-only and never ships, and our class files name the annotation
// by descriptor alone, so nothing shipped changes.
abstract class StripNullMarkedModuleTarget : DefaultTask() {
    @get:InputFiles
    abstract val jspecifyJar: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun strip() {
        val outDir = outputDir.get().asFile
        outDir.mkdirs()
        outDir.listFiles()?.forEach { it.delete() }
        val jar = jspecifyJar.singleFile
        val target = outDir.resolve(jar.nameWithoutExtension + "-stripped.jar")
        ZipInputStream(jar.inputStream().buffered()).use { zin ->
            ZipOutputStream(target.outputStream().buffered()).use { zout ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val bytes = zin.readBytes()
                    val nullMarked = entry.name == "org/jspecify/annotations/NullMarked.class"
                    val transformed = if (nullMarked) stripModuleTarget(bytes) else bytes
                    zout.putNextEntry(ZipEntry(entry.name))
                    zout.write(transformed)
                    zout.closeEntry()
                    entry = zin.nextEntry
                }
            }
        }
    }

    // The writer is given no reader to copy the constant pool from, so the MODULE constant leaves with its one use.
    private fun stripModuleTarget(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                val annotation = super.visitAnnotation(descriptor, visible)
                if (annotation == null || descriptor != "Ljava/lang/annotation/Target;") return annotation
                return object : AnnotationVisitor(Opcodes.ASM9, annotation) {
                    override fun visitArray(name: String?): AnnotationVisitor? {
                        val array = super.visitArray(name) ?: return null
                        return object : AnnotationVisitor(Opcodes.ASM9, array) {
                            override fun visitEnum(name: String?, descriptor: String?, value: String?) {
                                if (value != "MODULE") super.visitEnum(name, descriptor, value)
                            }
                        }
                    }
                }
            }
        }, 0)
        return writer.toByteArray()
    }
}

if (bandJavaVersion < 11) {
    val strippedJspecifyDir = layout.buildDirectory.dir("stripped-jspecify")
    val stripNullMarkedModuleTarget = tasks.register<StripNullMarkedModuleTarget>("stripNullMarkedModuleTarget") {
        jspecifyJar.from(configurations.detachedConfiguration().apply { dependencies.addLater(jspecify) })
        outputDir.set(strippedJspecifyDir)
    }
    val strippedJspecify = fileTree(strippedJspecifyDir) { include("*.jar") }.builtBy(stripNullMarkedModuleTarget)
    sourceSets.configureEach {
        compileClasspath = compileClasspath.filter { !it.name.startsWith("jspecify-") } + strippedJspecify
    }
}

if (errorProneUsable) {
    // NullAway runs as an Error Prone plugin, scoped to nullness only: disableAllChecks turns off every
    // other Error Prone check (Checkstyle and Spotless own style and layout, and Error Prone must not
    // contend with them), then NullAway is re-enabled at ERROR. AnnotatedPackages marks our own code as
    // the checked, @NullMarked surface; everything outside it (MC, the JDK) stays unannotated and
    // is assumed non-null, so NullAway enforces our internal consistency without modeling MC.
    // Where a loader subproject source-merges :common, this compile also re-checks that merged-in
    // source; AnnotatedPackages keeps the verdict identical to :common's own run.
    tasks.named<JavaCompile>("compileJava") {
        options.errorprone {
            disableAllChecks.set(true)
            check("NullAway", CheckSeverity.ERROR)
            option("NullAway:AnnotatedPackages", "world.thearchive")
        }
    }

    // Production code only. Test code stays @NullMarked for IDE and documentation value but stays
    // unchecked: JUnit lifecycle initialization (fields set in @BeforeEach or @BeforeAll) trips
    // NullAway's field-initialization analysis with no production payoff.
    tasks.named<JavaCompile>("compileTestJava").configure {
        options.errorprone.enabled.set(false)
    }
} else {
    // Below the Error Prone floor the plugin is still applied (the plugins block is static) but must not
    // attach to any compile task, since no errorprone dependency was added to load it.
    tasks.withType<JavaCompile>().configureEach {
        options.errorprone.enabled.set(false)
    }
}
