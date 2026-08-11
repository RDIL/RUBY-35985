import org.jetbrains.changelog.Changelog
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    java
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

// Java, not Kotlin: this is a deliberate deviation from the IntelliJ Platform Plugin Template. The
// sources are all Java and one of them (ProbeState) has to stay strictly JDK-only, so there is
// nothing for the Kotlin plugin to do except add a stdlib we must not bundle.

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

java {
    // 25, not 21: RubyMine 2026.2's platform jars are class file version 69 (Java 25), and a JDK 21
    // javac cannot read them at all -- "class file has wrong version 69.0, should be 65.0". The IDE
    // runs on JBR 25, so this also matches what the woven code will execute on.
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

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
        compileClasspath += bootstrap.output
    }
    test {
        compileClasspath += bootstrap.output
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
        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (getOrNull(project.version.toString()) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }
}

changelog {
    version = project.version.toString()
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

tasks.test {
    // The IDE's own libs put a junit-vintage engine on the classpath, which then fails discovery
    // because there is no JUnit 4 here. Naming the engine keeps discovery to what we actually use.
    useJUnitPlatform {
        includeEngines("junit-jupiter")
    }

    // ByteBuddyAgent self-attaches. RubyMine itself ships -Djdk.attach.allowAttachSelf=true, so the
    // test JVM is configured the same way rather than relying on the helper-process fallback.
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")

    // Every test shares one JVM and ProbePatch's statics, so weaving happens once and the guard
    // state is reset per test. Parallel execution would make that racy.
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
    // so that is the one whose layout matters.
    val pluginJar = tasks.named<Jar>("composedJar").flatMap { it.archiveFile }
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
