import org.gradle.api.attributes.java.TargetJvmVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `java-library`
    id("me.modmuss50.mod-publish-plugin") version "2.2.1"
}

/** Reads a value from `gradle.properties`, failing the build when it is missing. */
fun gradleProperty(name: String) = providers.gradleProperty(name).get()

val maven_group = gradleProperty("maven_group")
val archives_base_name = gradleProperty("archives_base_name")
val license = gradleProperty("license")
val mod_version = gradleProperty("mod_version")
val mod_id = gradleProperty("mod_id")
val mod_name = gradleProperty("mod_name")
val mod_description = gradleProperty("mod_description")
val mod_authors = gradleProperty("mod_authors")
val mod_github = gradleProperty("mod_github")
val mod_issues = gradleProperty("mod_issues")
val icon = gradleProperty("icon")

val toolkit_version = gradleProperty("toolkit_version")
val java_version = gradleProperty("java_version")
val kotlinmcui_version = gradleProperty("kotlinmcui_version")

val fabric_loader_version = gradleProperty("fabric_loader_version")
val forge_version = gradleProperty("forge_version")
val forge_eventbus_version = gradleProperty("forge_eventbus_version")
val neoforge_loader_version = gradleProperty("neoforge_loader_version")
val neoforge_bus_version = gradleProperty("neoforge_bus_version")

val slf4j_version = gradleProperty("slf4j_version")
val fastutil_version = gradleProperty("fastutil_version")
val coroutines_version = gradleProperty("coroutines_version")
val serialization_version = gradleProperty("serialization_version")

base.archivesName = archives_base_name

// One jar serves every loader, so the version carries no loader and no game version: the metadata
// inside declares the same number to all three.
group = maven_group
version = mod_version

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.minecraftforge.net/")
    maven("https://maven.neoforged.net/releases/")
    maven("https://jitpack.io")
}

// The platform supplies every runtime dependency of the core library, so the window backend links
// against them exactly the way the core does and never bundles them.
val slf4j = "org.slf4j:slf4j-api:$slf4j_version"
val fastutil = "it.unimi.dsi:fastutil:$fastutil_version"
val serializationCore = "org.jetbrains.kotlinx:kotlinx-serialization-core:$serialization_version"
val serializationJson = "org.jetbrains.kotlinx:kotlinx-serialization-json:$serialization_version"
val coroutines = "org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines_version"

/**
 * The core library: the sibling checkout's own jars, or the published artifact for a clean checkout.
 *
 * A sibling checkout is a decision, not a hint: when `../kotlinmcui` exists the build must never fall
 * back to the published artifact, because a missing local jar used to switch the whole build
 * silently - and the cached published one can be months old, so the build is green while compiling
 * against a different core library. A missing artifact is therefore a build error that names the
 * task to run. There is no compile dependency on the headless backend - the two only meet at
 * runtime, where the loaders' metadata declares the pairing.
 */
val siblingCore = rootProject.file("../kotlinmcui")
val coreLibrary: Any = if (siblingCore.isDirectory) {
    val jarFile = siblingCore.resolve("build/libs/kotlinmcui-$kotlinmcui_version.jar")
    val sourceFile = siblingCore.resolve("build/libs/kotlinmcui-$kotlinmcui_version-sources.jar")
    check(jarFile.exists()) { "the local core jar is missing: run ./gradlew jar in $siblingCore" }
    check(sourceFile.exists()) { "the local core sources jar is missing: run ./gradlew sourcesJar in $siblingCore" }
    files(jarFile, sourceFile)
} else {
    "com.github.2894638479:KotlinMCUI:v$kotlinmcui_version"
}

/**
 * What the standalone `main()` needs on its classpath and a loader supplies in a game. Kept out of
 * `runtimeClasspath` so the built jar stays a mod jar and nothing is bundled.
 */
