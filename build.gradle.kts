import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("java-library")
    alias(libs.plugins.maven.publish)
}

val GROUP: String by project
val VERSION_NAME: String by project

group = GROUP
// Pass -Prelease to publish the clean version; otherwise builds use -SNAPSHOT
// to avoid conflicting with Maven Central releases.
version = if (providers.gradleProperty("release").isPresent) VERSION_NAME else "$VERSION_NAME-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.truth)
}

tasks.test {
    useJUnitPlatform()
}

// Platform detection
val osName = providers.systemProperty("os.name").map { it.lowercase() }
val osArch = providers.systemProperty("os.arch").map { it.lowercase() }

val currentOs = osName.map { name ->
    when {
        name.contains("mac") || name.contains("darwin") -> "macos"
        name.contains("windows") -> "windows"
        else -> "linux"
    }
}

val currentArch = osArch.map { arch ->
    when {
        arch == "aarch64" || arch == "arm64" -> "arm64"
        else -> "x64"
    }
}

val platformClassifier = providers.zip(currentOs, currentArch) { os, arch -> "$os-$arch" }

val nativeBinaryName = currentOs.map { os ->
    when (os) {
        "windows" -> "fontmake.exe"
        else -> "fontmake"
    }
}

// Directory for platform-specific native binary
val nativeBinDir = layout.buildDirectory.dir(platformClassifier.map { "native/$it" })

// fontmake version to build
val fontmakeVersion = providers.environmentVariable("FONTMAKE_VERSION").orElse("3.11.1")

// uv package manager - resolve from PATH (optionally override via UV_PATH)
val uvPath = providers.environmentVariable("UV_PATH").orElse("uv")

// Python version to use for building the native binary (uv will download if needed)
val pythonVersion = providers.environmentVariable("PYTHON_VERSION").orElse("3.11")

// Virtual environment directory
val venvDir = layout.buildDirectory.dir("venv")
val venvPython = venvDir.map { dir ->
    val os = System.getProperty("os.name").lowercase()
    if (os.contains("windows")) {
        dir.file("Scripts/python.exe").asFile.absolutePath
    } else {
        dir.file("bin/python").asFile.absolutePath
    }
}

// Nuitka output directory
val nuitkaOutputDir = layout.buildDirectory.dir("nuitka")

val fontmakeEntryPoint = file("src/main/python/fontmake_entry.py")

// Create virtual environment with uv
tasks.register<Exec>("createVenv") {
    description = "Create Python virtual environment using uv"
    executable = uvPath.get()

    val venvPythonFile = venvPython.map { File(it) }

    args(
        "venv",
        "--python", pythonVersion.get(),
        "--managed-python",
        venvDir.get().asFile.absolutePath,
    )
    workingDir = projectDir

    // Recreate the venv if it exists but is broken (e.g. missing python).
    doFirst {
        val venvRoot = venvDir.get().asFile
        val pythonExe = venvPythonFile.get()
        if (venvRoot.exists() && !pythonExe.exists()) {
            venvRoot.deleteRecursively()
        }
    }

    inputs.property("pythonVersion", pythonVersion.get())
    inputs.property("uvExecutable", executable)
    outputs.dir(venvDir)
    outputs.file(venvPythonFile)
}

// Install dependencies with uv
tasks.register<Exec>("installNuitkaDependencies") {
    description = "Install Nuitka and fontmake Python packages using uv"
    dependsOn("createVenv")
    executable = uvPath.get()
    args(
        "pip",
        "install",
        "--quiet",
        "--python",
        venvPython.get(),
        "nuitka",
        "fontmake==${fontmakeVersion.get()}",
        // These are commonly imported by fontmake's dependency stack but are not
        // declared directly on the fontmake distribution.
        "cu2qu",
        "defcon",
        "mutatorMath",
        "fs",
        // Brotli is required for WOFF2 compression
        "brotli",
    )
    workingDir = projectDir
}

