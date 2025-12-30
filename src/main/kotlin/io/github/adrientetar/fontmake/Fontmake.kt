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
 * Options for font compilation.
 */
data class CompileOptions(
    /** Output formats to generate (default: OTF + TTF) */
    val formats: List<OutputFormat> = listOf(OutputFormat.OTF, OutputFormat.TTF),
    /** Build static font instances (interpolated from masters) */
    val interpolateInstances: Boolean = false,
    /** Flatten nested components */
    val flattenComponents: Boolean = false,
    /** Specific output directory (optional, uses format-specific subdirs if null) */
    val outputDir: String? = null,
    /** Specific output path (only valid for single output file) */
    val outputPath: String? = null,
    /** Additional command-line arguments */
    val extraArgs: List<String> = emptyList(),
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
 *         formats = listOf(OutputFormat.OTF, OutputFormat.VARIABLE),
 *         interpolateInstances = true
 *     )
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

        val args = buildCommandLine(sourcePath, options)
        return executeCommand(args, sourceFile.parentFile, options)
    }

    /**
     * Compile a font from a source file to a specific output directory.
     *
     * @param sourcePath Path to the font source file
     * @param outputDir Directory to write compiled fonts
     * @param formats Output formats to generate
     * @return CompilationResult indicating success or failure
     */
    fun compile(
        sourcePath: String,
        outputDir: String,
        vararg formats: OutputFormat,
    ): CompilationResult {
        return compile(
            sourcePath = sourcePath,
            options = CompileOptions(
                formats = formats.toList().ifEmpty { listOf(OutputFormat.OTF, OutputFormat.TTF) },
                outputDir = outputDir,
            ),
        )
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

    private fun buildCommandLine(sourcePath: String, options: CompileOptions): List<String> {
        // Separate native formats from web formats
        val nativeFormats = options.formats.filter { it.isNative }
        val webFormats = options.formats.filter { it.isWebFormat }

        // Determine which base formats need to be built for web formats
        val baseFormatsForWeb = webFormats.mapNotNull { it.baseFormat }.toSet()

        // Combine: native formats + any base formats needed for web that aren't already included
        val formatsToBuild = (nativeFormats + baseFormatsForWeb.filter { base ->
            nativeFormats.none { it == base }
        }).distinct()

        return buildList {
            add(binaryPath.toString())

            // Source file
            add(sourcePath)

            // Output formats (only native ones)
            if (formatsToBuild.isNotEmpty()) {
                add("-o")
                addAll(formatsToBuild.mapNotNull { it.flag })
            }

            // Output directory
            options.outputDir?.let {
                add("--output-dir")
                add(it)
            }

            // Output path (single file)
            options.outputPath?.let {
                add("--output-path")
                add(it)
            }

            // Interpolate instances
            if (options.interpolateInstances) {
                add("-i")
            }

            // Flatten components
            if (options.flattenComponents) {
                add("-f")
            }

            // Extra arguments
            addAll(options.extraArgs)
        }
    }

    private fun executeCommand(
        args: List<String>,
        workingDir: File?,
        options: CompileOptions,
    ): CompilationResult {
        return try {
            val processBuilder = ProcessBuilder(args)
                .redirectErrorStream(true)

            workingDir?.let { processBuilder.directory(it) }

            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                val allBuiltFonts = findOutputFiles(workingDir, options)
                val outputBuilder = StringBuilder(output)

                // Determine which formats were requested
                val requestedNativeFormats = options.formats.filter { it.isNative }.toSet()
                val webFormats = options.formats.filter { it.isWebFormat }

                // Determine which base formats were only built for web (not explicitly requested)
                val baseFormatsOnlyForWeb = webFormats
                    .mapNotNull { it.baseFormat }
                    .filter { it !in requestedNativeFormats }
                    .toSet()

                // Compress to web formats
                val webFonts = mutableListOf<CompiledFont>()
                for (webFormat in webFormats) {
                    val baseFormat = webFormat.baseFormat ?: continue
                    val compression = webFormat.compression ?: continue
                    val extension = when (baseFormat) {
                        OutputFormat.OTF -> "otf"
                        OutputFormat.TTF -> "ttf"
                        else -> continue
                    }

                    // Find matching base fonts
                    val baseFonts = allBuiltFonts.filter { it.path.endsWith(".$extension") }
                    for (baseFont in baseFonts) {
                        val inputFile = File(baseFont.path)
                        val outputFile = File(
                            inputFile.parentFile,
                            inputFile.nameWithoutExtension + "." + compression.extension
                        )

                        if (compress(baseFont.path, outputFile.absolutePath, compression)) {
                            webFonts.add(CompiledFont(
                                path = outputFile.absolutePath,
                                name = outputFile.name,
                            ))
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

                // Filter out base fonts that were only built for web compression
                val requestedFonts = if (baseFormatsOnlyForWeb.isNotEmpty()) {
                    allBuiltFonts.filter { font ->
                        val isOtf = font.path.endsWith(".otf")
                        val isTtf = font.path.endsWith(".ttf")
                        when {
                            isOtf && OutputFormat.OTF in baseFormatsOnlyForWeb -> false
                            isTtf && OutputFormat.TTF in baseFormatsOnlyForWeb -> false
                            else -> true
                        }
                    }
                } else {
                    allBuiltFonts
                }

                CompilationResult.Success(fonts = requestedFonts + webFonts, output = outputBuilder.toString())
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

    private fun findOutputFiles(workingDir: File?, options: CompileOptions): List<CompiledFont> {
        val baseDir = options.outputDir?.let { File(it) } ?: workingDir ?: return emptyList()

        // fontmake creates format-specific subdirectories by default
        val outputDirs = if (options.outputDir != null) {
            listOf(baseDir)
        } else {
            listOf(
                "master_otf", "master_ttf", "master_otf_interpolatable", "master_ttf_interpolatable",
                "instance_otf", "instance_ttf", "variable_ttf", "variable_otf", "master_ufo"
            ).map { File(baseDir, it) }
        }

        return outputDirs
            .filter { it.exists() && it.isDirectory }
            .flatMap { dir ->
                dir.listFiles { file ->
                    file.isFile && (file.extension in listOf("otf", "ttf", "ufo", "ufoz"))
                }?.toList() ?: emptyList()
            }
            .map { file ->
                CompiledFont(
                    path = file.absolutePath,
                    name = file.name,
                )
            }
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
