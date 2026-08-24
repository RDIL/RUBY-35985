import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    java
    id("org.jetbrains.intellij.platform")
}

// Java, not Kotlin: this is a deliberate deviation from the IntelliJ Platform Plugin Template. The
// sources are all Java and one of them (ProbeState) has to stay strictly JDK-only, so there is
// nothing for the Kotlin plugin to do except add a stdlib we must not bundle.

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

java {
    // 25, not 21: RubyMine 2026.2's platform jars are class file version 69 (Java 25), and a JDK 21
    // javac cannot read them at all -- "class file has wrong version 69.0, should be 65.0".
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// verifyPluginProjectConfiguration emits two warnings here that cannot both be satisfied, because its
// own tables disagree: it maps platform 2026.2.1 to Java 25, but since-build=262 to Java 21. The
// platform is the substantive constraint -- its jars are class file 69 and it runs on JBR 25 -- so
// targeting 25 is correct and the since-build mapping is simply stale for build 262. Targeting 21
// works too (untilBuild pins this to 262.*, which always runs JBR 25); it just trades one warning for
// the other.
//
// The `until-build` warning is also deliberate. Forward compatibility is exactly what this plugin must
// NOT claim: it instruments private RubyMine internals by name, and a platform upgrade can move them.

/**
 * ProbeState and ProbePatch are appended to the BOOTSTRAP classloader at runtime, so that the
 * instrumented Ruby code (in the Ruby plugin's module classloader) and the tool window (in this
 * plugin's classloader) share one copy of the statics. Two copies would silently split the state.
 *
 * They therefore must not be compiled against the platform, and must not be packaged on the plugin
 * classpath -- only embedded as a resource. A separate source set is how that invariant gets
 * expressed to the build rather than merely documented.
 */
val bootstrap: SourceSet = sourceSets.create("bootstrap")

sourceSets {
    // compileClasspath only, never runtimeClasspath: main and test may *reference* the bootstrap
    // classes, but must resolve them at runtime through the bootstrap loader, exactly as in the IDE.
    main {
        compileClasspath += (bootstrap as SourceSet).output
    }
    test {
        compileClasspath += (bootstrap as SourceSet).output
    }
}

val bootstrapJar = tasks.register<Jar>("bootstrapJar") {
    description = "JDK-only jar appended to the bootstrap classloader at runtime."
    group = "build"
    archiveFileName = "ruby-probe-boot.jar"
    destinationDirectory = layout.buildDirectory.dir("bootstrap")
    from(bootstrap.output)
}

// Embedding it as a resource is what makes it locatable inside the IDE: IntelliJ's PathClassLoader
// does not populate a usable CodeSource location, so deriving the path from disk is unreliable.
// Landing it in the main resources output also puts it on the test runtime classpath, so the tests
// exercise the same lookup the plugin uses.
tasks.processResources {
    from(bootstrapJar) {
        into("boot")
    }
}

val platformLocalPath: String = providers.gradleProperty("platformLocalPath").orNull
    ?.takeIf { it.isNotBlank() }
    ?: listOf(
        "${System.getProperty("user.home")}/Applications/RubyMine.app",
        "/Applications/RubyMine.app",
        "${System.getProperty("user.home")}/Applications/JetBrains Toolbox/RubyMine.app",
    ).firstOrNull { file(it).isDirectory }
    ?: error(
        "No local RubyMine found. Install RubyMine, or set -PplatformLocalPath=/path/to/RubyMine.app"
    )

dependencies {
    implementation("net.bytebuddy:byte-buddy:1.17.5")
    implementation("net.bytebuddy:byte-buddy-agent:1.17.5")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    intellijPlatform {
        local(platformLocalPath)
        bundledPlugin("org.jetbrains.plugins.ruby")
    }
}

intellijPlatform {
    // Nothing here relies on @NotNull instrumentation, and skipping it drops a tooling dependency.
    instrumentCode = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
        changeNotes = "bugs were probably fixed"
    }
}

tasks.test {
    // The IDE's own libs put a junit-vintage engine on the classpath, which then fails discovery
    // because there is no JUnit 4 here. Naming the engine keeps discovery to what we actually use.
    useJUnitPlatform {
        includeEngines("junit-jupiter")
    }

    // ByteBuddyAgent self-attaches. RubyMine itself ships -Djdk.attach.allowAttachSelf=true, so the
    // test JVM is configured the same way rather than relying on the helper-process fallback.
    jvmArgs(
        "-Djdk.attach.allowAttachSelf=true",
        "-XX:+EnableDynamicAgentLoading",
    )

    // Every test shares one JVM, one weave, and BurstGuard's per-thread state. Parallel execution
    // would make the per-key counts racy.
    maxParallelForks = 1

    val bootJar = bootstrapJar.flatMap { it.archiveFile }
    inputs.file(bootJar).withPropertyName("bootstrapJar")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Drubyprobe.bootJar=${bootJar.get().asFile.absolutePath}")
        }
    )

    testLogging {
        events("passed", "skipped", "failed")
    }
}

/**
 * The invariants the hand-rolled build used to assert. They are cheap and they have both been
 * violated for real at least once, so they are wired into `check` rather than left to review.
 */
