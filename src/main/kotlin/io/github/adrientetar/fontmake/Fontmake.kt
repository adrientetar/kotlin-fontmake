/*
 * Copyright 2025 the kotlin-fontmake authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.adrientetar.fontmake

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.exists
import kotlin.io.path.setPosixFilePermissions

/**
 * Output format for font compilation.
 */
enum class OutputFormat(internal val flag: String?, internal val baseFormat: OutputFormat? = null, internal val compression: CompressionFormat? = null) {
    /** Per-master OTF (CFF-outline) binaries */
    OTF("otf"),
    /** Per-master TTF (TrueType-outline) binaries */
    TTF("ttf"),
    /** Per-master OTF binaries with CFF2 outlines */
    OTF_CFF2("otf-cff2"),
    /** A TrueType variable font */
    VARIABLE("variable"),
    /** A variable font with CFF2 outlines */
    VARIABLE_CFF2("variable-cff2"),
    /** OTF binaries suitable for merging into a variable font */
    OTF_INTERPOLATABLE("otf-interpolatable"),
    /** TTF binaries suitable for merging into a variable font */
    TTF_INTERPOLATABLE("ttf-interpolatable"),
    /** Convert Glyphs sources to UFO */
    UFO("ufo"),

    // Web font formats (derived from base formats)
    /** WOFF compressed from OTF */
    WOFF_OTF(null, OTF, CompressionFormat.WOFF),
    /** WOFF compressed from TTF */
    WOFF_TTF(null, TTF, CompressionFormat.WOFF),
    /** WOFF2 compressed from OTF */
    WOFF2_OTF(null, OTF, CompressionFormat.WOFF2),
    /** WOFF2 compressed from TTF */
    WOFF2_TTF(null, TTF, CompressionFormat.WOFF2),
    /** WOFF compressed from variable TTF */
    WOFF_VARIABLE(null, VARIABLE, CompressionFormat.WOFF),
    /** WOFF2 compressed from variable TTF */
    WOFF2_VARIABLE(null, VARIABLE, CompressionFormat.WOFF2),
    /** WOFF compressed from variable CFF2 */
    WOFF_VARIABLE_CFF2(null, VARIABLE_CFF2, CompressionFormat.WOFF),
    /** WOFF2 compressed from variable CFF2 */
    WOFF2_VARIABLE_CFF2(null, VARIABLE_CFF2, CompressionFormat.WOFF2),
    ;

    /** Whether this is a native fontmake format (vs a derived web format) */
    val isNative: Boolean get() = flag != null

    /** Whether this is a web font format */
    val isWebFormat: Boolean get() = compression != null
}

/**
 * Web font compression format.
 */
enum class CompressionFormat(val extension: String) {
    /** WOFF (Web Open Font Format 1.0) - zlib compressed */
    WOFF("woff"),
    /** WOFF2 (Web Open Font Format 2.0) - Brotli compressed, smaller */
    WOFF2("woff2"),
}

/**
 * Outline/build options applied to a fontmake invocation.
 *
 * These options are part of the "compatibility key" for reusing builds:
 * two outputs can share the same underlying build only if their effective
 * options are identical.
 */
data class OutlineOptions(
    /** Remove overlaps in outlines. null = use fontmake default (off). */
    val removeOverlaps: Boolean? = null,
    /** Flatten nested components. */
    val flattenComponents: Boolean = false,
    /** Apply autohinting. null = use fontmake default (typically enabled for static TTF). */
    val autohint: Boolean? = null,
    /** Extra fontmake CLI args for this output (advanced escape hatch). */
    val extraArgs: List<String> = emptyList(),
)

/**
 * Request a specific output format with its own options.
 */
data class OutputRequest(
    val format: OutputFormat,
    val outline: OutlineOptions = OutlineOptions(),
)

/**
 * Top-level compilation options.
 */
data class CompileOptions(
    /** Requested outputs. Each output may carry its own options. */
    val outputs: List<OutputRequest> = listOf(
        OutputRequest(OutputFormat.OTF),
        OutputRequest(OutputFormat.TTF),
    ),
    /** Build static font instances (interpolated from masters). */
    val interpolateInstances: Boolean = false,
    /** Output directory (optional). If null, fontmake will use default subdirs under the working dir. */
    val outputDir: String? = null,
)