val standalone = configurations.create("standalone") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    compileOnly(coreLibrary)
    compileOnly(slf4j)
    compileOnly(fastutil)
    compileOnly(coroutines)
    compileOnly(serializationCore)
    compileOnly(serializationJson)
    // The preview session reads Kotlin reflection to tell which page parameters have defaults; it
    // is never bundled and only the standalone run has to ask for it explicitly.
    compileOnly(kotlin("reflect"))

    // Each entry point is compiled against exactly one loader and the other two never load that
    // class, so all three are compile-only: no loader is pulled in by the artifact. Every one of
    // them is non-transitive as well - an entry point names at most three classes, and dragging a
    // loader's whole dependency tree onto the compile classpath only creates version clashes.
    compileOnly("net.fabricmc:fabric-loader:$fabric_loader_version") { isTransitive = false }
    // `net.minecraftforge:forge` is a BOM-shaped POM, so the classes come from the `universal`
    // classifier, and `net.minecraftforge.fml.common.Mod` lives in the Java language provider.
    compileOnly("net.minecraftforge:forge:$forge_version:universal") { isTransitive = false }
    compileOnly("net.minecraftforge:javafmllanguage:$forge_version") { isTransitive = false }
    compileOnly("net.minecraftforge:fmlcore:$forge_version") { isTransitive = false }
    compileOnly("net.minecraftforge:eventbus:$forge_eventbus_version") { isTransitive = false }
    // NeoForge's `net.neoforged.fml` package is FancyModLoader's, and its event bus is its own.
    compileOnly("net.neoforged.fancymodloader:loader:$neoforge_loader_version") { isTransitive = false }
    compileOnly("net.neoforged:bus:$neoforge_bus_version") { isTransitive = false }

    standalone(coreLibrary)
    standalone(slf4j)
    // A logging backend, so the standalone run says what it is doing: nothing else on this classpath
    // implements the SLF4J binding the framework logs through.
    standalone("org.slf4j:slf4j-simple:$slf4j_version")
    standalone(fastutil)
    standalone(coroutines)
    standalone(serializationCore)
    standalone(serializationJson)
    standalone(kotlin("reflect"))

    // The core library is a jar file dependency, so it brings no transitive dependency with it, and
    // most of what the framework needs at runtime is `compileOnly` for the main source set. Anything
    // the tests actually execute is therefore named here as well.
    testImplementation(kotlin("test"))
    testImplementation(coreLibrary)
    testImplementation(slf4j)
    testImplementation(serializationJson)
    // The coexistence cases build a real root, so they need the framework's runtime pieces that
    // the main source set only compiles against: compileOnly names nothing for the test runtime.
    testImplementation(coroutines)
    testImplementation(fastutil)
}

kotlin {
    // Compiling on 21 while emitting Java 8 bytecode is what lets one jar load on every game
    // version the core library supports: the toolchain is the build, the target is the game.
    jvmToolchain(toolkit_version.toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.valueOf("JVM_$java_version"))
        // Link against the JDK 8 API surface rather than the toolchain's, exactly as the core
        // library does: a JDK 9+ call would compile here and fail on an older game runtime.
        freeCompilerArgs.add("-Xjdk-release=1.8")
    }
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.valueOf("VERSION_$java_version")
    targetCompatibility = JavaVersion.valueOf("VERSION_$java_version")
}

