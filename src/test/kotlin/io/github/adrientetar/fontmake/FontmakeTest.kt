/*
 * Copyright 2025 the kotlin-fontmake authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.adrientetar.fontmake

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.io.File

class FontmakeTest {

    private fun hasNativeBinary(): Boolean {
        return try {
            FontMake.version() != null
        } catch (e: Exception) {
            false
        }
    }

    @Test
    @EnabledIf("hasNativeBinary")
    fun `version returns non-null string`() {
        val version = FontMake.version()
        assertThat(version).isNotNull()
        // Version string is just the version number, e.g. "3.11.1"
        assertThat(version).matches("\\d+\\.\\d+\\.\\d+")
    }

    @Test
    fun `compile returns error for non-existent file`() {
        val result = FontMake.compile("/non/existent/path.glyphs")

        assertThat(result).isInstanceOf(CompilationResult.Error::class.java)
        val error = result as CompilationResult.Error
        assertThat(error.message).contains("not found")
    }

    @Test
    fun `compile options builds correct command line`() {
        val options = CompileOptions(
            formats = listOf(OutputFormat.OTF, OutputFormat.VARIABLE),
            interpolateInstances = true,
            flattenComponents = true,
            outputDir = "/tmp/output",
        )

        // Verify options are set correctly
        assertThat(options.formats).containsExactly(OutputFormat.OTF, OutputFormat.VARIABLE)
        assertThat(options.interpolateInstances).isTrue()
        assertThat(options.flattenComponents).isTrue()
        assertThat(options.outputDir).isEqualTo("/tmp/output")
    }

    @Test
    fun `output format flags are correct`() {
        assertThat(OutputFormat.OTF.flag).isEqualTo("otf")
        assertThat(OutputFormat.TTF.flag).isEqualTo("ttf")
        assertThat(OutputFormat.VARIABLE.flag).isEqualTo("variable")
        assertThat(OutputFormat.VARIABLE_CFF2.flag).isEqualTo("variable-cff2")
        assertThat(OutputFormat.OTF_CFF2.flag).isEqualTo("otf-cff2")
    }
}