/**
 * A compiled font file.
 */
data class CompiledFont(
    /** Full path to the compiled font file */
    val path: String,
    /** Font filename (e.g., "MyFont-Bold.otf") */
    val name: String,
)

/**
 * Result of font compilation.
 */
sealed class CompilationResult {
    /** Successful compilation */
    data class Success(
        /** List of compiled font files */
        val fonts: List<CompiledFont>,
        /** Standard output from fontmake */
        val output: String,
    ) : CompilationResult()

    /** Failed compilation */
    data class Error(
        /** Error message */
        val message: String,
        /** Exit code from fontmake */
        val exitCode: Int,
        /** Full output (stdout + stderr) */
        val output: String,
    ) : CompilationResult()
}

/**
 * Kotlin wrapper for fontmake, Google's Python font compiler.
 *
 * fontmake compiles fonts from various sources (.glyphs, .ufo, .designspace)
 * into binaries (.otf, .ttf). It supports static instances and variable fonts.
 *
 * This wrapper uses a pre-compiled standalone fontmake binary bundled with the library,
 * so no Python installation is required.
 *
 * Example usage:
 * ```kotlin
 * val result = FontMake.compile(
 *     sourcePath = "/path/to/MyFont.glyphs",
 *     options = CompileOptions(
 *         outputs = listOf(
 *             OutputRequest(OutputFormat.OTF, OutlineOptions(removeOverlaps = true)),
 *             OutputRequest(OutputFormat.WOFF2_OTF, OutlineOptions(removeOverlaps = true)),
 *             OutputRequest(OutputFormat.VARIABLE),
 *         ),
 *         interpolateInstances = true,
 *     ),
 * )
 *
 * when (result) {
 *     is CompilationResult.Success -> {
 *         for (font in result.fonts) {
 *             println("Created: ${font.path}")
 *         }
 *     }
 *     is CompilationResult.Error -> {
 *         println("Compilation failed: ${result.message}")
 *     }
 * }
 * ```
 */
object FontMake {
    private val binaryPath: Path by lazy {
        extractNativeBinary()
    }

    /**
     * Compile a font from a source file.
     *
     * @param sourcePath Path to the font source file (.glyphs, .ufo, or .designspace)
     * @param options Compilation options
     * @return CompilationResult indicating success or failure
     */
    fun compile(
        sourcePath: String,
        options: CompileOptions = CompileOptions(),
    ): CompilationResult {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) {
            return CompilationResult.Error(
                message = "Source file not found: $sourcePath",
                exitCode = -1,
                output = "",
            )
        }