// What this project *emits* is Java 8, and `jvmTarget` above is where that is decided. What it has
// to *read* is another matter: the three loader APIs are published as Java 17 and 21 class files,
// and a compiler reads a newer class file happily. Gradle would otherwise refuse them outright -
// module metadata records the Java version as a resolution constraint - so the compile classpath
// asks for the newest of the three while the bytecode this project writes stays where the game
// needs it.
configurations.compileClasspath {
    attributes {
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}

val sourcesJar = tasks.named<Jar>("sourcesJar").get()
sourcesJar.exclude("fabric.mod.json")
sourcesJar.exclude("META-INF/mods.toml")
sourcesJar.exclude("META-INF/neoforge.mods.toml")
sourcesJar.from("LICENSE")

tasks.jar {
    from("LICENSE")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val map = mapOf(
        "license" to license,
        "mod_version" to mod_version,
        "mod_id" to mod_id,
        "mod_name" to mod_name,
        "mod_description" to mod_description,
        "mod_authors" to mod_authors,
        "mod_github" to mod_github,
        "mod_issues" to mod_issues,
        "icon" to icon,
        "kotlinmcui_version" to kotlinmcui_version,
    )
    inputs.properties(map)
    filesMatching("fabric.mod.json") { expand(map) }
    filesMatching("META-INF/mods.toml") { expand(map) }
    filesMatching("META-INF/neoforge.mods.toml") { expand(map) }
}

/**
 * Opens the window with no game behind it.
 *
 * `-Ppreview=<page reference>` hands the same reference the framework's in-game preview takes to
 * `DslPreview`; `-Pcapture=true` photographs the window and leaves, which is the run the acceptance
 * check uses, and `-PcaptureName=<file>` names the file it writes.
 */
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Opens the Swing window and renders a demo page, without Minecraft."
    mainClass.set("io.github.u2894638479.kotlinmcuiwindow.StandaloneKt")
    classpath = files(sourceSets["main"].output, sourceSets["main"].runtimeClasspath, standalone)
    // The screenshot directory hangs off the game directory, which is the working directory.
    workingDir = projectDir
    providers.gradleProperty("preview").orNull?.let { systemProperty("kotlinmcui.preview", it) }
    if(providers.gradleProperty("capture").orNull.toBoolean()) {
        systemProperty(
            "kotlinmcuiwindow.capture",
            providers.gradleProperty("captureName").getOrElse("kotlinmcuiwindow-demo.png"),
        )
        // A preview session photographs itself; telling it to as well is what makes
        // `-Ppreview=... -Pcapture=true` gate on the page rather than on the placeholder.
        if(providers.gradleProperty("preview").isPresent) systemProperty("kotlinmcui.preview.capture", "true")
    }
}

/**
 * The same code as a program of its own.
 *
 * The artifact a loader takes is [tasks.jar]: it carries the three metadata files and expects the
 * core library to be another mod installed next to it. This one is the opposite - everything
 * `main()` needs is inside it, and the loader metadata is left out, because a jar that carries the
 * core library must never be dropped into `mods/`: the game would end up with a second copy of it.
 *
 * `java -jar build/libs/<name>-standalone.jar` opens the window with no game and no build.
 */
val standaloneJar = tasks.register<Jar>("standaloneJar") {
    group = "build"
    description = "Packages the window as a runnable jar, with everything its main() needs inside."
    archiveClassifier = "standalone"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "io.github.u2894638479.kotlinmcuiwindow.StandaloneKt" }
    from(sourceSets["main"].output)
    from(standalone.map { if (it.isDirectory) it else zipTree(it) })
    // A program is not a mod: no loader metadata, so nothing mistakes it for one.
    exclude("fabric.mod.json","META-INF/mods.toml","META-INF/neoforge.mods.toml")
}

tasks.named("assemble") { dependsOn(standaloneJar) }

val tag = "v$mod_version"

// A checkout without an `origin` remote - a fresh clone of nothing, a sandbox - has no tag to find
// and therefore publishes; the same rule the other repositories use, made total.
val shouldPublish by lazy {
    runCatching {
        providers.exec {
            commandLine("git","ls-remote","--tags","origin","refs/tags/$tag")
        }.standardOutput.asText.get()
    }.getOrDefault("").isBlank()
}
tasks.configureEach {
    if(group == "publishing") enabled = shouldPublish
}

publishMods {
    file = tasks.jar.get().archiveFile
    additionalFiles.from(sourcesJar.archiveFile)
    changelog = "no changelog."
    type = when {
        mod_version.contains("SNAPSHOT",true) -> ALPHA
        mod_version.contains("alpha",true) -> ALPHA
        mod_version.contains("beta",true) -> BETA
        else -> STABLE
    }
    displayName = "$mod_name $mod_version"
    // The core library is the one that lists the loaders on the storefronts; this jar only ever
    // ships as its companion.
    modLoaders.addAll("fabric","forge","neoforge")

    github {
        accessToken = providers.environmentVariable("GITHUB_TOKEN")
        repository = "2894638479/KotlinMCUI-window"
        commitish = "master"
        tagName = tag
    }
}
