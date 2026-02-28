<div align="center">

kotlin-fontmake
===============

**Kotlin wrapper for [fontmake](https://github.com/googlefonts/fontmake), Google's Python font compiler.**

[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7f52ff.svg)](https://kotlinlang.org/)
[![Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE.txt)
[![Maven central](https://img.shields.io/maven-central/v/io.github.adrientetar/kotlin-fontmake?color=brightgreen)](https://central.sonatype.com/artifact/io.github.adrientetar/kotlin-fontmake)

</div>

[fontmake](https://github.com/googlefonts/fontmake) compiles fonts from various sources (`.glyphs`, `.ufo`, `.designspace`) into binaries (`.otf`, `.ttf`). It supports static instances and variable fonts with both TrueType and CFF/CFF2 outlines.

This library provides a Kotlin wrapper for fontmake using standalone binaries built with the [Nuitka] Python-to-native compiler. No Python installation is required.

## Features

- **Full fontmake capabilities**: OTF, TTF, variable fonts, CFF2
- **Web font compression**: WOFF and WOFF2 output (Brotli)
- **Self-contained**: No Python runtime needed
- **Cross-platform**: macOS (ARM64/x64), Linux (ARM64/x64), Windows (x64)
- **Simple API**: Kotlin-idiomatic interface

## Maven Library

```kotlin
repositories {
    mavenCentral()
}

val fontmakeVersion = "1.0.0"
val fontmakeTarget = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()

    val osPart = when {
        "mac" in os || "darwin" in os -> "macos"
        "windows" in os -> "windows"
        else -> "linux"
    }
    val archPart = when (arch) {
        "aarch64", "arm64" -> "arm64"
        else -> "x64"
    }
    "$osPart-$archPart"
}

dependencies {
    implementation("io.github.adrientetar:kotlin-fontmake:$fontmakeVersion")
    runtimeOnly("io.github.adrientetar:kotlin-fontmake:$fontmakeVersion:$fontmakeTarget")
}
```

## Usage

### Basic Compilation

```kotlin
import io.github.adrientetar.fontmake.*

// Compile a .glyphs file to OTF and TTF
val result = FontMake.compile("/path/to/MyFont.glyphs")

when (result) {
    is CompilationResult.Success -> {
        for (font in result.fonts) {
            println("Created: ${font.path}")
        }
    }
    is CompilationResult.Error -> {
        println("Failed: ${result.message}")
        println(result.output)
    }
}
```

### With Options

```kotlin
// Compile to variable font with CFF2 outlines
val result = FontMake.compile(
    sourcePath = "/path/to/MyFont.glyphs",
    options = CompileOptions(
        formats = listOf(OutputFormat.VARIABLE_CFF2),
        outputDir = "/path/to/output",
    )
)

// Compile OTF for desktop + WOFF2 for web (in one call)
val result = FontMake.compile(
    sourcePath = "/path/to/MyFont.glyphs",
    options = CompileOptions(
        formats = listOf(OutputFormat.OTF, OutputFormat.WOFF2_TTF),
        outputDir = "/path/to/output",
    )
)

// Compile with interpolated instances
val result = FontMake.compile(
    sourcePath = "/path/to/MyFont.designspace",
    options = CompileOptions(
        formats = listOf(OutputFormat.OTF),
        interpolateInstances = true,
        flattenComponents = true,
    )
)
```

### Convenience Methods

```kotlin
// Compile to specific output directory with specific formats
val result = FontMake.compile(
    sourcePath = "/path/to/MyFont.glyphs",
    outputDir = "/path/to/output",
    OutputFormat.OTF,
    OutputFormat.VARIABLE,
)
```

## Output Formats

| Format | Description |
|--------|-------------|
| `OTF` | Per-master OTF with CFF outlines |
| `TTF` | Per-master TTF with TrueType outlines |
| `OTF_CFF2` | Per-master OTF with CFF2 outlines |
| `VARIABLE` | TrueType variable font |
| `VARIABLE_CFF2` | CFF2 variable font |
| `OTF_INTERPOLATABLE` | OTF binaries for merging into variable font |
| `TTF_INTERPOLATABLE` | TTF binaries for merging into variable font |
| `UFO` | Convert Glyphs sources to UFO |

### Web Font Formats

Web formats are derived from a base format (OTF or TTF) and compressed:

| Format | Description |
|--------|-------------|
| `WOFF_TTF` | WOFF from TTF (zlib compressed) |
| `WOFF_OTF` | WOFF from OTF (zlib compressed) |
| `WOFF2_TTF` | WOFF2 from TTF (Brotli compressed) |
| `WOFF2_OTF` | WOFF2 from OTF (Brotli compressed) |

When you request a web format, the library automatically builds the required base format (if not already requested), compresses it, and returns only the formats you asked for.

## How It Works

This library bundles pre-compiled fontmake binaries created using [Nuitka], a Python-to-native compiler. The binary includes:

- Python interpreter (embedded)
- fontmake and all dependencies (fonttools, ufo2ft, glyphsLib, etc.)
- Brotli for WOFF2 compression
- Native extensions

At runtime, the appropriate binary is extracted from JAR resources to a temp directory and executed via subprocess.

## Development

To build this library locally, you need:

- JDK 11 or higher
- [uv] in your `PATH` (used to create/manage the Python venv and install build deps)
- A native build toolchain suitable for Nuitka on your OS (compiler/linker)
- On Linux: `patchelf` (required by Nuitka for onefile/standalone builds)

The build uses a few environment variables you can override:

- `PYTHON_VERSION` (default: `3.11`)
- `FONTMAKE_VERSION` (default: `3.11.1`)
- `NUITKA_VERSION` (default: `4.0.1`)
- `UV_PATH` (default: `uv`) if `uv` is not on your `PATH`

```bash
# Build the library (also builds the current-platform native fontmake binary)
./gradlew build

# Run Kotlin tests
./gradlew test

# Publish to Maven Local (~/.m2/repository)
./gradlew publishToMavenLocal
```

## Building Native Binaries

The native binaries are built via GitHub Actions. To build locally:

```bash
# Install dependencies
pip install nuitka fontmake

# Build standalone binary
python -m nuitka \
    --mode=standalone \
    --follow-imports \
    --include-package=fontmake \
    --include-package=fontTools \
    --include-package=ufo2ft \
    --output-filename=fontmake \
    src/main/python/fontmake_entry.py
```

[Nuitka]: https://github.com/Nuitka/Nuitka
[uv]: https://docs.astral.sh/uv/getting-started/installation/