val verifyPluginLayout = tasks.register("verifyPluginLayout") {
    description = "Asserts the bootstrap classes are embedded-only and never on the plugin classpath."
    group = "verification"

    // composedJar, not jar: composedJar is the artifact the IntelliJ Platform plugin actually ships,
    // so that is the one whose layout matters. It extends org.gradle.jvm.tasks.Jar -- the base type,
    // not the org.gradle.api.tasks.bundling.Jar that `Jar` resolves to in this script.
    val pluginJar = tasks.named<org.gradle.jvm.tasks.Jar>("composedJar").flatMap { it.archiveFile }
    inputs.file(pluginJar).withPropertyName("pluginJar")
    outputs.upToDateWhen { true }

    doLast {
        ZipFile(pluginJar.get().asFile).use { outer ->
            val names = outer.entries().asSequence().map { it.name }.toSet()

            val embedded = "boot/ruby-probe-boot.jar"
            check(embedded in names) { "boot jar not embedded in the plugin jar" }

            val innerBytes = outer.getInputStream(outer.getEntry(embedded)).readBytes()
            val innerNames = ZipInputStream(innerBytes.inputStream()).use { zis ->
                generateSequence { zis.nextEntry }.map { it.name }.toList()
            }

            for (cls in listOf("ProbeState", "ProbePatch")) {
                val entry = "rocks/rdil/rubyprobe/$cls.class"
                check(entry in innerNames) { "$cls missing from the boot jar: $innerNames" }
                check(entry !in names) { "$cls leaked onto the plugin classpath" }
            }

            check(names.none { it.endsWith("AncestorsAdvice.class") }) {
                "stale sink-based AncestorsAdvice is still being shipped"
            }

            logger.lifecycle("verifyPluginLayout: boot jar embedded; bootstrap classes are not on the plugin classpath")
        }
    }
}

tasks.check {
    dependsOn(verifyPluginLayout)
}

/**
 * The IDE whose config directory `installLocal` writes to, derived from pluginSinceBuild rather than
 * picked by guesswork: build 262 is 2026.2. There is usually more than one RubyMine config directory
 * on a machine, and installing a 262-only plugin into 2026.1's would look like it silently failed.
 */
val targetIdeVersion: String = providers.gradleProperty("pluginSinceBuild").get().trim().let { since ->
    Regex("""^(\d{2})(\d)$""").matchEntire(since)
        ?.let { "20${it.groupValues[1]}.${it.groupValues[2]}" }
        ?: error("cannot derive an IDE version from pluginSinceBuild='$since'")
}

val localPluginsDir: File = providers.gradleProperty("localPluginsDir").orNull
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
    ?: System.getProperty("user.home").let { home ->
        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("mac") ->
                file("$home/Library/Application Support/JetBrains/RubyMine$targetIdeVersion/plugins")
            os.contains("win") ->
                file("${System.getenv("APPDATA")}/JetBrains/RubyMine$targetIdeVersion/plugins")
            else ->
                file("$home/.local/share/JetBrains/RubyMine$targetIdeVersion")
        }
    }

/**
 * `./gradlew installLocal` -- the equivalent of unzipping the distribution over the installed copy.
 *
 * Sync rather than Copy so stale files are removed: the pre-Gradle layout had a top-level boot/
 * directory and an unversioned jar, and leaving those behind would be confusing at best. Sync's
 * deletion is scoped to this plugin's own directory, never the plugins directory as a whole.
 */
val installLocal = tasks.register<Sync>("installLocal") {
    description = "Installs the built plugin into the local RubyMine $targetIdeVersion plugins directory."
    group = tasks.getByName("runIde").group

    val buildPluginTask = tasks.named<Zip>("buildPlugin")
    dependsOn(buildPluginTask)

    from(zipTree(buildPluginTask.flatMap { it.archiveFile })) {
        // Strip the archive's own top-level directory so its contents land directly in the target.
        eachFile {
            relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    into(localPluginsDir.resolve(rootProject.name))

    // Locals, not the script-level values: a task action that reaches back to `rootProject` or to a
    // script property captures a Gradle script object reference, which the configuration cache
    // refuses to serialize.
    val pluginsDir = localPluginsDir
    val pluginDirName = rootProject.name
    val ideVersion = targetIdeVersion
    val allowRunningIde = providers.gradleProperty("allowRunningIde")
        .map { it.toBoolean() }.orElse(false)

    val pluginsDirExisted = localPluginsDir.isDirectory

    outputs.upToDateWhen { false }

    doFirst {
        if (!pluginsDirExisted) {
            error(
                "No RubyMine $ideVersion plugins directory at:\n  $pluginsDir\n" +
                    "Point it somewhere real with -PlocalPluginsDir=/path/to/plugins"
            )
        }
        // Replacing jars under a live IDE leaves it holding half the old plugin, and it needs a
        // restart to pick up the new one regardless.
        val running = ProcessHandle.allProcesses().anyMatch {
            it.info().command().orElse("").lowercase().contains("rubymine")
        }
        if (running && !allowRunningIde.get()) {
            error(
                "RubyMine appears to be running. Quit it first, or pass -PallowRunningIde=true if " +
                    "that detection is wrong."
            )
        }
    }

    doLast {
        logger.lifecycle("installed into $pluginsDir/$pluginDirName")
        logger.lifecycle("start RubyMine, then check the 'runtime patch' block in the Ruby Probe tool window")
    }
}