tasks.register<Exec>("buildFontmakeBinary") {
    description = "Build fontmake as a onefile binary using Nuitka"
    dependsOn("installNuitkaDependencies")

    val outputDir = nuitkaOutputDir.get().asFile
    val outputBinary = outputDir.resolve(nativeBinaryName.get())

    // Define Nuitka arguments as a list so we can track them as inputs
    val nuitkaArgs = listOf(
        "-m", "nuitka",
        "--mode=onefile",
        // Prefer following imports over forcing entire packages; forcing packages
        // tends to pull in tests and other heavy optional deps.
        "--follow-imports",
        "--include-package=fontmake",
        // openstep_plist is a Cython module that glyphsLib uses for parsing .glyphs files
        "--include-package=openstep_plist",
        // glyphsLib.filters is dynamically loaded by ufo2ft
        "--include-package=glyphsLib.filters",
        // glyphsLib needs its data files (GlyphData.xml, etc.) at runtime
        "--include-package-data=glyphsLib",
        // cffsubr needs its bundled 'tx' binary at runtime
        "--include-package-data=cffsubr",

        // Anti-bloat: avoid pulling in interactive shells, test frameworks, docs tooling, etc.
        "--noinclude-setuptools-mode=nofollow",
        "--noinclude-pytest-mode=nofollow",
        "--noinclude-unittest-mode=nofollow",
        "--noinclude-pydoc-mode=nofollow",
        "--noinclude-IPython-mode=nofollow",
        // Some libraries import doctest helpers; not needed for deployments.
        "--noinclude-custom-mode=doctest:nofollow",
        "--output-filename=${nativeBinaryName.get()}",
        "--output-dir=${outputDir.absolutePath}",
        "--remove-output",
        "--assume-yes-for-downloads",
        fontmakeEntryPoint.absolutePath,
    )

    // Track inputs so Gradle knows when to rebuild
    inputs.property("nuitkaArgs", nuitkaArgs)
    inputs.file(fontmakeEntryPoint)

    // Track output binary specifically (not the whole dir which Nuitka cleans)
    outputs.file(outputBinary)

    doFirst {
        outputDir.mkdirs()
    }

    executable = venvPython.get()
    args(nuitkaArgs)
    workingDir = projectDir
}

tasks.register<Copy>("copyNativeBinary") {
    description = "Copy the built fontmake binary to platform-specific directory"
    dependsOn("buildFontmakeBinary")

    from(nuitkaOutputDir) {
        include(nativeBinaryName.get())
    }
    into(nativeBinDir)
}

tasks.register("prepareNative") {
    description = "Prepare native fontmake binary"
    dependsOn("copyNativeBinary")
}

// For tests, ensure the native binary is available as a classpath resource.
tasks.register<Copy>("copyNativeBinaryForTest") {
    description = "Copy native fontmake binary to test resources"
    dependsOn("copyNativeBinary")
    from(nativeBinDir) {
        include("fontmake", "fontmake.exe")
        into("natives/${platformClassifier.get()}")
    }
    into(layout.buildDirectory.dir("resources/test"))
}

tasks.named("processTestResources") {
    dependsOn("copyNativeBinaryForTest")
}

tasks.test {
    dependsOn("copyNativeBinaryForTest")
}

// Base JAR with only Kotlin code (native binaries are in classifier JARs)
tasks.jar {
    archiveClassifier.set("")
}

// Platform-specific JAR containing only the native binary
val nativeJar by tasks.registering(Jar::class) {
    dependsOn("copyNativeBinary")
    archiveClassifier.set(platformClassifier)
    from(nativeBinDir) {
        include("fontmake", "fontmake.exe")
        into("natives/${platformClassifier.get()}")
    }
}

// Platform-specific native JARs (used by CI publish job when multiple native binaries are present)
val currentNativeClassifier = platformClassifier.get()
val allNativeClassifiers = listOf(
    "macos-arm64",
    "macos-x64",
    "linux-x64",
    "linux-arm64",
    "windows-x64",
    "windows-arm64",
)

val nativeJarsByClassifier: Map<String, TaskProvider<Jar>> =
    allNativeClassifiers
        .filter { it != currentNativeClassifier }
        .associateWith { classifier ->
            tasks.register<Jar>("nativeJar_${classifier.replace('-', '_')}") {
                archiveClassifier.set(classifier)
                val dir = layout.buildDirectory.dir("native/$classifier")
                from(dir) {
                    into("natives/$classifier")
                }
                onlyIf { dir.get().asFile.exists() && (dir.get().asFile.listFiles()?.isNotEmpty() == true) }
            }
        }

tasks.named("build") {
    dependsOn(nativeJar)
}

// Hook into vanniktech maven-publish to add native JARs as additional artifacts
publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(nativeJar)
        // Only publish additional platform artifacts when CI has produced them.
        if (providers.environmentVariable("CI").isPresent) {
            nativeJarsByClassifier.values.forEach { artifact(it) }
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("CI").isPresent) {
        signAllPublications()
    }
}