        val plan = planInvocations(options)
        return executePlan(sourcePath, plan, sourceFile.parentFile)
    }

    /**
     * Get the version of the bundled fontmake binary.
     *
     * @return Version string, or null if unable to determine
     */
    fun version(): String? {
        return try {
            val process = ProcessBuilder(binaryPath.toString(), "--version")
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()

            if (process.exitValue() == 0) output else null
        } catch (e: Exception) {
            null
        }
    }

    internal data class PlannedInvocation(
        val formatsToBuild: List<OutputFormat>,
        val requestedNativeFormats: Set<OutputFormat>,
        val requestedWebFormats: List<OutputFormat>,
        val interpolateInstances: Boolean,
        val outputDir: String?,
        val effectiveOutline: EffectiveOutlineOptions,
    )

    internal data class EffectiveOutlineOptions(
        val removeOverlaps: Boolean?,
        val flattenComponents: Boolean,
        val autohint: Boolean?,
        val extraArgs: List<String>,
    )

    internal fun planInvocations(options: CompileOptions): List<PlannedInvocation> {
        data class Need(
            val baseFormat: OutputFormat,
            val requestedFormat: OutputFormat,
            val effectiveOutline: EffectiveOutlineOptions,
        )

        val needs = options.outputs.distinct().mapNotNull { request ->
            val base = request.format.baseFormat ?: request.format
            if (!base.isNative) return@mapNotNull null
            Need(
                baseFormat = base,
                requestedFormat = request.format,
                effectiveOutline = resolveEffectiveOutline(base, request.outline),
            )
        }

        val grouped = needs.groupBy { need ->
            Triple(
                options.interpolateInstances,
                options.outputDir,
                need.effectiveOutline,
            )
        }

        return grouped.entries.map { (key, groupNeeds) ->
            val requestedWebFormats = groupNeeds
                .map { it.requestedFormat }
                .filter { it.isWebFormat }

            val requestedNativeFormats = groupNeeds
                .map { it.requestedFormat }
                .filter { it.isNative }
                .toSet()

            PlannedInvocation(
                formatsToBuild = groupNeeds.map { it.baseFormat }.distinct(),
                requestedNativeFormats = requestedNativeFormats,
                requestedWebFormats = requestedWebFormats,
                interpolateInstances = key.first,
                outputDir = key.second,
                effectiveOutline = key.third,
            )
        }
    }

    private fun resolveEffectiveOutline(baseFormat: OutputFormat, outline: OutlineOptions): EffectiveOutlineOptions {
        return EffectiveOutlineOptions(
            removeOverlaps = outline.removeOverlaps,
            flattenComponents = outline.flattenComponents,
            autohint = outline.autohint,
            extraArgs = outline.extraArgs,
        )
    }

    private fun buildCommandLine(sourcePath: String, invocation: PlannedInvocation): List<String> {
        return buildList {
            add(binaryPath.toString())
            add(sourcePath)

            if (invocation.formatsToBuild.isNotEmpty()) {
                add("-o")
                addAll(invocation.formatsToBuild.mapNotNull { it.flag })
            }

            invocation.outputDir?.let {
                add("--output-dir")
                add(it)
            }

            if (invocation.interpolateInstances) {
                add("-i")
            }

            if (invocation.effectiveOutline.flattenComponents) {
                add("-f")
            }

            when (invocation.effectiveOutline.removeOverlaps) {
                true -> { /* fontmake default is to remove overlaps, so no flag needed */ }
                false -> add("--keep-overlaps")
                null -> { /* use fontmake default */ }
            }

            when (invocation.effectiveOutline.autohint) {
                true -> add("--autohint")
                false -> add("--no-autohint")
                null -> { /* use fontmake default */ }
            }

            addAll(invocation.effectiveOutline.extraArgs)
        }
    }

    private fun executePlan(
        sourcePath: String,
        plan: List<PlannedInvocation>,
        workingDir: File?,
    ): CompilationResult {
        val allFonts = mutableListOf<CompiledFont>()
        val outputBuilder = StringBuilder()

        for ((index, invocation) in plan.withIndex()) {
            val args = buildCommandLine(sourcePath, invocation)
            val result = executeInvocation(args, workingDir, invocation)
            when (result) {
                is CompilationResult.Success -> {
                    allFonts.addAll(result.fonts)
                    outputBuilder.appendLine("# Invocation ${index + 1}/${plan.size}")
                    outputBuilder.appendLine(result.output)
                }
                is CompilationResult.Error -> {
                    outputBuilder.appendLine("# Invocation ${index + 1}/${plan.size} FAILED")
                    outputBuilder.appendLine(result.output)
                    return CompilationResult.Error(
                        message = result.message,
                        exitCode = result.exitCode,
                        output = outputBuilder.toString(),
                    )
                }
            }
        }

        return CompilationResult.Success(fonts = allFonts, output = outputBuilder.toString())
    }

    private fun executeInvocation(
        args: List<String>,
        workingDir: File?,
        invocation: PlannedInvocation,
    ): CompilationResult {
        return try {
            val processBuilder = ProcessBuilder(args)
                .redirectErrorStream(true)

            workingDir?.let { processBuilder.directory(it) }

            val baseDirForOutputs = invocation.outputDir?.let { File(it) } ?: workingDir
            val preSnapshot = snapshotOutputs(baseDirForOutputs)

            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                val outputBuilder = StringBuilder(output)
                val allBuiltFonts = findOutputFiles(workingDir, invocation.outputDir)
                val newlyBuiltFonts = filterNewOutputs(allBuiltFonts, snapshotOutputs(baseDirForOutputs), preSnapshot)

                // Compress to requested web formats
                val webFonts = mutableListOf<CompiledFont>()
                for (webFormat in invocation.requestedWebFormats) {
                    val baseFormat = webFormat.baseFormat ?: continue
                    val compression = webFormat.compression ?: continue
                    val extension = when (baseFormat) {
                        OutputFormat.OTF -> "otf"
                        OutputFormat.TTF, OutputFormat.VARIABLE -> "ttf"
                        OutputFormat.VARIABLE_CFF2 -> "otf"
                        else -> continue
                    }

                    val baseFonts = newlyBuiltFonts.filter { it.path.endsWith(".$extension") }
                    for (baseFont in baseFonts) {
                        val inputFile = File(baseFont.path)
                        val outputFile = File(
                            inputFile.parentFile,
                            inputFile.nameWithoutExtension + "." + compression.extension,
                        )

                        if (compress(baseFont.path, outputFile.absolutePath, compression)) {
                            webFonts.add(
                                CompiledFont(
                                    path = outputFile.absolutePath,
                                    name = outputFile.name,
                                )
                            )
                            outputBuilder.appendLine("Compressed: ${baseFont.name} -> ${outputFile.name}")
                        } else {
                            return CompilationResult.Error(
                                message = "Failed to compress ${baseFont.name} to ${compression.extension}",
                                exitCode = -1,
                                output = outputBuilder.toString(),
                            )
                        }
                    }
                }

                val requestedExtensions = invocation.requestedNativeFormats
                    .flatMap { nativeExtensionsFor(it) }
                    .toSet()

                val requestedNativeFonts = if (requestedExtensions.isEmpty()) emptyList() else {
                    newlyBuiltFonts.filter { font ->
                        File(font.path).extension.lowercase() in requestedExtensions
                    }
                }

                CompilationResult.Success(
                    fonts = requestedNativeFonts + webFonts,
                    output = outputBuilder.toString(),
                )
            } else {
                CompilationResult.Error(
                    message = "fontmake exited with code $exitCode",
                    exitCode = exitCode,
                    output = output,
                )
            }
        } catch (e: Exception) {
            CompilationResult.Error(
                message = "Failed to execute fontmake: ${e.message}",
                exitCode = -1,
                output = "",
            )
        }
    }

    private fun nativeExtensionsFor(format: OutputFormat): Set<String> {
        return when (format) {
            OutputFormat.OTF,
            OutputFormat.OTF_CFF2,
            OutputFormat.OTF_INTERPOLATABLE,
            OutputFormat.VARIABLE_CFF2 -> setOf("otf")

            OutputFormat.TTF,
            OutputFormat.TTF_INTERPOLATABLE,
            OutputFormat.VARIABLE -> setOf("ttf")

            OutputFormat.UFO -> setOf("ufo", "ufoz")

            // Derived formats are not produced directly by fontmake in our wrapper.
            OutputFormat.WOFF_OTF,
            OutputFormat.WOFF_TTF,
            OutputFormat.WOFF2_OTF,
            OutputFormat.WOFF2_TTF,
            OutputFormat.WOFF_VARIABLE,
            OutputFormat.WOFF2_VARIABLE,
            OutputFormat.WOFF_VARIABLE_CFF2,
            OutputFormat.WOFF2_VARIABLE_CFF2 -> emptySet()
        }
    }

    private data class OutputSnapshotEntry(
        val lastModified: Long,
        val isDirectory: Boolean,
    )

    private fun snapshotOutputs(baseDir: File?): Map<String, OutputSnapshotEntry> {
        if (baseDir == null || !baseDir.exists()) return emptyMap()

        val allowedExtensions = setOf("otf", "ttf", "ufo", "ufoz", "woff", "woff2")
        return baseDir
            .walkTopDown()
            .filter { it.exists() }
            .filter { file ->
                val ext = file.extension.lowercase()
                ext in allowedExtensions && (file.isFile || (ext == "ufo" && file.isDirectory))
            }
            .associate { file ->
                file.absolutePath to OutputSnapshotEntry(
                    lastModified = file.lastModified(),
                    isDirectory = file.isDirectory,
                )
            }
    }

    private fun filterNewOutputs(
        outputs: List<CompiledFont>,
        postSnapshot: Map<String, OutputSnapshotEntry>,
        preSnapshot: Map<String, OutputSnapshotEntry>,
    ): List<CompiledFont> {
        return outputs.filter { font ->
            val post = postSnapshot[font.path] ?: return@filter false
            val pre = preSnapshot[font.path]

            // New file/dir, or updated timestamp.
            pre == null || post.lastModified > pre.lastModified
        }
    }

    /**
     * Compress a font file to WOFF or WOFF2 format.
     *
     * @param inputPath Path to the input font file (.otf or .ttf)
     * @param outputPath Path for the output web font file
     * @param format Compression format (WOFF or WOFF2)
     * @return true if compression succeeded, false otherwise
     */
    fun compress(
        inputPath: String,
        outputPath: String,
        format: CompressionFormat,
    ): Boolean {
        val inputFile = File(inputPath)
        val outputFile = File(outputPath)

        if (!inputFile.exists()) {
            return false
        }

        return try {
            val args = listOf(
                binaryPath.toString(),
                "--compress",
                format.extension,
                inputFile.absolutePath,
                outputFile.absolutePath,
            )

            val process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()

            process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            exitCode == 0 && outputFile.exists()
        } catch (e: Exception) {
            false
        }
    }

    private fun findOutputFiles(workingDir: File?, outputDir: String?): List<CompiledFont> {
        val baseDir = outputDir?.let { File(it) } ?: workingDir ?: return emptyList()
        if (!baseDir.exists()) return emptyList()

        val allowedExtensions = setOf("otf", "ttf", "ufo", "ufoz", "woff", "woff2")

        return baseDir
            .walkTopDown()
            .filter { it.exists() }
            .filter { file ->
                val ext = file.extension.lowercase()
                ext in allowedExtensions && (file.isFile || (ext == "ufo" && file.isDirectory))
            }
            .map { file ->
                CompiledFont(
                    path = file.absolutePath,
                    name = file.name,
                )
            }
            .toList()
    }

    private fun extractNativeBinary(): Path {
        val platform = detectPlatform()
        val binaryName = if (platform.startsWith("windows")) "fontmake.exe" else "fontmake"
        val resourcePath = "/natives/$platform/$binaryName"

        // Check if resource exists
        val resourceStream = FontMake::class.java.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException(
                "Native fontmake binary not found for platform: $platform. " +
                    "Resource path: $resourcePath"
            )

        // Extract to temp directory
        val tempDir = Files.createTempDirectory("kotlin-fontmake")
        val binaryPath = tempDir.resolve(binaryName)

        resourceStream.use { input ->
            FileOutputStream(binaryPath.toFile()).use { output ->
                input.copyTo(output)
            }
        }

        // Make executable on Unix systems
        if (!platform.startsWith("windows")) {
            try {
                binaryPath.setPosixFilePermissions(
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    )
                )
            } catch (e: UnsupportedOperationException) {
                // Windows doesn't support POSIX permissions
            }
        }

        // Clean up on JVM exit
        Runtime.getRuntime().addShutdownHook(Thread {
            try {
                Files.deleteIfExists(binaryPath)
                Files.deleteIfExists(tempDir)
            } catch (_: Exception) {
                // Best effort cleanup
            }
        })

        return binaryPath
    }

    private fun detectPlatform(): String {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()

        val os = when {
            "mac" in osName || "darwin" in osName -> "macos"
            "windows" in osName -> "windows"
            else -> "linux"
        }

        val arch = when (osArch) {
            "aarch64", "arm64" -> "arm64"
            else -> "x64"
        }

        return "$os-$arch"
    }
}
